package com.xhs.agent.model

import kotlinx.serialization.Serializable

/**
 * 手机当前屏幕的完整状态表示
 * 由 StateCollector 采集，用于构建发给 AI 的 Prompt
 */
data class ScreenState(
    val currentActivity: String,          // 当前 Activity 名称
    val pageType: PageType,               // 页面类型
    val uiElements: List<UiNode>,         // UI 树（压缩后）
    val ocrTexts: List<OcrTextBlock>,     // OCR 提取的文本块
    val actionHistory: List<ActionResult>, // 最近操作历史
    val sessionContext: SessionContext,    // 会话上下文
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 页面类型分类
 */
enum class PageType {
    SPLASH,             // 启动页
    MAIN_FEED,          // 主信息流（首页/发现）
    POST_DETAIL,        // 帖子详情
    SEARCH,             // 搜索页面
    PROFILE,            // 个人主页
    COMMENT_SECTION,    // 评论区域
    LOGIN,              // 登录页
    UNKNOWN             // 未知
}

/**
 * UI 节点（从 Accessibility 树压缩后的语义表示）
 */
data class UiNode(
    val id: Int,                        // 唯一标识（在当前页面内）
    val text: String?,                  // 显示文本
    val contentDescription: String?,    // 内容描述（用于 ImageView 等）
    val className: String,              // 控件类型 (TextView/ImageView/...)
    val bounds: Rect,                   // 屏幕坐标 [left, top, right, bottom]
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean? = null,     // 选中状态（用于点赞按钮）
    val isVisible: Boolean = true,
    val children: List<UiNode> = emptyList(),
    val depth: Int = 0
)

/**
 * 屏幕坐标
 */
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * OCR 识别的文字块
 */
data class OcrTextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 1.0f
)

/**
 * 会话上下文
 */
data class SessionContext(
    val alreadyLiked: Set<String> = emptySet(),
    val alreadyCommented: Set<String> = emptySet(),
    val seenPostTitles: List<String> = emptyList(),
    val totalLikesToday: Int = 0,
    val totalCommentsToday: Int = 0,
    val sessionStartTime: Long = System.currentTimeMillis(),
    val phase: BrowsePhase = BrowsePhase.LAUNCHING
)

enum class BrowsePhase {
    LAUNCHING,           // 启动 App
    EXPLORING_FEED,      // 浏览信息流
    READING_POST,        // 阅读帖子
    INTERACTING,         // 互动操作
    NAVIGATING,          // 导航切换
    COOLDOWN             // 休息冷却
}

/**
 * 操作结果记录
 */
data class ActionResult(
    val action: AgentAction,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
