package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentCoordinatorTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("call", name, args.toString())
    private fun start(c: SubAgentCoordinator) = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "check evidence"))).content)
    private fun get(c: SubAgentCoordinator, id: String, wait: Int = 1000) = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id).put("wait_ms", wait))).content)

    @Test fun parallelLimitAndRunOwnership() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, _ -> started.countDown(); release.await(); "done" }.use { c ->
            val first = start(c).getString("task_id")
            start(c)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals("SUB_AGENT_LIMIT", start(c).getString("code"))
            SubAgentCoordinator(listOf(model)) { _, _, _ -> "other" }.use { other ->
                assertFalse(get(other, first, 0).getBoolean("ok"))
            }
            release.countDown()
            assertEquals("completed", get(c, first).getString("status"))
        }
    }

    @Test fun cancelCannotBeOverwrittenByLateCompletion() {
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, controller ->
            started.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
            assertTrue(controller.isCancelled)
            returned.countDown()
            "late answer"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            c.execute(call("cancel_task", JSONObject().put("task_id", id)))
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            val result = get(c, id, 0)
            assertEquals("cancelled", result.getString("status"))
            assertEquals("", result.getString("result"))
        }
    }

    @Test fun closeCancelsControllerAndRejectsNewTasks() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val c = SubAgentCoordinator(listOf(model)) { _, _, controller ->
            controller.register { stopped.countDown() }
            started.countDown()
            CountDownLatch(1).await()
            "unused"
        }
        start(c)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        c.close()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertEquals("RUN_CLOSED", start(c).getString("code"))
    }

    @Test fun timeoutStopsWorkerAndResultsAreBoundedSensitive() {
        val stopped = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), timeoutMs = 100) { _, _, controller ->
            controller.register { stopped.countDown() }
            CountDownLatch(1).await()
            "unused"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals("timed_out", get(c, id, 0).getString("status"))
        }
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "a".repeat(20000) }.use { c ->
            val id = start(c).getString("task_id")
            val result = get(c, id)
            assertTrue(result.getString("result").length < 16100)
            assertTrue(result.getBoolean("review_required"))
            assertTrue(c.execute(call("get_task_result", JSONObject().put("task_id", id))).sensitive)
        }
    }

    @Test fun failuresDoNotExposeProviderSecrets() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> error("secret apiKey=do-not-leak") }.use { c ->
            val result = get(c, start(c).getString("task_id"))
            assertEquals("failed", result.getString("status"))
            assertFalse(result.toString().contains("do-not-leak"))
        }
    }
    @Test fun roleSelectionUsesConfiguredModelAndRejectsWrongWorker() {
        val other = model.copy(model = "review-model")
        var used = ""
        SubAgentCoordinator(listOf(model, other), roles = listOf("implementation", "review")) { cfg, _, _ ->
            used = cfg.model
            "summary"
        }.use { c ->
            val started = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "summarize").put("role", "summary"))).content)
            assertEquals("completed", get(c, started.getString("task_id")).getString("status"))
            assertEquals("review-model", used)
            val denied = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "summarize").put("role", "summary").put("worker", 1))).content)
            assertEquals("WORKER_ROLE_MISMATCH", denied.getString("code"))
        }
    }

    @Test fun missingImplementationRoleNeverFallsBackToDifferentModel() {
        SubAgentCoordinator(listOf(model), roles = listOf("review")) { _, _, _ -> "unused" }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "implement").put("role", "implementation").put("project", "/workspace/Test"))).content)
            assertEquals("ROLE_NOT_CONFIGURED", result.getString("code"))
        }
    }
    @Test fun contextLimitIsReportedToParentAsActionableFailure() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> throw SubAgentContextLimitException() }.use { c ->
            val id = start(c).getString("task_id")
            val result = get(c, id)
            assertEquals("failed", result.getString("status"))
            assertEquals("SUB_AGENT_CONTEXT_LIMIT", result.getString("error_code"))
            assertTrue(result.getString("result").contains("Split the task"))
        }
    }
    @Test fun fourSlotsRouteAdditionalImplementationWorkers() {
        val models = (1..4).map { model.copy(model = "model-$it") }
        var chosen = ""
        SubAgentCoordinator(models, roles = listOf("implementation", "review", "implementation", "implementation")) { config, _, _ ->
            chosen = config.model; "done"
        }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "check").put("worker", 4))).content)
            val done = get(c, result.getString("task_id"))
            assertEquals("completed", done.getString("status"))
            assertEquals("model-4", chosen)
            assertEquals(4, done.getJSONObject("context_usage").getInt("worker"))
            assertEquals("completed", done.getJSONObject("context_usage").getString("status"))
        }
    }

    @Test fun compressingChildDoesNotBlockOtherWorkerAndLateTelemetryCannotReviveIt() {
        val compressing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<SubAgentContextStats>())
        SubAgentCoordinator(listOf(model), onContext = { events += it },
            executeObservedChild = { _, prompt, _, _, _, _, emit ->
                if (prompt.contains("compress me")) {
                    emit(io.github.mangi.eta.agent.runtime.AgentEvent.ContextCompactionStarted(1))
                    compressing.countDown()
                    try { release.await() } catch (_: InterruptedException) { }
                    emit(io.github.mangi.eta.agent.runtime.AgentEvent.ContextCompactionStarted(2))
                }
                "done"
            }, executeChild = { _, _, _ -> error("Observed runner expected") }).use { c ->
            val first = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "compress me"))).content).getString("task_id")
            assertTrue(compressing.await(2, TimeUnit.SECONDS))
            assertTrue(get(c, first, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
            val second = start(c).getString("task_id")
            assertEquals("completed", get(c, second).getString("status"))
            c.execute(call("cancel_task", JSONObject().put("task_id", first)))
            release.countDown()
            assertEquals("cancelled", get(c, first, 0).getString("status"))
            assertFalse(get(c, first, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
            assertFalse(events.last { it.taskId == first }.isCompacting)
        }
    }

    @Test fun telemetryFailureDoesNotPreventCompletionOrCancellation() {
        SubAgentCoordinator(listOf(model), onContext = { error("UI gone") }) { _, _, _ -> "done" }.use { c ->
            assertEquals("completed", get(c, start(c).getString("task_id")).getString("status"))
        }
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), onContext = { error("UI gone") }) { _, _, controller ->
            controller.register { cancelled.countDown() }
            started.countDown()
            CountDownLatch(1).await()
            "unused"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            c.execute(call("cancel_task", JSONObject().put("task_id", id)))
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
    }

}
