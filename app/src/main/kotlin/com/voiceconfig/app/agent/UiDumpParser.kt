package com.voiceconfig.app.agent

/**
 * 解析 `uiautomator dump` 产出的 XML，提取对文本 LLM 有用的节点摘要。
 *
 * 不依赖 Android 框架，纯字符串处理，便于单元测试。
 */
object UiDumpParser {

    private val NODE_REGEX = Regex("""<node\b[^>]*>""")
    private val ATTR_REGEX = Regex("""(\S+?)\s*=\s*"([^"]*)"""")

    data class UiNode(
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val className: String,
        val bounds: String,
        val clickable: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
        val visible: Boolean = true,
    ) {
        val hasLabel: Boolean get() = text.isNotBlank() || contentDesc.isNotBlank() || resourceId.isNotBlank()
    }

    /**
     * 返回紧凑摘要：每行一个节点。
     * 只包含有文本/描述/id 或可点击/可输入的节点，避免把整个布局树塞给 LLM。
     *
     * @param maxChars 输出总字符上限，超过会截断并提示，防止 UI 树过大挤占 LLM 注意力。
     */
    fun summarize(xml: String, maxNodes: Int = 120, maxChars: Int = 4000): String {
        val nodes = parse(xml)
            .filter { it.enabled && (it.hasLabel || it.clickable || it.focusable) }
            .distinctBy { node ->
                listOf(
                    node.text,
                    node.contentDesc,
                    node.resourceId,
                    node.bounds,
                    node.className,
                    node.clickable.toString(),
                    node.focusable.toString(),
                ).joinToString("|")
            }
            .sortedWith(
                compareByDescending<UiNode> { it.clickable || it.focusable },
            )
            .take(maxNodes)
        if (nodes.isEmpty()) {
            return "(未发现可交互或带文本的节点)"
        }
        val lines = nodes.map(::renderNode)
        val total = lines.sumOf { it.length + 1 }
        if (total <= maxChars) {
            return lines.joinToString("\n")
        }
        val kept = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            if (used + line.length + 1 > maxChars && kept.isNotEmpty()) break
            kept += line
            used += line.length + 1
        }
        val omitted = lines.size - kept.size
        return kept.joinToString("\n") + "\n...（UI 树过长已省略 $omitted 个节点）"
    }

    private fun renderNode(node: UiNode): String {
        val label = listOf(
            node.text.takeIf { it.isNotBlank() }?.let { "text=\"${it.take(64)}\"" },
            node.contentDesc.takeIf { it.isNotBlank() }?.let { "desc=\"${it.take(64)}\"" },
            node.resourceId.takeIf { it.isNotBlank() }?.let { "id=\"${it.take(48)}\"" },
        ).filterNotNull().joinToString(" ")
        val type = node.className.substringAfterLast('.').ifBlank { node.className }.take(24)
        val actions = buildList {
            if (node.clickable) add("clickable")
            if (node.focusable) add("focusable")
        }.joinToString(",")
        return "$label class=$type bounds=${node.bounds}${if (actions.isNotBlank()) " [$actions]" else ""}"
    }

    fun parse(xml: String): List<UiNode> =
        NODE_REGEX.findAll(xml).mapNotNull { match ->
            val attrs = ATTR_REGEX.findAll(match.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
            if (attrs.isEmpty()) return@mapNotNull null
            UiNode(
                text = attrs["text"].orEmpty(),
                contentDesc = attrs["content-desc"].orEmpty(),
                resourceId = attrs["resource-id"].orEmpty(),
                className = attrs["class"].orEmpty(),
                bounds = attrs["bounds"].orEmpty(),
                clickable = attrs["clickable"].equals("true", ignoreCase = true),
                focusable = attrs["focusable"].equals("true", ignoreCase = true),
                enabled = !attrs["enabled"].equals("false", ignoreCase = true),
            )
        }.toList()
}
