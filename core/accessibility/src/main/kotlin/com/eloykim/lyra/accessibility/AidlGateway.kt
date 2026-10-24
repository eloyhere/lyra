package com.eloykim.lyra.accessibility

import com.eloykim.lyra.agent.ActionResult
import com.eloykim.lyra.agent.AutomationGateway
import com.eloykim.lyra.agent.Observation
import com.eloykim.lyra.agent.ObservedElement
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 AIDL 的 JSON 协议翻译成 Agent 用的类型。
 *
 * 这里是唯一做 JSON 解析的地方：Agent 循环本身不关心传输格式。
 */
class AidlGateway : AutomationGateway {

    override suspend fun observe(): Observation {
        val json = AutomationClient.observe()
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) {
            throw IllegalStateException(root.optString("code") + ": " + root.optString("message"))
        }
        return parseObservation(root.getJSONObject("observation"))
    }

    override suspend fun tap(nodeId: Int, obsId: Long): ActionResult =
        parseResult(AutomationClient.tap(nodeId, obsId))

    override suspend fun setText(nodeId: Int, obsId: Long, text: String): ActionResult =
        parseResult(AutomationClient.setText(nodeId, obsId, text))

    override suspend fun scroll(direction: Int, obsId: Long): ActionResult =
        parseResult(AutomationClient.scroll(direction, obsId))

    override suspend fun launch(packageName: String): ActionResult =
        parseResult(AutomationClient.launch(packageName))

    override suspend fun back(): ActionResult =
        parseResult(AutomationClient.globalAction("back"))

    private fun parseResult(json: String): ActionResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ActionResult.Failed("bad_json", json.take(120))

        val observation = root.optJSONObject("observation")?.let { parseObservation(it) }

        if (root.optBoolean("ok")) {
            return ActionResult.Ok(observation)
        }

        val code = root.optString("code", "unknown")
        val message = root.optString("message", "")
        val scope = root.optString("scope").ifBlank { null }
        return when (code) {
            "blocked" -> ActionResult.Blocked(code, message, scope, observation)
            "authorization_required", "authorization_pending" -> ActionResult.NeedsConsent(
                challenge = root.optString("challenge").ifBlank { null },
                scope = scope,
                message = message,
                observation = observation,
            )

            else -> ActionResult.Failed(code, message, observation)
        }
    }

    private fun parseObservation(json: JSONObject): Observation {
        val elementsJson = json.optJSONArray("elements") ?: JSONArray()
        val elements = ArrayList<ObservedElement>(elementsJson.length())
        for (index in 0 until elementsJson.length()) {
            val item = elementsJson.optJSONObject(index) ?: continue
            elements += ObservedElement(
                id = item.optInt("id"),
                label = item.optString("text").ifBlank { item.optString("desc") }.ifBlank { item.optString("role") },
                clickable = item.optBoolean("clickable"),
                editable = item.optBoolean("editable"),
                scrollable = item.optBoolean("scrollable"),
            )
        }
        return Observation(
            obsId = json.optLong("obsId"),
            packageName = json.optString("package"),
            elements = elements,
        )
    }
}
