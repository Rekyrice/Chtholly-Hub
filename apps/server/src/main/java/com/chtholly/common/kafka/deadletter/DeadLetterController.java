package com.chtholly.common.kafka.deadletter;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.kafka.DeadLetterStatus;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 死信消息管理接口（仅 ADMIN 可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequireRole(Role.ADMIN)
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class DeadLetterController {

    private static final Logger log =
            LoggerFactory.getLogger(DeadLetterController.class);
    private static final long DEFAULT_REPLAY_ACK_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(10L);
    private static final long DEFAULT_MAX_BLOCK_MILLIS =
            TimeUnit.SECONDS.toMillis(60L);
    private static final long DEFAULT_DELIVERY_TIMEOUT_MILLIS =
            TimeUnit.MINUTES.toMillis(2L);
    private static final long RECOVERY_SAFETY_MARGIN_MILLIS =
            TimeUnit.SECONDS.toMillis(30L);

    private final DeadLetterMessageService deadLetterMessageService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long replayAckTimeoutMillis;
    private final long replayRecoveryHorizonMillis;
    private final Supplier<String> attemptTokenSupplier;

    @Autowired
    public DeadLetterController(
            DeadLetterMessageService deadLetterMessageService,
            KafkaTemplate<String, String> kafkaTemplate) {
        this(
                deadLetterMessageService,
                kafkaTemplate,
                DEFAULT_REPLAY_ACK_TIMEOUT_MILLIS,
                recoveryHorizonMillis(kafkaTemplate),
                () -> UUID.randomUUID().toString());
    }

    DeadLetterController(
            DeadLetterMessageService deadLetterMessageService,
            KafkaTemplate<String, String> kafkaTemplate,
            long replayAckTimeoutMillis,
            long replayRecoveryHorizonMillis,
            Supplier<String> attemptTokenSupplier) {
        this.deadLetterMessageService = deadLetterMessageService;
        this.kafkaTemplate = kafkaTemplate;
        if (replayAckTimeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "Dead-letter replay ACK timeout must be positive");
        }
        if (replayRecoveryHorizonMillis <= replayAckTimeoutMillis) {
            throw new IllegalArgumentException(
                    "Dead-letter replay recovery horizon must exceed the ACK timeout");
        }
        this.replayAckTimeoutMillis = replayAckTimeoutMillis;
        this.replayRecoveryHorizonMillis = replayRecoveryHorizonMillis;
        this.attemptTokenSupplier =
                Objects.requireNonNull(
                        attemptTokenSupplier, "attemptTokenSupplier");
    }

    @GetMapping
    public DeadLetterPageResponse list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<DeadLetterResponse> items =
                deadLetterMessageService.list(topic, status, page, size)
                        .stream()
                        .map(DeadLetterResponse::from)
                        .toList();
        long total = deadLetterMessageService.count(topic, status);
        return new DeadLetterPageResponse(items, total, page, size);
    }

    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(@PathVariable long id) {
        DeadLetterMessageRow row = deadLetterMessageService.findById(id);
        if (row == null) {
            throw resourceNotFound();
        }
        String attemptToken = nextAttemptToken();
        boolean claimed = deadLetterMessageService.claimReplay(
                id, attemptToken, replayRecoveryHorizonMillis);
        if (!claimed) {
            throw statusConflict(id, "死信消息状态不允许重放");
        }

        CompletableFuture<?> confirmation;
        try {
            confirmation = kafkaTemplate.send(
                    row.getSourceTopic(),
                    row.getMessageKey(),
                    row.getMessageValue());
        } catch (RuntimeException synchronousFailure) {
            finishAfterSynchronousFailure(
                    id, attemptToken, synchronousFailure);
            throw new IllegalStateException(
                    "Kafka replay failed before broker confirmation",
                    synchronousFailure);
        }
        if (confirmation == null) {
            finishOrThrow(
                    id, attemptToken, DeadLetterStatus.UNCERTAIN);
            throw uncertainReplay(
                    "Kafka replay returned no broker confirmation future",
                    null);
        }

        CompletableFuture<ReplayOutcome> terminal =
                trackTerminalOutcome(id, attemptToken, confirmation);
        observeLateCompletion(id, attemptToken, terminal);
        ReplayOutcome outcome = awaitHttpWindow(terminal);
        if (outcome.failure() != null) {
            throw outcome.failure();
        }

        DeadLetterMessageRow updated = deadLetterMessageService.findById(id);
        if (updated == null) {
            throw new IllegalStateException(
                    "Confirmed dead-letter replay row disappeared");
        }
        return DeadLetterResponse.from(updated);
    }

    /**
     * Recovers an in-flight attempt only after its database-clock producer
     * delivery deadline and only for the supplied attempt generation.
     * Recovery never makes the row directly replayable.
     */
    @PostMapping("/{id}/recover-expired")
    public DeadLetterResponse recoverExpired(
            @PathVariable long id,
            @RequestParam String attemptToken) {
        boolean recovered =
                deadLetterMessageService.recoverExpiredReplay(
                        id, attemptToken);
        if (!recovered) {
            throw statusConflict(
                    id, "死信重放尚未到达安全核对时间");
        }
        return requireUpdatedRow(id, "Recovered dead-letter row disappeared");
    }

    /**
     * Resolves an attempt whose producer delivery window has ended.
     *
     * @param attemptToken opaque generation token returned by the admin response
     * @param published true when an operator verified publication, false when it is safe to retry
     */
    @PostMapping("/{id}/resolve")
    public DeadLetterResponse resolve(
            @PathVariable long id,
            @RequestParam String attemptToken,
            @RequestParam boolean published) {
        DeadLetterStatus target =
                published ? DeadLetterStatus.PENDING : DeadLetterStatus.DEAD;
        boolean resolved = deadLetterMessageService.resolveUncertain(
                id, attemptToken, target);
        if (!resolved) {
            throw statusConflict(id, "死信消息不处于待核对状态");
        }
        return requireUpdatedRow(id, "Resolved dead-letter replay row disappeared");
    }

    private CompletableFuture<ReplayOutcome> trackTerminalOutcome(
            long id,
            String attemptToken,
            CompletableFuture<?> confirmation) {
        return confirmation.handle((sendResult, failure) -> {
            if (failure != null) {
                RuntimeException uncertain = uncertainReplay(
                        "Kafka broker did not provide an unambiguous replay outcome",
                        unwrapCompletion(failure));
                finishOrThrow(
                        id, attemptToken, DeadLetterStatus.UNCERTAIN);
                return new ReplayOutcome(
                        DeadLetterStatus.UNCERTAIN, uncertain);
            }
            if (sendResult == null) {
                RuntimeException uncertain = uncertainReplay(
                        "Kafka replay completed without a broker send result",
                        null);
                finishOrThrow(
                        id, attemptToken, DeadLetterStatus.UNCERTAIN);
                return new ReplayOutcome(
                        DeadLetterStatus.UNCERTAIN, uncertain);
            }
            finishOrThrow(
                    id, attemptToken, DeadLetterStatus.PENDING);
            return new ReplayOutcome(DeadLetterStatus.PENDING, null);
        });
    }

    private ReplayOutcome awaitHttpWindow(
            CompletableFuture<ReplayOutcome> terminal) {
        try {
            return terminal.get(
                    replayAckTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw replayStillInProgress(interrupted);
        } catch (TimeoutException timeout) {
            throw replayStillInProgress(timeout);
        } catch (ExecutionException completionFailure) {
            throw propagateCompletion(completionFailure.getCause());
        }
    }

    private void finishAfterSynchronousFailure(
            long id,
            String attemptToken,
            RuntimeException synchronousFailure) {
        try {
            boolean restored = deadLetterMessageService.finishReplay(
                    id, attemptToken, DeadLetterStatus.DEAD);
            if (!restored) {
                synchronousFailure.addSuppressed(new IllegalStateException(
                        "Dead-letter replay attempt no longer owns its claim"));
            }
        } catch (RuntimeException restoreFailure) {
            if (restoreFailure != synchronousFailure) {
                synchronousFailure.addSuppressed(restoreFailure);
            }
        }
    }

    private void finishOrThrow(
            long id,
            String attemptToken,
            DeadLetterStatus targetStatus) {
        boolean finished = deadLetterMessageService.finishReplay(
                id, attemptToken, targetStatus);
        if (!finished) {
            throw new IllegalStateException(
                    "Dead-letter replay attempt no longer owns its claim");
        }
    }

    private void observeLateCompletion(
            long id,
            String attemptToken,
            CompletableFuture<ReplayOutcome> terminal) {
        terminal.whenComplete((outcome, failure) -> {
            if (failure != null) {
                log.error(
                        "Dead-letter replay terminal update failed id={} attempt={}: {}",
                        id,
                        attemptToken,
                        unwrapCompletion(failure).getMessage(),
                        unwrapCompletion(failure));
            } else if (outcome.failure() != null) {
                log.warn(
                        "Dead-letter replay requires manual verification id={} attempt={}: {}",
                        id,
                        attemptToken,
                        outcome.failure().getMessage());
            }
        });
    }

    private DeadLetterResponse requireUpdatedRow(
            long id,
            String missingMessage) {
        DeadLetterMessageRow updated = deadLetterMessageService.findById(id);
        if (updated == null) {
            throw new IllegalStateException(missingMessage);
        }
        return DeadLetterResponse.from(updated);
    }

    private String nextAttemptToken() {
        String attemptToken = attemptTokenSupplier.get();
        if (attemptToken == null || attemptToken.isBlank()) {
            throw new IllegalStateException(
                    "Dead-letter replay attempt token supplier returned no token");
        }
        return attemptToken;
    }

    private static IllegalStateException replayStillInProgress(
            Throwable cause) {
        return new IllegalStateException(
                "Kafka replay is still in progress; do not resolve or replay it "
                        + "before terminal completion or expiry recovery",
                cause);
    }

    private static IllegalStateException uncertainReplay(
            String detail,
            Throwable cause) {
        return new IllegalStateException(
                detail
                        + "; outcome is uncertain and requires explicit administrator resolution",
                cause);
    }

    private static RuntimeException propagateCompletion(Throwable failure) {
        Throwable unwrapped = unwrapCompletion(failure);
        if (unwrapped instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(
                "Dead-letter replay terminal update failed",
                unwrapped);
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private BusinessException statusConflict(long id, String message) {
        DeadLetterMessageRow row = deadLetterMessageService.findById(id);
        if (row == null) {
            return resourceNotFound();
        }
        return new BusinessException(
                ErrorCode.CONFLICT,
                message + "，当前状态为 " + row.getStatus(),
                HttpStatus.CONFLICT.value());
    }

    private static BusinessException resourceNotFound() {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "死信消息不存在");
    }

    private static long recoveryHorizonMillis(
            KafkaTemplate<String, String> kafkaTemplate) {
        ProducerFactory<String, String> producerFactory =
                kafkaTemplate.getProducerFactory();
        Map<String, Object> properties = producerFactory == null
                ? Map.of()
                : producerFactory.getConfigurationProperties();
        long maxBlock = configMillis(
                properties,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                DEFAULT_MAX_BLOCK_MILLIS);
        long deliveryTimeout = configMillis(
                properties,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                DEFAULT_DELIVERY_TIMEOUT_MILLIS);
        return Math.addExact(
                Math.addExact(maxBlock, deliveryTimeout),
                RECOVERY_SAFETY_MARGIN_MILLIS);
    }

    private static long configMillis(
            Map<String, Object> properties,
            String key,
            long defaultValue) {
        Object configured = properties.get(key);
        if (configured == null) {
            return defaultValue;
        }
        long value;
        if (configured instanceof Number number) {
            value = number.longValue();
        } else {
            try {
                value = Long.parseLong(configured.toString());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "Kafka producer " + key + " must be an integer",
                        invalid);
            }
        }
        if (value <= 0L) {
            throw new IllegalArgumentException(
                    "Kafka producer " + key + " must be positive");
        }
        return value;
    }

    private record ReplayOutcome(
            DeadLetterStatus status,
            RuntimeException failure) {}
}
