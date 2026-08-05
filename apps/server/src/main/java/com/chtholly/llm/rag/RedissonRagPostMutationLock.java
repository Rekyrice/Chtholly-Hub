package com.chtholly.llm.rag;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Redisson adapter that serializes one post's RAG projection mutations across nodes.
 *
 * <p>The lock relies on the project's existing Redisson watchdog while the mutation
 * runs. It therefore does not reserve a MySQL connection across content loading,
 * embedding calls, or Elasticsearch writes.</p>
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class RedissonRagPostMutationLock implements RagPostMutationLock {

    private static final long ACQUIRE_TIMEOUT_SECONDS = 5L;
    private static final String LOCK_KEY_PREFIX = "lock:rag:post:";

    private final RedissonClient redisson;

    /**
     * Creates the distributed RAG mutation lock adapter.
     *
     * @param redisson shared Redisson client
     */
    public RedissonRagPostMutationLock(RedissonClient redisson) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
    }

    @Override
    public int withLock(long postId, IntSupplier mutation) {
        Objects.requireNonNull(mutation, "mutation");
        RLock lock = Objects.requireNonNull(
                redisson.getLock(LOCK_KEY_PREFIX + postId),
                "RAG mutation lock");
        boolean acquired = false;
        Throwable operationFailure = null;
        try {
            acquired = lock.tryLock(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "Timed out acquiring RAG mutation lock for post " + postId);
            }
            try {
                return mutation.getAsInt();
            } catch (RuntimeException | Error failure) {
                operationFailure = failure;
                throw failure;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while acquiring RAG mutation lock for post " + postId,
                    failure);
        } finally {
            if (acquired) {
                release(lock, postId, operationFailure);
            }
        }
    }

    private static void release(
            RLock lock,
            long postId,
            Throwable operationFailure) {
        RuntimeException releaseFailure = null;
        try {
            lock.unlock();
        } catch (RuntimeException | Error failure) {
            releaseFailure = new IllegalStateException(
                    "Failed to release RAG mutation lock for post " + postId,
                    failure);
        }
        if (releaseFailure == null) {
            return;
        }
        if (operationFailure != null) {
            operationFailure.addSuppressed(releaseFailure);
            return;
        }
        throw releaseFailure;
    }
}
