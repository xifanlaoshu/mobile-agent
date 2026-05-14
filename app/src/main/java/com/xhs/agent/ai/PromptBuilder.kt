package com.xhs.agent.ai

import com.xhs.agent.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 用户 Prompt 构建器
 *
 * 将结构化的 ScreenState 转换为 DeepSeek 可读的文本描述。
 * 包含智能压缩策略以节省 token。
 */
object PromptBuilder {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 构建发送给 AI 的用户消息
     */
    fun buildUserPrompt(
        screenState: ScreenState,
        userInterests: String,
        sessionContext: SessionContext
    ): String {
        return buildString {
            appendLine("=== 屏幕状态 ===")
            appendLine("当前页面: ${screenState.currentActivity} (${screenState.pageType.name})")
            appendLine()

            // UI 树（压缩后）
            appendLine("=== 页面结构 ===")
            appendLine(compressUiTree(screenState.uiElements))
            appendLine()

            // OCR 文本（如果有）
            if (screenState.ocrTexts.isNotEmpty()) {
                appendLine("=== 识别到的文字 ===")
                screenState.ocrTexts.take(10).forEach { block ->
                    appendLine("  [${block.bounds.centerX},${block.bounds.centerY}] ${block.text.take(100)}")
                }
                if (screenState.ocrTexts.size > 10) {
                    appendLine("  ... 还有 ${screenState.ocrTexts.size - 10} 段文字")
                }
                appendLine()
            }

            // 操作历史
            appendLine("=== 操作历史 ===")
            if (screenState.actionHistory.isEmpty()) {
                appendLine("  (无历史操作)")
            } else {
                screenState.actionHistory.takeLast(5).forEach { result ->
                    val timeStr = timeFormat.format(Date(result.timestamp))
                    val status = if (result.success) "✓" else "✗"
                    appendLine("  [$timeStr] $status ${result.action.type.name} " +
                            "(${result.action.reason.take(40)})")
                }
            }
            appendLine()

            // 时间上下文
            appendLine("=== 时间信息 ===")
            appendLine("当前时间: ${timeFormat.format(Date())}")
            appendLine("运行时长: ${formatDuration(System.currentTimeMillis() - sessionContext.sessionStartTime)}")
            appendLine("今日已点赞: ${sessionContext.totalLikesToday} 次")
            appendLine("今日已评论: ${sessionContext.totalCommentsToday} 次")
            appendLine()

            // 可用动作提示
            appendLine("可用动作: scroll_down, scroll_up, tap(id), back, wait(ms), " +
                    "like(id), enter_comment(id), type_comment(text), enter_post(id), exit_post")
            appendLine()
            appendLine("请根据以上屏幕状态，决定下一步要执行的动作。输出 JSON 格式。")
        }
    }

    /**
     * 压缩 UI 树为可读的文本表示
     *
     * 核心策略：
     * 1. 只保留可见元素
     * 2. 合并无文本的纯布局容器
     * 3. 识别卡片边界（一组紧密排列的交互元素）
     * 4. 为每个可点击/可交互元素保留 ID 引用
     * 5. 对列表做截断（前 N 项，其余计数）
     */
    private fun compressUiTree(nodes: List<UiNode>, maxDepth: Int = 3): String {
        if (nodes.isEmpty()) return "  (空页面)"

        return buildString {
            val cardGroups = groupIntoCards(nodes)

            cardGroups.forEachIndexed { index, group ->
                when (group) {
                    is CardGroup.Single -> {
                        appendLine(formatNode(group.node, indent = 0))
                    }
                    is CardGroup.FeedCard -> {
                        appendLine(renderFeedCard(group, index + 1))
                    }
                    is CardGroup.NavBar -> {
                        appendLine(renderNavBar(group))
                    }
                    is CardGroup.Header -> {
                        appendLine(renderHeader(group))
                    }
                }
            }
        }
    }

    private sealed class CardGroup {
        data class Single(val node: UiNode) : CardGroup()
        data class FeedCard(
            val nodes: List<UiNode>,
            val title: String,
            val author: String?,
            val likes: String?,
            val isLiked: Boolean?,
            val id: Int
        ) : CardGroup()
        data class NavBar(val items: List<String>, val activeIndex: Int) : CardGroup()
        data class Header(val items: List<UiNode>) : CardGroup()
    }

    private fun groupIntoCards(nodes: List<UiNode>): List<CardGroup> {
        val groups = mutableListOf<CardGroup>()

        // 简化的卡片分组逻辑
        // 实际实现需要考虑 Accessibility 树的结构特征
        for (node in nodes) {
            if (node.className.contains("ListView") || node.className.contains("RecyclerView")) {
                // 列表/RecyclerView：信息流
                node.children.forEachIndexed { idx, child ->
                    if (isFeedCard(child)) {
                        val card = extractFeedCard(child, idx)
                        groups.add(card)
                    }
                }
            } else if (node.className.contains("TabWidget") || 
                       node.className.contains("Tab")) {
                // Tab 栏
                val tabs = node.children.mapNotNull { it.text }
                groups.add(CardGroup.NavBar(tabs, 0))
            } else {
                groups.add(CardGroup.Single(node))
            }
        }

        return groups
    }

    private fun isFeedCard(node: UiNode): Boolean {
        // 启发式判断：包含标题文本、有点击行为的组合容器
        val hasClickable = node.isClickable || node.children.any { it.isClickable }
        val hasText = node.text != null || node.children.any { it.text != null }
        val hasImage = node.children.any { 
            it.className.contains("ImageView") 
        }
        return hasClickable && hasText && hasImage
    }

    private fun extractFeedCard(root: UiNode, index: Int): CardGroup.FeedCard {
        val texts = mutableListOf<String>()
        var author: String? = null
        var likes: String? = null
        var isLiked: Boolean? = null

        fun extract(node: UiNode) {
            node.text?.let { texts.add(it) }
            if (node.contentDescription?.contains("点赞") == true) {
                isLiked = node.isChecked
                likes = node.contentDescription
            }
            node.children.forEach { extract(it) }
        }
        extract(root)

        val title = texts.firstOrNull() ?: ""
        // 简单启发：第二段文字可能是作者
        if (texts.size > 1) author = texts[1]

        return CardGroup.FeedCard(
            nodes = listOf(root),
            title = title,
            author = author,
            likes = likes,
            isLiked = isLiked,
            id = root.id
        )
    }

    private fun renderFeedCard(card: CardGroup.FeedCard, index: Int): String {
        val likeIcon = if (card.isLiked == true) "♥" else "♡"
        return buildString {
            appendLine("  ┌─ 内容卡片#${index} (id: c${card.id}) ────────────────")
            appendLine("  │  ${card.author?.let { "作者: $it" } ?: ""}     $likeIcon")
            appendLine("  │  标题: ${card.title.take(40)}")
            card.likes?.let { appendLine("  │  $it") }
            appendLine("  │  bounds: [可点击]")
            appendLine("  └──────────────────────────────────────────")
        }
    }

    private fun renderNavBar(nav: CardGroup.NavBar): String {
        val items = nav.items.joinToString(" | ") { it }
        return "  [底部导航] $items"
    }

    private fun renderHeader(header: CardGroup.Header): String {
        val items = header.items.joinToString(" ") { it.text ?: "[图标]" }
        return "  [标题栏] $items"
    }

    private fun formatNode(node: UiNode, indent: Int): String {
        val prefix = "  ".repeat(indent)
        val text = node.text?.take(50) ?: node.contentDescription?.take(50) ?: ""
        val info = buildString {
            append(prefix)
            append("${node.className.split(".").last()}")
            if (text.isNotEmpty()) append(" \"$text\"")
            if (node.isClickable) append(" [可点击]")
            if (node.isScrollable) append(" [可滚动]")
            append(" (id:${node.id})")
        }
        return info
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        return "${minutes}分${seconds}秒"
    }
}
