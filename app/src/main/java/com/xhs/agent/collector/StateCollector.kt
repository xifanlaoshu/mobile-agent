package com.xhs.agent.collector

import android.util.Log
import com.xhs.agent.model.*
import com.xhs.agent.service.AgentAccessibilityService

/**
 * 状态采集器
 *
 * 整合以下数据源：
 * 1. Accessibility Service → UI 树
 * 2. MediaProjection → 截图
 * 3. OCR → 图片文字
 *
 * 输出结构化的 ScreenState
 */
class StateCollector(
    private val accessibilityService: AgentAccessibilityService,
    private val ocrProcessor: OcrProcessor? = null  // OCR 模块（可选）
) {
    companion object {
        private const val TAG = "StateCollector"
    }

    /**
     * 采集当前完整状态
     */
    suspend fun collect(): ScreenState {
        // 1. 采集 UI 树
        val uiTree = accessibilityService.collectUiTree()

        // 2. 如果需要，采集截图和 OCR（可选，较慢）
        val ocrTexts = ocrProcessor?.let {
            try {
                extractOcrTexts()
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                emptyList()
            }
        } ?: emptyList()

        // 3. 确定页面类型
        val pageType = classifyPage(uiTree)

        // 4. 构建 ScreenState
        return ScreenState(
            currentActivity = "",  // 可通过其他方式获取
            pageType = pageType,
            uiElements = flattenUiTree(uiTree),
            ocrTexts = ocrTexts,
            actionHistory = emptyList(),  // 由外部填充
            sessionContext = SessionContext()
        )
    }

    private suspend fun extractOcrTexts(): List<OcrTextBlock> {
        ocrProcessor ?: return emptyList()
        // 1. 通过 MediaProjection 截图（需在服务中实现）
        // 2. 对截图进行 OCR
        // 3. 返回文字块列表
        // 暂未实现 — 需先集成 MediaProjection
        Log.d(TAG, "OCR extraction not yet implemented")
        return emptyList()
    }

    private fun classifyPage(rootNode: UiNode?): PageType {
        rootNode ?: return PageType.UNKNOWN

        // 基于文本和结构特征判断页面类型
        val allTexts = collectAllTexts(rootNode)

        return when {
            anyContains(allTexts, "发现", "推荐", "首页") -> PageType.MAIN_FEED
            anyContains(allTexts, "笔记详情", "赞", "收藏") -> PageType.POST_DETAIL
            anyContains(allTexts, "搜索", "🔍") -> PageType.SEARCH
            anyContains(allTexts, "登录", "注册", "手机号") -> PageType.LOGIN
            else -> PageType.UNKNOWN
        }
    }

    private fun flattenUiTree(root: UiNode?): List<UiNode> {
        root ?: return emptyList()
        val result = mutableListOf<UiNode>()
        val queue = ArrayDeque(listOf(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisible) {
                result.add(node)
                queue.addAll(node.children)
            }
        }

        return result
    }

    private fun collectAllTexts(node: UiNode): List<String> {
        val texts = mutableListOf<String>()
        node.text?.let { texts.add(it) }
        node.contentDescription?.let { texts.add(it) }
        node.children.forEach { texts.addAll(collectAllTexts(it)) }
        return texts
    }

    private fun anyContains(texts: List<String>, vararg keywords: String): Boolean {
        return keywords.any { kw ->
            texts.any { it.contains(kw, ignoreCase = true) }
        }
    }
}
