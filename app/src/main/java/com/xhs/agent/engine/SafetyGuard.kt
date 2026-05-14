package com.xhs.agent.engine

import android.util.Log
import com.xhs.agent.model.*
import kotlin.math.abs

/**
 * 安全守卫
 *
 * 职责：
 * 1. 操作频率限制（点赞/评论/滚动）
 * 2. 行为合理性检查
 * 3. 每日配额管理
 * 4. 异常行为检测
 */
class SafetyGuard(private val config: TaskConfig) {

    companion object {
        private const val TAG = "SafetyGuard"

        // 时间窗口（毫秒）
        private val HOUR_MS = 3600_000L
        private val MINUTE_MS = 60_000L
    }

    // 操作时间戳记录
    private val likeTimestamps = mutableListOf<Long>()
    private val commentTimestamps = mutableListOf<Long>()
    private val scrollTimestamps = mutableListOf<Long>()

    /**
     * 周期安全检查
     */
    fun check(state: ScreenState, context: SessionContext): SafetyCheck {
        // 1. 检查每日配额
        if (context.totalLikesToday >= config.safety.maxDailyLikes) {
            return SafetyCheck(false, SafetyAction.COOLDOWN,
                "每日点赞上限 (${config.safety.maxDailyLikes})")
        }
        if (context.totalCommentsToday >= config.safety.maxDailyComments) {
            return SafetyCheck(false, SafetyAction.COOLDOWN,
                "每日评论上限 (${config.safety.maxDailyComments})")
        }

        // 2. 检查会话时长
        val sessionMs = System.currentTimeMillis() - context.sessionStartTime
        if (sessionMs >= config.safety.maxSessionMinutes * MINUTE_MS) {
            return SafetyCheck(false, SafetyAction.COOLDOWN,
                "会话时长已达 ${config.safety.maxSessionMinutes} 分钟")
        }

        // 3. 清理过期的时间戳
        cleanupTimestamps()

        // 4. 检查小时频率
        val likesInHour = likeTimestamps.size
        if (likesInHour >= config.safety.maxLikesPerHour) {
            return SafetyCheck(false, SafetyAction.WAIT,
                "小时点赞上限 ($likesInHour/${config.safety.maxLikesPerHour})")
        }

        val commentsInHour = commentTimestamps.size
        if (commentsInHour >= config.safety.maxCommentsPerHour) {
            return SafetyCheck(false, SafetyAction.WAIT,
                "小时评论上限 ($commentsInHour/${config.safety.maxCommentsPerHour})")
        }

        return SafetyCheck(true)
    }

    /**
     * 单个动作验证
     */
    fun validateAction(action: AgentAction, context: SessionContext): Boolean {
        when (action.type) {
            ActionType.LIKE -> {
                // 是否已经在同一帖子操作过
                val postKey = action.targetId?.toString() ?: return false
                if (postKey in context.alreadyLiked) {
                    Log.w(TAG, "Skip: post already liked ($postKey)")
                    return false
                }
                // 记录时间戳
                likeTimestamps.add(System.currentTimeMillis())
            }

            ActionType.TYPE_COMMENT -> {
                val postKey = action.targetId?.toString() ?: return false
                if (postKey in context.alreadyCommented) {
                    Log.w(TAG, "Skip: post already commented ($postKey)")
                    return false
                }
                if (action.text.isNullOrBlank() || action.text.length > 1000) {
                    Log.w(TAG, "Skip: invalid comment text (empty or too long)")
                    return false
                }
                commentTimestamps.add(System.currentTimeMillis())
            }

            ActionType.SCROLL_DOWN, ActionType.SCROLL_UP -> {
                scrollTimestamps.add(System.currentTimeMillis())
            }

            else -> { /* 其他动作不做频率限制 */ }
        }
        return true
    }

    /**
     * 获取推荐的等待时间（毫秒）
     * 如果需要冷却，返回剩余的等待时间
     */
    fun getRecommendedWaitMs(actionType: ActionType): Long {
        val baseWait = when (actionType) {
            ActionType.LIKE -> MINUTE_MS / config.safety.maxLikesPerHour
            ActionType.TYPE_COMMENT -> MINUTE_MS / config.safety.maxCommentsPerHour
            ActionType.SCROLL_DOWN -> config.behavior.scrollCooldownMs
            else -> 2000L
        }

        // 加入 ±30% 随机变化
        val variation = (baseWait * 0.3).toLong()
        val wait = baseWait + (-variation..variation).random()
        return wait.coerceAtLeast(1000L)  // 最少 1 秒
    }

    private fun cleanupTimestamps() {
        val now = System.currentTimeMillis()
        likeTimestamps.removeAll { now - it > HOUR_MS }
        commentTimestamps.removeAll { now - it > HOUR_MS }
        scrollTimestamps.removeAll { now - it > MINUTE_MS }
    }

    /**
     * 重置所有计数（冷却后调用）
     */
    fun reset() {
        likeTimestamps.clear()
        commentTimestamps.clear()
        scrollTimestamps.clear()
    }
}
