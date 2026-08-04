package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentEvent;
import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.AgentProperties;
import com.chtholly.agent.memory.AgentConversationMemory;
import com.chtholly.agent.memory.AgentMemoryWriteException;
import com.chtholly.agent.observability.AgentExecutionTrace;
import com.chtholly.agent.observability.AgentTurnTraceLifecycle;
import io.micrometer.observation.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Expresses the ordered preparation, reasoning, response, and finalization phases of one turn. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentTurnOrchestrator {

    private final AgentProperties properties;
    private final AgentDomainConfig domainConfig;
    private final AgentTurnTraceLifecycle traceLifecycle;
    private final AgentTurnPreparationService preparationService;
    private final AgentLoopExecutor loopExecutor;
    private final AgentTurnResponseService responseService;
    private final AgentTurnCompletion completion;

    /** Creates the turn phase orchestrator. */
    public AgentTurnOrchestrator(
            AgentProperties properties,
            AgentDomainConfig domainConfig,
            AgentTurnTraceLifecycle traceLifecycle,
            AgentTurnPreparationService preparationService,
            AgentLoopExecutor loopExecutor,
            AgentTurnResponseService responseService,
            AgentTurnCompletion completion) {
        this.properties = properties;
        this.domainConfig = domainConfig;
        this.traceLifecycle = traceLifecycle;
        this.preparationService = preparationService;
        this.loopExecutor = loopExecutor;
        this.responseService = responseService;
        this.completion = completion;
    }

    /**
     * Executes one normalized turn command.
     *
     * @param command immutable turn inputs
     */
    public void run(Command command) {
        int maxSteps = Math.max(1, properties.getMaxSteps());
        AgentTurnTraceLifecycle.TraceScope scope = traceLifecycle.begin(
                command.userId(),
                command.control(),
                maxSteps,
                command.question(),
                command.pageContext(),
                properties.getModel());
        try (Observation.Scope ignored = scope.agentSpan().openScope()) {
            runPhases(command, maxSteps, scope);
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            String message = traceLifecycle.recordUnavailable(
                    unavailable,
                    command.control(),
                    scope.trace(),
                    domainConfig.errors().responseTimeout());
            if (message != null) {
                completion.emitError(command.sink(), message);
            }
        } finally {
            traceLifecycle.finishAfterClientDelivery(scope, command.control());
        }
    }

    private void runPhases(
            Command command,
            int maxSteps,
            AgentTurnTraceLifecycle.TraceScope scope) {
        AgentExecutionTrace trace = scope.trace();
        AgentTurnBudget turnBudget = command.control().budget();
        AgentTurnPreparationService.PreparedTurn prepared = null;
        try {
            turnBudget.check("turn_start");
            if (command.question() == null || command.question().isBlank()) {
                rejectEmptyQuestion(command.sink(), trace);
                return;
            }
            prepared = preparationService.prepare(new AgentTurnPreparationService.Request(
                    command.question(),
                    command.userId(),
                    command.memory(),
                    command.sessionId(),
                    command.pageContext(),
                    command.taskType(),
                    maxSteps,
                    trace,
                    scope.agentSpan(),
                    turnBudget));
            if (prepared.status() == AgentTurnPreparationService.Status.BOUNDARY) {
                responseService.completeBoundary(
                        prepared,
                        command.question(),
                        command.memory(),
                        command.sink(),
                        trace,
                        scope.agentSpan());
                return;
            }
            AgentLoopResult result = loopExecutor.execute(
                    prepared.loopRequest(), trace, scope.agentSpan(), command.sink());
            responseService.completeLoopResult(
                    result,
                    prepared,
                    command.question(),
                    command.memory(),
                    command.sink(),
                    trace,
                    scope.agentSpan());
        } catch (AgentTurnBudget.UnavailableException unavailable) {
            throw unavailable;
        } catch (AgentMemoryWriteException exception) {
            trace.terminateError();
            trace.markFailure(AgentExecutionTrace.FailureType.MEMORY_WRITE_FAILED);
            trace.setErrorMessage(AgentExecutionTrace.FailureType.MEMORY_WRITE_FAILED.name());
            throw exception;
        } catch (RuntimeException exception) {
            trace.terminateError();
            trace.markFailure(AgentExecutionTrace.FailureType.INTERNAL_ERROR);
            trace.setErrorMessage(AgentExecutionTrace.FailureType.INTERNAL_ERROR.name());
            throw exception;
        } finally {
            preparationService.finish(prepared, trace);
        }
    }

    private void rejectEmptyQuestion(
            Consumer<AgentEvent> sink,
            AgentExecutionTrace trace) {
        trace.terminateError();
        trace.markFailure(AgentExecutionTrace.FailureType.INVALID_INPUT);
        trace.setErrorMessage(domainConfig.errors().questionEmpty());
        completion.emitError(sink, domainConfig.errors().questionEmpty());
    }

    /** Immutable command accepted by the turn phase orchestrator. */
    public record Command(
            String question,
            long userId,
            AgentConversationMemory memory,
            AgentTurnControl control,
            String sessionId,
            String pageContext,
            String taskType,
            Consumer<AgentEvent> sink) {
    }
}
