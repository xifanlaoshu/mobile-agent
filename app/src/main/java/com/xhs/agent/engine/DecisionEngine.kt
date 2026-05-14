package com.xhs.agent.engine

import android.util.Log
import com.xhs.agent.ai.DeepSeekClient
import com.xhs.agent.collector.StateCollector
import com.xhs.agent.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * 决策引擎 — 主循环
 *
 * 职责：
 * 1. 协调状态采集 → AI 决策 → 动作执行的循环
 * 2. 管理会话生命周期
 * 3. 状态机转换
 * 4. 异常检测和恢复
 * 5. 暴露状态供 UI 层监控
 */
class DecisionEngine(
    private val stateCollector: StateCollector,
    private val deepSeekClient: DeepSeekClient,
    private val actionExecutor: ActionExecutor,
    private val safetyGuard: SafetyGuard,
    private val config: TaskConfig
) {
    companion object {
        private const val TAG = "DecisionEngine"
        private const val CYCLE_DELAY_MIN_MS = 2000L
        private const val CYCLE_DELAY_MAX_MS = 5000L
    }

    // ============ 状态流（UI 可观察） ============

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState

    private val _sessionContext = MutableStateFlow(createInitialContext())
    val sessionContext: StateFlow<SessionContext> = _sessionContext

    private val _lastAction = MutableStateFlow<ActionResult?>(null)
    val lastAction: StateFlow<ActionResult?> = _lastAction

    private val _logs = MutableStateFlow<List<EngineLog>>(emptyList())
    val logs: StateFlow<List<EngineLog>> = _logs

    // ============ 内部状态 ============

    private var job: Job? = null
    private val actionHistory = mutableListOf<ActionResult>()
    private var consecutiveSameAction = 0
    private var lastActionType: ActionType? = null

    // ============ 生命周期 ============

    /**
     * 启动自动化引擎
     */
    fun start() {
        if (job?.isActive == true) {
            Log.w(TAG, "Engine already running")
            return
        }

        _engineState.value = EngineState.STARTING
        _sessionContext.value = createInitialContext()

        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                runMainLoop()
            } catch (e: CancellationException) {
                Log.i(TAG, "Engine cancelled")
                _engineState.value = EngineState.STOPPED
            } catch (e: Exception) {
                Log.e(TAG, "Engine crashed", e)
                _engineState.value = EngineState.ERROR
                addLog(LogLevel.ERROR, "引擎异常崩溃: ${e.message}")
            }
        }
    }

    /**
     * 停止自动化引擎
     */
    fun stop() {
        Log.i(TAG, "Stopping engine")
        job?.cancel()
        job = null
        _engineState.value = EngineState.STOPPED
        addLog(LogLevel.INFO, "引擎已停止")
    }

    /**
     * 暂停引擎（保留状态）
     */
    fun pause() {
        if (_engineState.value == EngineState.RUNNING) {
            _engineState.value = EngineState.PAUSED
            addLog(LogLevel.INFO, "引擎已暂停")
        }
    }

    /**
     * 恢复运行
     */
    fun resume() {
        if (_engineState.value == EngineState.PAUSED) {
            _engineState.value = EngineState.RUNNING
            addLog(LogLevel.INFO, "引擎已恢复")
        }
    }

    // ============ 主循环 ============

    private suspend fun runMainLoop() {
        addLog(LogLevel.INFO, "引擎启动，开始自动化循环")
        _sessionContext.value = _sessionContext.value.copy(phase = BrowsePhase.LAUNCHING)

        // Phase 1: 启动小红书
        executePhaseLaunch()

        while (isActive()) {
            _sessionContext.value = _sessionContext.value.copy(
                phase = BrowsePhase.EXPLORING_FEED
            )

            // 1. 采集当前状态
            addLog(LogLevel.DEBUG, "采集屏幕状态...")
            val screenState = stateCollector.collect()
            addLog(LogLevel.DEBUG, "页面类型: ${screenState.pageType}, 元素: ${screenState.uiElements.size}")

            // 2. 安全检查
            val safetyCheck = safetyGuard.check(screenState, _sessionContext.value)
            if (!safetyCheck.isAllowed) {
                handleSafetyBlock(safetyCheck)
                continue
            }

            // 3. AI 决策
            val phaseContext = _sessionContext.value.copy(
                phase = resolvePhase(screenState)
            )
            _sessionContext.value = phaseContext

            addLog(LogLevel.INFO, "调用 DeepSeek 决策...")
            val decision = deepSeekClient.decideWithRetry(
                screenState = screenState,
                systemPrompt = "",  // 由调用方提供
                userInterests = config.interests.joinToString("\n") {
                    "[${it.priority}] ${it.name}: ${it.keywords.joinToString(", ")}"
                },
                sessionContext = phaseContext
            )

            if (decision.isFailure) {
                addLog(LogLevel.WARN, "DeepSeek 决策失败: ${decision.exceptionOrNull()?.message}")
                waitRandomCycleDelay()
                continue
            }

            val aiResponse = decision.getOrThrow()
            addLog(LogLevel.INFO, "AI决策: ${aiResponse.reasoning.take(100)}")

            // 4. 执行动作序列
            for (actionDto in aiResponse.actions) {
                if (!isActive()) break

                val action = convertToAction(actionDto)

                // 安全检查
                if (!safetyGuard.validateAction(action, _sessionContext.value)) {
                    addLog(LogLevel.WARN, "动作被安全模块拒绝: ${action.type} — ${actionDto.reason}")
                    continue
                }

                // 执行
                val result = actionExecutor.execute(action)

                // 记录
                val actionResult = result.toActionResult()
                actionHistory.add(actionResult)
                _lastAction.value = actionResult

                if (result is ExecutionResult.Success) {
                    addLog(LogLevel.INFO, "执行: ${action.type} ✓ ${action.reason}")
                    updateContextAfterAction(action)
                    detectSameActionLoop(action)

                    // 特殊动作后的额外处理
                    if (action.type == ActionType.LIKE) {
                        _sessionContext.value = _sessionContext.value.copy(
                            totalLikesToday = _sessionContext.value.totalLikesToday + 1
                        )
                    } else if (action.type == ActionType.TYPE_COMMENT) {
                        _sessionContext.value = _sessionContext.value.copy(
                            totalCommentsToday = _sessionContext.value.totalCommentsToday + 1
                        )
                    }
                } else {
                    val errMsg = (result as? ExecutionResult.Failed)?.error ?: "unknown"
                    addLog(LogLevel.WARN, "执行失败: ${action.type} ✗ $errMsg")
                }

                // 动作间随机延迟
                delay(randomActionDelay())
            }

            // 5. 检查会话是否应该结束
            if (shouldEndSession()) {
                executeCooldown()
            }

            // 6. 周期间的随机延迟
            waitRandomCycleDelay()
        }
    }

    // ============ 阶段执行 ============

    private suspend fun executePhaseLaunch() {
        addLog(LogLevel.INFO, "阶段: 启动小红书 App")

        // 通过启动 Intent 打开小红书
        actionExecutor.launchApp("com.xingin.xhs")

        // 等待 App 启动
        delay(3000)
        _sessionContext.value = _sessionContext.value.copy(
            phase = BrowsePhase.EXPLORING_FEED
        )
    }

    private suspend fun executeCooldown() {
        addLog(LogLevel.INFO, "阶段: 冷却休息")
        _sessionContext.value = _sessionContext.value.copy(
            phase = BrowsePhase.COOLDOWN
        )

        val cooldownMs = config.safety.cooldownMinutes * 60 * 1000L
        addLog(LogLevel.INFO, "冷却 ${config.safety.cooldownMinutes} 分钟...")

        // 逐步减少检查频率
        val interval = 30_000L
        var elapsed = 0L
        while (elapsed < cooldownMs && isActive()) {
            delay(interval)
            elapsed += interval
        }

        _sessionContext.value = createInitialContext()
        addLog(LogLevel.INFO, "冷却结束，重置上下文")
    }

    // ============ 辅助方法 ============

    private fun isActive(): Boolean {
        val state = _engineState.value
        return state == EngineState.RUNNING || state == EngineState.STARTING
    }

    private fun resolvePhase(screenState: ScreenState): BrowsePhase {
        return when (screenState.pageType) {
            PageType.MAIN_FEED -> BrowsePhase.EXPLORING_FEED
            PageType.POST_DETAIL -> BrowsePhase.READING_POST
            PageType.COMMENT_SECTION -> BrowsePhase.INTERACTING
            else -> _sessionContext.value.phase
        }
    }

    private fun convertToAction(dto: com.xhs.agent.model.AiActionDto): AgentAction {
        val type = try {
            ActionType.valueOf(dto.type.uppercase())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown action type: ${dto.type}, defaulting to WAIT")
            ActionType.WAIT
        }

        // 将 target_id 从字符串/数字统一转换
        val targetId = dto.target_id?.let {
            if (it == 0) null else it
        }

        return AgentAction(
            type = type,
            targetId = targetId,
            text = dto.text,
            durationMs = dto.duration_ms,
            reason = dto.reason
        )
    }

    private fun updateContextAfterAction(action: AgentAction) {
        val context = _sessionContext.value

        val updated = when (action.type) {
            ActionType.LIKE -> {
                val postId = action.targetId?.toString() ?: return
                context.copy(
                    alreadyLiked = context.alreadyLiked + postId
                )
            }
            ActionType.TYPE_COMMENT -> {
                val postId = action.targetId?.toString() ?: return
                context.copy(
                    alreadyCommented = context.alreadyCommented + postId
                )
            }
            ActionType.ENTER_POST -> {
                context.copy(phase = BrowsePhase.READING_POST)
            }
            ActionType.EXIT_POST -> {
                context.copy(phase = BrowsePhase.EXPLORING_FEED)
            }
            else -> context
        }
        _sessionContext.value = updated
    }

    private fun detectSameActionLoop(action: AgentAction) {
        if (action.type == lastActionType) {
            consecutiveSameAction++
        } else {
            consecutiveSameAction = 0
        }
        lastActionType = action.type

        if (consecutiveSameAction >= config.safety.sameActionThreshold) {
            addLog(LogLevel.WARN, "检测到动作循环: ${action.type} x${consecutiveSameAction}")
            // 强制切换阶段
            _sessionContext.value = _sessionContext.value.copy(
                phase = BrowsePhase.NAVIGATING
            )
            consecutiveSameAction = 0
        }
    }

    private fun handleSafetyBlock(check: SafetyCheck) {
        addLog(LogLevel.WARN, "安全模块阻止: ${check.reason}")
        when (check.action) {
            SafetyAction.WAIT -> {
                // 启动协程等待（此函数非 suspend）
                CoroutineScope(Dispatchers.Default).launch {
                    waitRandomCycleDelay()
                }
            }
            SafetyAction.PAUSE -> pause()
            SafetyAction.STOP -> stop()
            SafetyAction.COOLDOWN -> {
                job?.cancel()
                CoroutineScope(Dispatchers.Default).launch {
                    executeCooldown()
                }
            }
            SafetyAction.NONE -> { /* 无动作 */ }
        }
    }

    private fun shouldEndSession(): Boolean {
        val ctx = _sessionContext.value
        val elapsed = System.currentTimeMillis() - ctx.sessionStartTime
        val maxSessionMs = config.safety.maxSessionMinutes * 60 * 1000L

        return elapsed >= maxSessionMs ||
               ctx.totalLikesToday >= config.safety.maxDailyLikes ||
               ctx.totalCommentsToday >= config.safety.maxDailyComments
    }

    private fun randomActionDelay(): Long {
        val base = when (_sessionContext.value.phase) {
            BrowsePhase.READING_POST -> 5000L   // 阅读时延迟更长
            BrowsePhase.INTERACTING -> 3000L
            else -> 2000L
        }
        val variation = (base * config.behavior.scrollVariation).toLong()
        return base + Random.nextLong(-variation, variation)
    }

    private suspend fun waitRandomCycleDelay() {
        val delay = Random.nextLong(CYCLE_DELAY_MIN_MS, CYCLE_DELAY_MAX_MS)
        delay(delay)
    }

    private fun createInitialContext(): SessionContext {
        return SessionContext(
            alreadyLiked = emptySet(),
            alreadyCommented = emptySet(),
            seenPostTitles = emptyList(),
            totalLikesToday = 0,
            totalCommentsToday = 0,
            sessionStartTime = System.currentTimeMillis(),
            phase = BrowsePhase.LAUNCHING
        )
    }

    private fun addLog(level: LogLevel, message: String) {
        val log = EngineLog(level, message, System.currentTimeMillis())
        _logs.value = _logs.value + log
        Log.d(TAG, "[$level] $message")
    }
}

// ============ 状态定义 ============

enum class EngineState {
    IDLE, STARTING, RUNNING, PAUSED, STOPPED, ERROR
}

data class EngineLog(
    val level: LogLevel,
    val message: String,
    val timestamp: Long
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class SafetyCheck(
    val isAllowed: Boolean,
    val action: SafetyAction = SafetyAction.NONE,
    val reason: String = ""
)

enum class SafetyAction {
    NONE, WAIT, PAUSE, STOP, COOLDOWN
}
