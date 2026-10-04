package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun sameProcessActiveCheckpointIsReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active", owner = "same-process")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun terminalResultTakesPrecedenceAndKeepsCheckpointForTraceReplay() {
        val checkpoint = checkpoint("run-complete")
        val completed = completed("run-complete")

        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint),
            completedRuns = listOf(completed),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = "run-complete",
            locallyObservedRunId = null,
        )

        assertEquals(completed, plan.completed.single().result)
        assertEquals(checkpoint, plan.completed.single().checkpoint)
        assertNull(plan.reattach)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun inactiveCheckpointIsInterruptedButLocallyObservedRunIsIgnored() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale"), checkpoint("run-local")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunId = null,
            locallyObservedRunId = "run-local",
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertNull(plan.reattach)
    }

    @Test
    fun unavailableRuntimeLeavesUnresolvedCheckpointUntouched() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunId = null,
            locallyObservedRunId = null,
        )

        assertTrue(plan.interrupted.isEmpty())
        assertNull(plan.reattach)
    }

    @Test
    fun knownActiveRunCanReattachEvenWhenTerminalQueryFailed() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active"), checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunId = "run-active",
            locallyObservedRunId = null,
        )

        assertEquals("run-active", plan.reattach?.runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun multipleLocallyObservedRunsAreNotInterrupted() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-1"), checkpoint("run-2"), checkpoint("run-stale")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = emptySet(),
            locallyObservedRunIds = setOf("run-1", "run-2"),
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertTrue(plan.reattaches.isEmpty())
    }

    @Test
    fun multipleActiveRunsAreAllReattachedConcurrently() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-1"), checkpoint("run-2"), checkpoint("run-stale")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-1", "run-2"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-1", "run-2"), plan.reattaches.map { it.runId })
        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
    }

    private fun checkpoint(
        runId: String,
        owner: String = "old-process",
    ) = AgentRunCheckpointStore.Checkpoint(
        runId = runId,
        ownerInstanceId = owner,
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        events = emptyList(),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun completed(runId: String) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = 3L,
    )
}
