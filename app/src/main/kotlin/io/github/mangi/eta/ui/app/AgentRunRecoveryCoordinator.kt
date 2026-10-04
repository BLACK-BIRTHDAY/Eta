package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: AgentRunCheckpointStore.Checkpoint?,
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
        val reattaches: List<AgentRunCheckpointStore.Checkpoint> = listOfNotNull(reattach),
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunIds: Set<String>,
        locallyObservedRunIds: Set<String>,
    ): Plan {
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId in locallyObservedRunIds }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId in locallyObservedRunIds || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        val reattaches = if (activeStateKnown) {
            unresolved.filter { it.runId in activeRunIds }
        } else {
            emptyList()
        }
        val reattachIds = reattaches.mapTo(mutableSetOf()) { it.runId }

        return Plan(
            completed = completed,
            reattach = reattaches.firstOrNull(),
            reattaches = reattaches,
            interrupted = if (activeStateKnown && terminalStateKnown) {
                unresolved.filterNot { it.runId in reattachIds }
            } else {
                emptyList()
            },
        )
    }

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunId: String?,
        locallyObservedRunId: String?,
    ): Plan = plan(
        checkpoints = checkpoints,
        completedRuns = completedRuns,
        activeStateKnown = activeStateKnown,
        terminalStateKnown = terminalStateKnown,
        activeRunIds = setOfNotNull(activeRunId),
        locallyObservedRunIds = setOfNotNull(locallyObservedRunId),
    )

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
