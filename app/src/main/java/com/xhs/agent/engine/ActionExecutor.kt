package com.xhs.agent.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.xhs.agent.model.*
import com.xhs.agent.service.AgentAccessibilityService

/**
 * 动作执行器
 *
 * 将 AgentAction 转换为具体的 Android 操作调用
 */
class ActionExecutor(
    private val context: Context,
    private val accessibilityService: AgentAccessibilityService
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    /**
     * 执行一个动作
     */
    suspend fun execute(action: AgentAction): ExecutionResult {
        return try {
            val result = when (action.type) {
                ActionType.SCROLL_DOWN -> executeScrollDown(action)
                ActionType.SCROLL_UP -> executeScrollUp(action)
                ActionType.TAP -> executeTap(action)
                ActionType.TAP_COORD -> executeTapCoord(action)
                ActionType.BACK -> executeBack(action)
                ActionType.HOME -> executeHome(action)
                ActionType.WAIT -> executeWait(action)
                ActionType.LIKE -> executeLike(action)
                ActionType.ENTER_COMMENT -> executeEnterComment(action)
                ActionType.TYPE_COMMENT -> executeTypeComment(action)
                ActionType.ENTER_POST -> executeEnterPost(action)
                ActionType.EXIT_POST -> executeExitPost(action)
                ActionType.NAVIGATE_TAB -> executeNavigateTab(action)
            }

            Log.d(TAG, "Action ${action.type} ${if (result is ExecutionResult.Success) "succeeded" else "failed"}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing ${action.type}", e)
            ExecutionResult.Failed(action, e.message ?: "Unknown error")
        }
    }

    /**
     * 启动 App
     */
    suspend fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.d(TAG, "Launched $packageName")
                true
            } else {
                Log.e(TAG, "Package not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            false
        }
    }

    // ============ 具体动作实现 ============

    private suspend fun executeScrollDown(action: AgentAction): ExecutionResult {
        val success = accessibilityService.scrollDown()
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Scroll down failed")
    }

    private suspend fun executeScrollUp(action: AgentAction): ExecutionResult {
        // 向上滚动：用 swipe 反向
        val display = context.resources.displayMetrics
        val startX = display.widthPixels / 2
        val startY = display.heightPixels / 4
        val endY = display.heightPixels * 3 / 4
        val success = accessibilityService.swipe(startX, startY, startX, endY, 400)
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Scroll up failed")
    }

    private suspend fun executeTap(action: AgentAction): ExecutionResult {
        if (action.targetId == null) {
            return ExecutionResult.Failed(action, "No target ID")
        }
        val success = accessibilityService.clickById(action.targetId)
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Tap failed: target ${action.targetId}")
    }

    private suspend fun executeTapCoord(action: AgentAction): ExecutionResult {
        val coord = action.coordinate
            ?: return ExecutionResult.Failed(action, "No coordinate")
        val success = accessibilityService.clickByCoordinate(coord.x, coord.y)
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Coord tap failed")
    }

    private suspend fun executeBack(action: AgentAction): ExecutionResult {
        val success = accessibilityService.goBack()
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Back failed")
    }

    private suspend fun executeHome(action: AgentAction): ExecutionResult {
        val success = accessibilityService.goHome()
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Home failed")
    }

    private suspend fun executeWait(action: AgentAction): ExecutionResult {
        val ms = action.durationMs ?: 2000L
        kotlinx.coroutines.delay(ms.coerceIn(500L, 30_000L))
        return ExecutionResult.Success(action)
    }

    private suspend fun executeLike(action: AgentAction): ExecutionResult {
        // 点赞通常是点击一个"♥"按钮
        // 先尝试点击目标 ID（可能是卡片或点赞按钮）
        if (action.targetId != null) {
            val success = accessibilityService.clickById(action.targetId)
            if (success) {
                kotlinx.coroutines.delay(500)
                return ExecutionResult.Success(action)
            }
        }

        // fallback: 在当前页面寻找"赞"或"❤️"文本的按钮
        Log.w(TAG, "Like via target ID failed, trying fallback")
        return ExecutionResult.Failed(action, "Like action needs UI element targeting")
    }

    private suspend fun executeEnterComment(action: AgentAction): ExecutionResult {
        // 进入评论区域——通常是点击评论按钮
        if (action.targetId != null) {
            val success = accessibilityService.clickById(action.targetId)
            if (success) {
                kotlinx.coroutines.delay(2000)  // 等待评论加载
                return ExecutionResult.Success(action)
            }
        }
        return ExecutionResult.Failed(action, "Enter comment failed")
    }

    private suspend fun executeTypeComment(action: AgentAction): ExecutionResult {
        val text = action.text ?: return ExecutionResult.Failed(action, "No comment text")

        // 1. 输入评论文字
        val typed = accessibilityService.typeText(text)
        if (!typed) return ExecutionResult.Failed(action, "Type text failed")

        kotlinx.coroutines.delay(1000)

        // 2. 点击发送（寻找"发送"按钮）
        val sent = accessibilityService.clickByCoordinate(
            context.resources.displayMetrics.widthPixels - 100,
            context.resources.displayMetrics.heightPixels - 200
        )
        // 简化：实际需要找到发送按钮

        kotlinx.coroutines.delay(1000)
        return ExecutionResult.Success(action)
    }

    private suspend fun executeEnterPost(action: AgentAction): ExecutionResult {
        // 点击帖子卡片进入详情
        if (action.targetId != null) {
            val success = accessibilityService.clickById(action.targetId)
            if (success) {
                kotlinx.coroutines.delay(2000)  // 等待帖子加载
                return ExecutionResult.Success(action)
            }
        }
        return ExecutionResult.Failed(action, "Enter post failed")
    }

    private suspend fun executeExitPost(action: AgentAction): ExecutionResult {
        val success = accessibilityService.goBack()
        if (success) kotlinx.coroutines.delay(1500)
        return if (success) ExecutionResult.Success(action)
        else ExecutionResult.Failed(action, "Exit post failed")
    }

    private suspend fun executeNavigateTab(action: AgentAction): ExecutionResult {
        // 底部导航切换（首页/发现/消息/我）
        // 需要通过文本寻找对应的 Tab 元素
        Log.d(TAG, "Tab navigation: ${action.text ?: action.targetId}")
        return ExecutionResult.Failed(action, "Tab navigation not yet implemented")
    }
}
