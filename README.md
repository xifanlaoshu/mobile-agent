# XhsAgent — 小红书 AI 手机代理 🤖📱

> 在荣耀 V20 手机上完全运行的 AI 代理，自动浏览小红书、识别兴趣内容并进行点赞/评论。
> 后端接入 **DeepSeek V4 Flash** 作为决策引擎。

## 项目概述

```
┌──────────────────────────────────────────┐
│  XhsAgent 流程图                          │
│                                          │
│  截图 → UI树 → 状态文本 → DeepSeek       │
│   ↑                        ↓            │
│   ← ← ← 执行动作 ← 解析 JSON ←           │
└──────────────────────────────────────────┘
```

### 核心能力

| 能力 | 说明 |
|------|------|
| 📱 纯手机端 | 零 PC 依赖，无需 ADB，无需 root |
| 🧠 AI 驱动 | DeepSeek V4 Flash 分析屏幕状态并决策 |
| 👀 内容识别 | 通过 UI 树 + OCR 理解屏幕内容 |
| ❤️ 点赞 | 对匹配兴趣的帖子自动点赞 |
| 💬 评论 | 对高质量相关内容智能评论 |
| 🛡️ 防检测 | 随机延迟、频率控制、行为模拟 |

## 快速开始

### 前置条件

1. **荣耀 V20**（或任意 Android 9+ 手机）
2. **DeepSeek API Key**（从 platform.deepseek.com 获取）
3. **Android Studio**（用于构建 APK）

### 构建

```bash
# 1. 克隆项目
git clone <repo-url> xiaohongshu-agent
cd xiaohongshu-agent

# 2. 配置 API Key
echo "DEEPSEEK_API_KEY=sk-your-key-here" >> local.properties

# 3. 构建
./gradlew assembleDebug

# 4. 安装到手机
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 配置步骤（手机上）

1. **安装 App** → 打开"小红书助手"
2. **开启无障碍服务** → 设置 → 辅助功能 → 小红书助手
3. **授予截图权限** → App 会请求 MediaProjection 权限
4. **配置兴趣** → 在 App 内设置感兴趣的关键词/领域
5. **输入 API Key** → 设置 DeepSeek API 密钥
6. **启动** → 点击"启动"按钮

### 安全建议

- ⚠️ **使用小号操作**，以免主账号被限流
- 🎯 **合理设置每日上限**（默认点赞 200/日，评论 30/日）
- ⏱ **单次运行不超过 45 分钟**
- 🔄 **不要在高峰期大批量操作**

## 项目架构

```
xiaohongshu-agent/
├── DESIGN.md                 ← 完整设计文档（核心阅读）
├── README.md                 # 本文件
│
├── app/                      # Android 应用
│   ├── src/main/java/com/xhs/agent/
│   │   ├── MainActivity.kt           # 控制面板 UI
│   │   ├── XhsAgentApp.kt            # 应用入口
│   │   │
│   │   ├── service/
│   │   │   ├── AgentAccessibilityService.kt  # 无障碍服务（核心）
│   │   │   └── ForegroundService.kt        # 前台保活
│   │   │
│   │   ├── collector/
│   │   │   ├── StateCollector.kt      # 状态采集协调
│   │   │   └── OcrProcessor.kt        # 文字识别
│   │   │
│   │   ├── engine/
│   │   │   ├── DecisionEngine.kt      # 主循环引擎
│   │   │   ├── ActionExecutor.kt      # 动作执行
│   │   │   └── SafetyGuard.kt         # 安全守卫
│   │   │
│   │   ├── ai/
│   │   │   ├── DeepSeekClient.kt      # API 客户端
│   │   │   ├── PromptBuilder.kt       # Prompt 构建
│   │   │   └── ResponseParser.kt      # 响应解析
│   │   │
│   │   ├── model/                     # 数据模型
│   │   │   ├── ScreenState.kt
│   │   │   ├── AgentAction.kt
│   │   │   └── TaskConfig.kt
│   │   │
│   │   ├── storage/                   # 本地存储
│   │   └── util/                      # 工具类
│   │
│   ├── src/main/res/                  # 资源文件
│   └── build.gradle.kts
│
├── config/
│   ├── agent-default.yaml             # 默认配置
│   └── prompts/
│       ├── system-prompt.md           # 系统提示词
│       └── examples/                  # 决策示例
│
└── docs/
    └── api-reference.md               # API 参考
```

## 技术栈

| 组件 | 技术 | 选型理由 |
|------|------|---------|
| 语言 | Kotlin | Android 首选，协程支持，空安全 |
| UI | Jetpack Compose | 声明式 UI，控制面板 |
| 自动化 | AccessibilityService | 无需 root，系统级交互 |
| AI | DeepSeek V4 Flash API | 低成本，中文优秀 |
| OCR | Tesseract (tess-two) | 离线可用，不需 Google 服务 |
| 网络 | OkHttp + Retrofit | 稳定成熟 |
| 序列化 | kotlinx.serialization | Kotlin 原生 |

## 工作原理

1. **采集**: 通过 Android Accessibility Service 获取当前屏幕的 UI 层级结构
2. **理解**: 将 UI 树压缩为文本描述 + OCR 提取图片文字
3. **决策**: 发送给 DeepSeek，AI 分析内容并决定下一步动作
4. **执行**: 通过 Accessibility Service API 执行点击、滑动、输入等操作
5. **循环**: 随机延迟后回到步骤 1

## 开发计划

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | 项目骨架 + Accessibility 服务 + DeepSeek 调用 | 📝 待开发 |
| 2 | UI 树压缩 + 页面理解 + 核心 Prompt | 📝 待开发 |
| 3 | 点赞 + 评论 + 兴趣匹配 | 📝 待开发 |
| 4 | 防检测 + 异常恢复 + 用户控制面板 | 📝 待开发 |
| 5 | Token 优化 + 多任务 + 数据分析 | 📝 待开发 |

## 许可证

仅供个人学习和研究使用。使用自动化工具操作社交媒体平台可能违反该平台服务条款，请自行评估风险。

---

> **文档**: 完整设计请阅读 [DESIGN.md](DESIGN.md)
