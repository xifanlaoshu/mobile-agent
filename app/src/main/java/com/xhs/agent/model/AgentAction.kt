package com.xhs.agent.model

import kotlinx.serialization.Serializable

/**
 * AI 返回的动作指令
 */
data class AgentAction(
    val type: ActionType,
    val targetId: Int? = null,
    val coordinate: Coordinate? = null,
    val text: String? = null,
    val durationMs: Long? = null,
    val reason: String = ""
)

/**
 * 动作类型枚举
 */
enum class ActionType(val description: String) {
    SCROLL_DOWN("向下滚动"),
    SCROLL_UP("向上滚动"),
    TAP("点击元素"),
    TAP_COORD("按坐标点击"),
    BACK("返回"),
    HOME("回到桌面"),
    WAIT("等待"),
    LIKE("点赞"),
    ENTER_COMMENT("进入评论"),
    TYPE_COMMENT("输入评论"),
    ENTER_POST("进入帖子"),
    EXIT_POST("退出帖子"),
    NAVIGATE_TAB("切换底部Tab")
}

/**
 * 屏幕坐标点
 */
data class Coordinate(
    val x: Int,
    val y: Int
)

/**
 * AI 的完整响应结构
 */
@Serializable
data class AiResponse(
    val actions: List<AiActionDto>,
    val observation: String = "",
    val reasoning: String = ""
)

/**
 * AI 响应中的单个动作（反序列化用）
 */
@Serializable
data class AiActionDto(
    val type: String,
    val target_id: Int? = null,
    val text: String? = null,
    val duration_ms: Long? = null,
    val reason: String = ""
)

/**
 * 动作执行结果
 */
sealed class ExecutionResult {
    data class Success(val action: AgentAction) : ExecutionResult()
    data class Failed(val action: AgentAction, val error: String) : ExecutionResult()
    data class Skipped(val action: AgentAction, val reason: String) : ExecutionResult()
}
