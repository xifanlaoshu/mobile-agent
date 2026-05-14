package com.xhs.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xhs.agent.collector.StateCollector
import com.xhs.agent.engine.*
import com.xhs.agent.collector.OcrProcessor
import com.xhs.agent.service.AgentAccessibilityService
import com.xhs.agent.model.TaskConfig
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var decisionEngine: DecisionEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                MainScreen(
                    onStart = { startEngine() },
                    onStop = { stopEngine() },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onOpenApiConfig = { openApiConfig() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台重新检查无障碍状态
    }

    override fun onDestroy() {
        scope.cancel()
        decisionEngine?.stop()
        super.onDestroy()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return services?.contains(packageName + "/.service.AgentAccessibilityService") == true
    }

    private fun startEngine() {
        if (decisionEngine?.engineState?.value == EngineState.RUNNING) return

        val accessibilityService = AgentAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "无障碍服务未连接，请先开启", Toast.LENGTH_SHORT).show()
            return
        }

        // 读取 API Key
        val apiKey = (application as XhsAgentApp).apiKey
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "请先设置 DeepSeek API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val ocr = OcrProcessor(this)
        val stateCollector = StateCollector(accessibilityService, ocr)
        val actionExecutor = ActionExecutor(this, accessibilityService)
        val safetyGuard = SafetyGuard(TaskConfig())
        val deepSeekClient = com.xhs.agent.ai.DeepSeekClient(apiKey)

        decisionEngine = DecisionEngine(
            stateCollector = stateCollector,
            deepSeekClient = deepSeekClient,
            actionExecutor = actionExecutor,
            safetyGuard = safetyGuard,
            config = TaskConfig()
        ).also {
            // 启动协程收集状态更新
            scope.launch {
                it.engineState.collect { state ->
                    // state 变化会自动触发 Compose 重组
                }
            }
            scope.launch {
                it.logs.collect { /* 日志显示由 Compose State 驱动 */ }
            }
            it.start()
        }
    }

    private fun stopEngine() {
        decisionEngine?.stop()
        decisionEngine = null
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApiConfig() {
        Toast.makeText(this, "请在 local.properties 中设置 DEEPSEEK_API_KEY", Toast.LENGTH_LONG).show()
    }

    // ============ Compose UI ============

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(
        onStart: () -> Unit,
        onStop: () -> Unit,
        onOpenAccessibility: () -> Unit,
        onOpenApiConfig: () -> Unit
    ) {
        // 实时状态
        var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
        val engineState by remember { derivedStateOf { decisionEngine?.engineState?.value ?: EngineState.IDLE } }
        val engineLogs by remember { derivedStateOf { decisionEngine?.logs?.value ?: emptyList() } }
        val sessionCtx by remember { derivedStateOf { decisionEngine?.sessionContext?.value } }

        // 每 2 秒刷新无障碍状态
        LaunchedEffect(Unit) {
            while (isActive) {
                accessibilityEnabled = isAccessibilityServiceEnabled()
                delay(2000)
            }
        }

        val isRunning = engineState == EngineState.RUNNING || engineState == EngineState.STARTING

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("小红书助手") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 状态卡
                statusCard(accessibilityEnabled, engineState.name, sessionCtx)

                // 控制按钮
                controlButtons(
                    isRunning = isRunning,
                    accessibilityEnabled = accessibilityEnabled,
                    onStart = onStart,
                    onStop = onStop
                )

                // 功能入口
                settingsButtons(onOpenAccessibility, onOpenApiConfig)

                // 日志
                logSection(engineLogs, modifier = Modifier.weight(1f, fill = true))
            }
        }
    }

    @Composable
    private fun statusCard(
        accessibilityEnabled: Boolean,
        stateName: String,
        sessionCtx: com.xhs.agent.model.SessionContext?
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("引擎状态", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                StatusRow("运行状态", stateName,
                    if (stateName == "RUNNING") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                StatusRow("无障碍服务",
                    if (accessibilityEnabled) "已开启 ✓" else "未开启 ✗",
                    if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
                sessionCtx?.let {
                    Spacer(Modifier.height(4.dp))
                    StatusRow("今日操作", "点赞 ${it.totalLikesToday} | 评论 ${it.totalCommentsToday}",
                        MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String, valueColor: Color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp)
            Text(value, fontSize = 14.sp, color = valueColor)
        }
    }

    @Composable
    private fun controlButtons(
        isRunning: Boolean,
        accessibilityEnabled: Boolean,
        onStart: () -> Unit,
        onStop: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = accessibilityEnabled && !isRunning
            ) {
                Text(if (isRunning) "运行中..." else "启动")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = isRunning
            ) {
                Text("停止")
            }
        }
    }

    @Composable
    private fun settingsButtons(
        onOpenAccessibility: () -> Unit,
        onOpenApiConfig: () -> Unit
    ) {
        Button(
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("开启无障碍服务")
        }
        OutlinedButton(
            onClick = onOpenApiConfig,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("设置 API Key")
        }
    }

    @Composable
    private fun logSection(logs: List<EngineLog>, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("运行日志", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                if (logs.isEmpty()) {
                    Text(
                        "等待启动...",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        // 自动滚动到底部
                        LaunchedEffect(logs.size) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                        logs.takeLast(50).forEach { log ->
                            val prefix = when (log.level) {
                                LogLevel.DEBUG -> "D"
                                LogLevel.INFO -> "I"
                                LogLevel.WARN -> "W"
                                LogLevel.ERROR -> "E"
                            }
                            Text(
                                text = "[$prefix] ${log.message}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when (log.level) {
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    LogLevel.WARN -> Color(0xFFFFA000)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
