package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject

/** Read-only, bounded handoff. Starting a parent never resumes historical groups. */
internal object AgentChildTaskHandoff {
    fun appendToPrompt(prompt: String, ownerId: String): String {
        val listing = runCatching {
            JSONObject(AgentChildTaskGroups.execute(ownerId, null, AgentModelClient.ToolCall(
                "child-handoff", "get_task_result", "{}",
            )).content)
        }.getOrNull() ?: return prompt
        if (listing.optJSONArray("tasks")?.length() == 0 || !listing.optBoolean("ok")) return prompt
        return prompt + "\n\n" + """
            [Runtime Historical Sub-Tasks Summary]
            Below is a read-only status summary of existing sub-tasks in current session.
            They were not automatically resumed at turn start. Call get_task_result to query status first.
            健康或暂停的旧任务应保留其冻结的原子代理配置，按需要逐个 continue；不要批量启动全部历史任务，也不要重复派发同一工作。
            主代理停止、设置变更或主网络失败不是子模型故障证据。只有子任务确已失败且 can_replace 允许时，才可按替代策略使用用户新配置重派，并携带 replace_task_id。
            若摘要有 next_offset，请按需分页查询；已完成或停止的结果仍应查询复用。
        """.trimIndent() + "\n" + listing.toString().take(12_000) + "\n[/运行时旧子任务摘要]"
    }
}
