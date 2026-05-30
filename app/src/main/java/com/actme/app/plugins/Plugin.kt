package com.actme.app.plugins

import org.json.JSONObject

interface Plugin {
    val id: String
    val name: String
    val description: String
    val isBuiltin: Boolean get() = false
    val tools: List<ToolDef>

    suspend fun execute(toolName: String, args: JSONObject): ToolCallResult

    /** Chat-bubble card HTML fragment. null = no card UI for this tool. */
    fun getCardHtml(toolName: String, data: Map<String, String>): String? = null

    /**
     * Management page HTML body. null = builtin plugin uses a Compose route instead.
     * @see composeRoute
     */
    fun getManagementHtml(): String? = null

    /**
     * Compose nav route for builtin plugins whose management page is not WebView.
     * Ignored when [getManagementHtml] is non-null.
     */
    val composeRoute: String? get() = null
}

data class ToolDef(
    val name: String,
    val description: String,
    /** JSON Schema string describing the tool's parameters object. */
    val parametersSchema: String
)

data class ToolCallResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, String> = emptyMap()
)
