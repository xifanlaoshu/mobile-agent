package com.xhs.agent.model

/**
 * 用户任务配置
 */
data class TaskConfig(
    val interests: List<InterestCategory> = emptyList(),
    val behavior: BehaviorConfig = BehaviorConfig(),
    val safety: SafetyConfig = SafetyConfig()
)

data class InterestCategory(
    val name: String,
    val keywords: List<String>,
    val priority: Int
)

data class BehaviorConfig(
    val likeThreshold: Int = 70,
    val commentThreshold: Int = 85,
    val minPostViewSec: Int = 3,
    val maxPostViewSec: Int = 20,
    val scrollCooldownMs: Long = 3000,
    val scrollVariation: Float = 0.5f
)

data class SafetyConfig(
    val maxLikesPerHour: Int = 30,
    val maxCommentsPerHour: Int = 5,
    val maxSessionMinutes: Int = 45,
    val cooldownMinutes: Int = 15,
    val maxDailyLikes: Int = 200,
    val maxDailyComments: Int = 30,
    val sameActionThreshold: Int = 5
)
