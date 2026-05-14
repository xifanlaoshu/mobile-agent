package com.xhs.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xhs.agent.model.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 核心无障碍服务
 *
 * 职责：
 * 1. 监听 UI 事件（页面变化等）
 * 2. 提供获取当前 UI 树的能力
 * 3. 执行点击、滑动、输入等操作
 * 4. 在需要时返回 UI 树快照
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        var instance: AgentAccessibilityService? = null
            private set
    }

    private var currentRoot: AccessibilityNodeInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var lastEventType: Int = -1
    private var lastEventTime: Long = 0L

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility Service connected")
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 记录最后事件用于页面稳定检测
        lastEventType = event.eventType
        lastEventTime = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 页面变化时更新缓存的 root node
                updateRootNode()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
        isRunning.set(false)
    }

    override fun onDestroy() {
        instance = null
        currentRoot?.recycle()
        coroutineScope.cancel()
        super.onDestroy()
    }

    // ============ 公开 API ============

    /**
     * 获取当前 UI 树（在子线程读取）
     */
    fun collectUiTree(): UiNode? {
        return try {
            val root = currentRoot ?: rootInActiveWindow ?: return null

            synchronized(this) {
                val node = buildUiNode(root, depth = 0, idCounter = 1)
                root.recycle()
                node
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting UI tree", e)
            null
        }
    }

    /**
     * 点击指定 ID 的 UI 元素
     */
    suspend fun clickById(targetId: Int): Boolean {
        val root = currentRoot ?: rootInActiveWindow ?: return false
        return try {
            val target = findNodeById(root, targetId)
            target?.let { node ->
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    // 如果自身不可点击，尝试找可点击的父节点
                    var parent = node.parent
                    while (parent != null && !parent.isClickable) {
                        parent = parent.parent
                    }
                    parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                }
            } ?: false
        } finally {
            root.recycle()
        }
    }

    /**
     * 按屏幕坐标点击（作为 fallback）
     */
    suspend fun clickByCoordinate(x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            var result = false
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                }
            }, null)

            // 等待手势完成
            delay(200)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Gesture click failed", e)
            false
        }
    }

    /**
     * 滑动屏幕
     */
    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 400
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            var result = false
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                }
            }, null)

            delay(durationMs + 100)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Swipe gesture failed", e)
            false
        }
    }

    /**
     * 向下滚动
     */
    suspend fun scrollDown(): Boolean {
        // 尝试 Accessibility scroll action
        val root = currentRoot ?: rootInActiveWindow ?: return false
        return try {
            val scrollable = findScrollableNode(root)
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 
                ?: fallbackSwipeDown()
        } finally {
            root.recycle()
        }
    }

    private suspend fun fallbackSwipeDown(): Boolean {
        val display = resources.displayMetrics
        val startX = display.widthPixels / 2
        val startY = display.heightPixels * 3 / 4
        val endY = display.heightPixels / 4
        return swipe(startX, startY, startX, endY)
    }

    /**
     * 执行返回操作
     */
    suspend fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 回到桌面
     */
    suspend fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 输入文本
     */
    suspend fun typeText(text: String): Boolean {
        val root = currentRoot ?: rootInActiveWindow ?: return false
        return try {
            val editText = findEditableNode(root)
            editText?.let { node ->
                // 先清空已有内容
                node.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
                node.performAction(AccessibilityNodeInfo.ACTION_CUT)

                // 使用剪贴板方式输入确保中文支持
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("text", text)
                clipboard.setPrimaryClip(clip)
                Thread.sleep(100)

                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(android.R.id.paste)
                true
            } ?: false
        } finally {
            root.recycle()
        }
    }

    /**
     * 等待页面稳定（不再有内容变化事件）
     */
    suspend fun waitForPageStable(timeoutMs: Long = 5000): Boolean {
        val stableStart = System.currentTimeMillis()
        var lastChange = lastEventTime

        while (System.currentTimeMillis() - stableStart < timeoutMs) {
            if (lastEventTime > lastChange) {
                lastChange = lastEventTime
                delay(300)  // 有变化，再等 300ms
            } else {
                return true  // 稳定了
            }
        }
        return false  // 超时
    }

    // ============ 内部辅助 ============

    private fun updateRootNode() {
        currentRoot?.recycle()
        currentRoot = rootInActiveWindow
    }

    private var nextId = 1

    private fun buildUiNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        idCounter: Int
    ): UiNode {
        val currentId = nextId++

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val children = mutableListOf<UiNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isVisibleToUser) {
                children.add(buildUiNode(child, depth + 1, nextId))
            }
            child.recycle()
        }

        return UiNode(
            id = currentId,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString() ?: "Unknown",
            bounds = Rect(rect.left, rect.top, rect.right, rect.bottom),
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isChecked = if (node.isCheckable) node.isChecked else null,
            isVisible = node.isVisibleToUser,
            children = children,
            depth = depth
        )
    }

    private fun findNodeById(root: AccessibilityNodeInfo, targetId: Int): AccessibilityNodeInfo? {
        // 实际实现需要遍历节点树并匹配 ID
        // 这里简化——通过 BFS 遍历
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            // 使用 ID 映射表（在采集时构建）
            // 简化方案：遍历并用 bounds 近似匹配
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
            current.recycle()
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isScrollable) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
            current.recycle()
        }
        return null
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable || current.className?.contains("EditText") == true) {
                return current
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
            current.recycle()
        }
        return null
    }

    private fun CoroutineScope.suspendDelay(timeMs: Long) {
        // Since we're not actually in a suspend context here, we use Handler postDelay
        // This is a simplification — real impl should use callback
    }
}
