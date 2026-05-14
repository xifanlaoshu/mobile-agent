# 小红书手机端 AI 代理 — 设计文档

> **项目代号**: XhsAgent  
> **目标设备**: 荣耀 V20 (Android 10+, EMUI)  
> **AI 引擎**: DeepSeek V4 Flash (API)  
> **版本**: v0.1-draft

---

## 📋 目录

1. [项目概述](#1-项目概述)
2. [系统架构](#2-系统架构)
3. [核心数据流](#3-核心数据流)
4. [组件详细设计](#4-组件详细设计)
5. [AI 交互设计（Prompt 工程）](#5-ai-交互设计prompt-工程)
6. [状态机与决策逻辑](#6-状态机与决策逻辑)
7. [安全性设计](#7-安全性设计)
8. [配置系统](#8-配置系统)
9. [实现路线图](#9-实现路线图)
10. [荣耀 V20 适配说明](#10-荣耀-v20-适配说明)
11. [风险与应对](#11-风险与应对)

---

## 1. 项目概述

### 1.1 目标

开发一个**完全运行在荣耀 V20 手机端**的 Android 应用，能够：

1. 自动打开小红书 App
2. 浏览 / 刷新内容流（首页推荐、发现页）
3. 识别用户感兴趣的帖子
4. 对感兴趣的内容执行**点赞**和**评论**操作
5. 持续运行，模拟真人浏览行为

### 1.2 核心原则

| 原则 | 说明 |
|------|------|
| **纯手机端** | 零 PC 依赖，不依赖 ADB 或电脑连接 |
| **AI 驱动** | DeepSeek V4 Flash 作为决策引擎，控制所有操作 |
| **可配置兴趣** | 用户可自定义"感兴趣"的标准（关键词、话题、作者等） |
| **安全优先** | 内置频率限制、操作延迟、异常检测，避免被平台封禁 |
| **可观测** | 所有决策和操作都有日志记录，用户可以实时查看状态 |

### 1.3 技术栈

| 层 | 技术选型 | 说明 |
|----|---------|------|
| **语言** | Kotlin | Android 首选语言，空安全，协程支持 |
| **UI 框架** | Jetpack Compose | 声明式 UI，易于构建控制面板 |
| **网络** | OkHttp + Retrofit | DeepSeek API 调用 |
| **JSON** | Kotlinx Serialization | 数据模型序列化 |
| **OCR** | Tesseract (tess-two) / ML Kit | 屏幕文字识别 |
| **截图** | MediaProjection API | 屏幕捕获 |
| **自动化** | AccessibilityService API | UI 树获取 + 操作执行 |
| **协程** | Kotlin Coroutines | 异步任务编排 |
| **存储** | DataStore / Room | 配置 & 历史记录 |

---

## 2. 系统架构

### 2.1 总体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Honor V20 (Android)                          │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    XhsAgent App                                 │  │
│  │                                                                  │  │
│  │  ┌──────────────┐   ┌──────────────────┐   ┌──────────────┐   │  │
│  │  │ State        │   │ Decision Engine   │   │ Action       │   │  │
│  │  │ Collector    │──▶│ (Main Loop)       │──▶│ Executor     │   │  │
│  │  │              │   │                    │   │              │   │  │
│  │  │ • UI Tree    │   │ • State Machine   │   │ • Tap        │   │  │
│  │  │ • Screenshot │   │ • DeepSeek Call   │   │ • Swipe      │   │  │
│  │  │ • OCR Text  │   │ • Action Planning │   │ • Type       │   │  │
│  │  └──────┬───────┘   └────────┬───────────┘   └──────┬───────┘   │  │
│  │         │                    │                       │           │  │
│  │         │    ┌───────────────▼────────────────┐      │           │  │
│  │         │    │     DeepSeek API Bridge        │      │           │  │
│  │         │    │  • PromptBuilder               │      │           │  │
│  │         │    │  • ResponseParser              │      │           │  │
│  │         │    │  • Retry / Backoff             │      │           │  │
│  │         │    └───────────────┬────────────────┘      │           │  │
│  │         │                    │                       │           │  │
│  │  ┌──────▼────────────────────▼───────────────────────▼───────┐  │  │
│  │  │                Android System Services                     │  │  │
│  │  │  ┌──────────────────┐  ┌──────────────────────────────┐  │  │  │
│  │  │  │ Accessibility    │  │ MediaProjection              │  │  │  │
│  │  │  │ Service          │  │ (Screen Capture)             │  │  │  │
│  │  │  └──────────────────┘  └──────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                    │ HTTPS (DeepSeek API)             │
└────────────────────────────────────┼──────────────────────────────────┘
                                     │
                      ┌──────────────▼─────────────────┐
                      │     DeepSeek V4 Flash API        │
                      │  (chat/completions, text-only)    │
                      │  无多模态 / 无 vision 能力         │
                      └────────────────────────────────────┘
```

### 2.2 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | **用 UI 树替代截图给 AI** | DeepSeek V4 Flash 不支持图像输入；AccessibilityService 可以提供结构化的 UI 文本表示 |
| D2 | **OCR 作为辅助** | 帖子中的图片文字（如标题图、文案）需要提取文本后才能提供给 AI分析 |
| D3 | **结构化的动作输出** | DeepSeek 返回 JSON 格式的动作指令，而不是自由文本，便于解析和执行 |
| D4 | **状态机驱动** | 避免 AI 每次独立决策导致的循环震荡，上层状态机保证宏观行为连贯 |
| D5 | **异步流水线** | 截图→OCR→AI 决策→执行 使用协程流水线，不阻塞主线程 |

---

## 3. 核心数据流

### 3.1 一次完整的决策周期

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  1. 采集状态  │───▶│ 2. 构建 Prompt│───▶│ 3. 调用       │───▶│ 4. 解析响应   │
│              │    │              │    │    DeepSeek   │    │              │
│ • UI Tree    │    │ • 系统指令   │    │ • HTTP POST  │    │ • 提取动作   │
│ • OCR Text   │    │ • 屏幕状态   │    │ • 流式响应    │    │ • 校验格式   │
│ • 上下文历史 │    │ • 可用动作   │    │ • 重试逻辑    │    │ • 提取评论   │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
                                                                    │
                        ┌──────────────┐                            │
                        │  6. 等待/冷却  │◀─── ┌──────────────┐      │
                        │              │     │  5. 执行动作  │◀─────┘
                        │ • 随机延迟   │     │              │
                        │ • 防检测     │     │ • Accessibility│
                        │              │     │   服务 API    │
                        └──────────────┘     • Gesture 模拟  │
                                                    └──────────────┘
```

### 3.2 状态表示格式

每次决策周期中，屏幕状态被序列化为结构化文本，格式如下：

```
=== 屏幕状态 ===
当前页面: 发现页 (发现)
页面结构:
  [Header] 搜索框
  [TabBar] 推荐 | 穿搭 | 美食 | 家居 | 旅行 | ...
  [Feed_Card#1] 标题: "北京必去的10家胡同咖啡馆"
               作者: 北京探店指南
               点赞: 1.2万  收藏: 5432
               [❤️ 未点赞] [💬 评论]
  [Feed_Card#2] 标题: "冬季护肤必备清单"
               作者: 美妆达人小A
               点赞: 8567  收藏: 2100
               [❤️ 未点赞] [💬 评论]
  ...
  [Bottom_Nav] 首页 | 发现 | 发布+ | 消息 | 我

=== 操作历史 ===
上一次动作: scroll_down (15秒前) → 加载了新内容
再上一次动作: tap_on_card#3 → 进入帖子详情 → 已退出

=== 时间信息 ===
当前时间: 2025-01-15 14:35:22
运行时长: 27分钟
今日已操作: 点赞 12次, 评论 3次
```

---

## 4. 组件详细设计

### 4.1 State Collector（状态采集器）

**类**: `StateCollector.kt`

负责在每次决策周期开始时，全面采集当前手机屏幕状态。

```
StateCollector
├── collect(): ScreenState
│   ├── collectUiTree()    → UiNode 树（来自 AccessibilityService）
│   ├── captureScreenshot() → Bitmap
│   ├── extractText()     → OCR 结果
│   └── buildState()      → ScreenState 对象
```

**UiTreeCollector** — 通过 AccessibilityService 获取 View Hierarchy：

- 获取当前窗口的 root node
- 递归遍历，提取每个节点：`text`, `contentDescription`, `className`, `boundsInScreen`, `clickable`, `scrollable`, `checked` 等
- 过滤掉不可见节点（`isVisibleToUser == false`）
- 进行**语义摘要**：将原始 View 树压缩为有意义的 UI 块
  - 例如多个嵌套的 LinearLayout + TextView 合并为一个 "Feed_Card#3"
  - 底部导航栏识别为固定组件，和内容区域分离

**OcrCollector** — 截图文字识别：

- 使用 **Tesseract (tess-two)** 或 **ML Kit Text Recognition**
- 只对**当前可见区域**进行 OCR
- 输出：`List<TextBlock>`，包含文本内容和屏幕坐标
- OCR 结果与 UI Tree 文本进行**去重合并**（UI Tree 已有 text 的节点跳过 OCR）

> **荣耀 V20 优化**：Kirin 980 的 NPU 可加速部分推理任务。ML Kit 自动利用系统级加速。

### 4.2 Decision Engine（决策引擎）

**类**: `DecisionEngine.kt`

核心主循环，运行在独立协程中。

```kotlin
class DecisionEngine(
    private val stateCollector: StateCollector,
    private val deepSeekClient: DeepSeekClient,
    private val actionExecutor: ActionExecutor,
    private val safetyGuard: SafetyGuard
) {
    suspend fun runCycle(): CycleResult {
        // 1. 采集状态
        val state = stateCollector.collect()
        
        // 2. 安全检查：是否达到操作上限？
        safetyGuard.checkRateLimit()
        
        // 3. 构建 Prompt 并调用 DeepSeek
        val response = deepSeekClient.decide(state)
        
        // 4. 解析动作
        val actions = responseParser.parse(response)
        
        // 5. 安全性验证（动作合理性检查）
        safetyGuard.validateActions(actions)
        
        // 6. 执行动作序列
        for (action in actions) {
            actionExecutor.execute(action)
            delay(randomDelay())  // 随机延迟，模拟人类
        }
        
        // 7. 等待页面稳定
        waitForPageStable()
    }
}
```

**状态管理**：

```
决策引擎内部状态：
├── currentPhase: BrowsePhase     // 当前所处阶段
│   ├── LAUNCHING                 // 启动 App
│   ├── EXPLORING_FEED           // 浏览信息流
│   ├── READING_POST             // 阅读帖子详情
│   ├── INTERACTING              // 执行点赞/评论
│   ├── NAVIGATING               // 页面切换
│   └── COOLDOWN                 // 休息/冷却
│
├── context: SessionContext
│   ├── alreadyLiked: Set<String>       // 已点赞帖子 ID
│   ├── alreadyCommented: Set<String>   // 已评论帖子 ID
│   ├── seenPosts: List<SeenPost>       // 本轮已浏览帖子
│   ├── totalActions: Int               // 今日操作总数
│   └── sessionStart: Long
│
└── config: TaskConfig                  // 用户配置
```

### 4.3 Action Executor（动作执行器）

**类**: `ActionExecutor.kt`

将 DeepSeek 返回的抽象动作转换为具体的 Android Accessibility 操作。

**支持的动作类型**：

| 动作类型 | 参数 | 实现方式 |
|---------|------|---------|
| `scroll_down` | 无 | `performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)` 或模拟滑动手势 |
| `tap` | `target_id` (UI 树中的元素ID) | 找到节点 → `performAction(ACTION_CLICK)` |
| `tap_coord` | `(x, y)` | `GestureDescription` 模拟点击 |
| `swipe` | `(x1,y1)→(x2,y2), duration` | `GestureDescription` 模拟滑动 |
| `type_text` | `text` | 找到输入框 → 逐个字符 `ACTION_SET_TEXT` |
| `back` | 无 | 模拟返回键 |
| `home` | 无 | 回到桌面 |
| `wait` | `duration_ms` | 延迟等待 |
| `like` | `target_id` | 找到点赞按钮 → tap |
| `comment` | `target_id, text` | 进入评论 → 输入 → 发送 |
| `enter_post` | `card_id` | 点击卡片进入详情 |
| `exit_post` | 无 | 返回信息流 |

### 4.4 DeepSeek API Bridge

**类**: `DeepSeekClient.kt`, `PromptBuilder.kt`, `ResponseParser.kt`

**DeepSeekClient**：

```kotlin
class DeepSeekClient(private val apiKey: String) {
    suspend fun decide(screenState: ScreenState, context: SessionContext): ApiResponse {
        val prompt = PromptBuilder.build(screenState, context)
        
        val request = ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = listOf(
                Message(role = "system", content = SYSTEM_PROMPT),
                Message(role = "user", content = prompt)
            ),
            temperature = 0.3,        // 低温度，更确定性
            max_tokens = 800,
            response_format = ResponseFormat(type = "json_object")  // 强制 JSON
        )
        
        return httpClient.post(request)
    }
}
```

**ResponseParser** — 解析 DeepSeek 返回的 JSON 为 `AgentAction`：

```json
{
  "actions": [
    {
      "type": "scroll_down",
      "reason": "继续浏览更多内容"
    }
  ],
  "observation": "当前信息流有3篇关于美食的帖子，但用户兴趣配置中优先级更高的是旅行类内容，需要往下滚动寻找",
  "interest_match": false
}
```

解析后的动作会进行完整性校验：
- `type` 是否是已知动作类型
- 必要参数是否齐全
- 坐标是否在屏幕范围内
- `reason` 是否合理（辅助调试和日志）

### 4.5 Safety Guard（安全守卫）

**类**: `SafetyGuard.kt`

核心职责：防止被小红书平台检测和封禁。

**防护策略**：

```
每轮决策安全检查清单：
┌─────────────────────────────────────────────┐
│ ✅ 操作频率限制                               │
│    • 点赞间隔：≥ 8-15 秒（随机）               │
│    • 评论间隔：≥ 30-60 秒（随机）               │
│    • 每次浏览：≥ 3-8 秒（模拟阅读时间）          │
│    • 每小时点赞上限：≤ 30 次                    │
│    • 每小时评论上限：≤ 8 次                     │
│                                              │
│ ✅ 行为随机性                                 │
│    • 滚动速度和距离随机化                       │
│    • 有时"误触"，有时"阅读到一半退出"             │
│    • 随机暂停，模拟被其他事情打断                 │
│                                              │
│ ✅ 会话控制                                    │
│    • 单次运行时长：≤ 45 分钟                    │
│    • 强制冷却：运行后休息 ≥ 15 分钟              │
│    • 每日总操作上限（可配置）                    │
│                                              │
│ ✅ 异常检测                                    │
│    • 相同动作重复次数检测（防卡死）               │
│    • 异常页面检测（如被踢出/验证码）               │
│    • 操作成功率监控                             │
└─────────────────────────────────────────────┘
```

---

## 5. AI 交互设计（Prompt 工程）

### 5.1 核心挑战

| 挑战 | 说明 | 应对策略 |
|------|------|---------|
| **无视觉输入** | DeepSeek V4 Flash 不支持图像 | UI 树结构摘要 + OCR 文本 + 语义压缩 |
| **Token 预算** | 屏幕状态可能很长，消耗大量 token | 智能压缩 UI 树，只保留相关层级 |
| **决策一致性** | 独立决策可能导致行为震荡 | 状态机 + 历史上下文 |
| **JSON 格式稳定** | 需要可靠的结构化输出 | `response_format = json_object` + 严格校验 |

### 5.2 System Prompt（系统提示词）

```
# 角色
你是一个手机端 AI 代理，正在控制用户手机上的"小红书"(Xiaohongshu)App。
你的任务是模拟真实用户浏览行为。

# 你的能力
你可以理解当前屏幕状态（以结构化文本形式呈现），并决定下一步操作。

# 可用动作
可用动作和格式如下：

1. { "type": "scroll_down" } — 向下滚动浏览更多内容
2. { "type": "scroll_up" }   — 向上滚动
3. { "type": "tap", "target_id": <数字ID> } — 点击某个 UI 元素
4. { "type": "back" }        — 返回上一页
5. { "type": "like", "target_id": <卡片ID> } — 点赞某篇帖子
6. { "type": "enter_comment", "target_id": <卡片ID> } — 进入评论区
7. { "type": "type_comment", "text": "评论内容" } — 输入并发送评论
8. { "type": "wait", "duration_ms": <毫秒数> } — 等待/停顿

# 输出格式
你必须只输出以下 JSON 格式，不要包含其他文字：

{
  "actions": [ ... 一个或多个动作 ... ],
  "observation": "你观察到的当前页面情况",
  "reasoning": "你为什么决定执行这些动作"
}

# 用户兴趣配置
用户感兴趣的领域/关键词：
{user_interests}

# 行为准则
1. 模拟真人：不要操作太快，间隔合理
2. 内容识别：只有当你认为帖子内容符合用户兴趣时才点赞/评论
3. 点赞标准：
   - 内容与用户兴趣匹配度 ≥ 70%
   - 帖子质量高（点赞多、内容详实）
   - 同一个帖子不要重复点赞
4. 评论标准：
   - 有兴趣强相关且内容丰富
   - 评论要自然、相关、有对话感
   - 评论长度 10-50 字
   - 不要评论每条帖子，选择性评论
5. 浏览行为：
   - 大部分时间用于浏览（滚动）
   - 偶尔进入帖子查看详情
   - 偶尔点赞
   - 很少评论（最贵的操作）
```

### 5.3 屏幕状态构建逻辑

**UI 树压缩策略**（关键优化点）：

原始 Accessibility 节点树可能包含 **数百个节点**。我们必须压缩为 AI 可使用的格式：

```
压缩规则：
1. 移除不可见节点 (isVisibleToUser = false)
2. 合并纯布局容器 (只有子节点、无文本/无交互的 ViewGroup)
3. 识别并标记"卡片"边界 (Card 级别的语义分组)
4. 为每个可交互元素分配 target_id
5. 对列表、网格做摘要（前 N 项完整，其余合并计数）
```

压缩后的输出示例（约为原始大小的 10-20%）：

```
屏幕状态:
┌─ 标题栏 ─────────────────────────────┐
│  [搜索框]                             │
├─ Tab 栏目 ────────────────────────────┤
│  [推荐] [穿搭] [美食] [旅行] [家居]... │
├─ 内容卡片#1 (id: c1) ────────────────┤
│  作者: 北京小张            ♥ 未点赞   │
│  标题: 故宫角楼最美机位攻略            │
│  点赞: 1.2万  收藏: 5432             │
│  bounds: [10, 240, 410, 520]         │
├─ 内容卡片#2 (id: c2) ────────────────┤
│  作者: 旅行达人Lily        ♥ 未点赞   │
│  标题: 云南大理洱海骑行路线              │
│  点赞: 8560   收藏: 2100              │
├─ 内容卡片#3 (id: c3) ────────────────┤
│  作者: 护肤日记            ♥ 已点赞   │
│  标题: 冬季敏感肌护理指南              │
│  点赞: 2.1万  收藏: 8999              │
├─ 还有 7 张卡片（以下略） ─────────────│
└─ 底部导航 ────────────────────────────┘
  [首页] [发现] [+] [消息] [我]
```

**为什么保留 bounds（坐标范围）**：
- Accessibility Service 的 `click` 操作依赖节点引用
- 如果节点引用不可用（页面变化），fallback 到坐标点击
- 坐标用于执行精确的滑动手势

### 5.4 兴趣识别机制

兴趣识别不依赖 DeepSeek 的视觉理解（因为它看不到图片），而是依赖：

1. **帖子标题文本** — 最核心的信息来源
2. **作者名** — 用户可能关注特定作者
3. **帖子描述/标签** — OCR 从截图中提取
4. **AI 语义理解** — DeepSeek 理解文本语义来判断内容相关性

**用户兴趣配置示例**：

```yaml
interests:
  categories:
    - name: "旅行"
      keywords: ["旅行", "攻略", "自驾", "民宿", "机票", "签证"]
      priority: 1            # 最高优先级
    - name: "美食"
      keywords: ["探店", "美食", "菜谱", "烘焙", "咖啡"]
      priority: 2
    - name: "科技"
      keywords: ["数码", "手机", "App", "效率", "AI"]
      priority: 3

behavior:
  like_threshold: 70         # 兴趣匹配度 ≥ 70% 时点赞
  comment_threshold: 85      # 兴趣匹配度 ≥ 85% 时评论
  max_comments_per_hour: 5
  max_likes_per_hour: 30
```

### 5.5 评论生成策略

评论由 DeepSeek 在决策时**实时生成**，要求：

1. **相关性** — 评论必须和帖子内容相关
2. **自然度** — 像真人评论，不要像刷评
3. **多样性** — 避免重复句式
4. **情感适宜** — 正面、友善

**Prompt 中的评论指导**：
```
当你要评论时：
1. 先阅读帖子标题和内容摘要
2. 生成一条自然的中文评论
3. 评论长度：8-40 字
4. 风格参考："去过三次了，真的很美！" 
               "收藏了，周末去试试"
               "楼主拍得好好，求滤镜参数"
5. 避免：广告语气、过度夸张、重复相同句式
```

---

## 6. 状态机与决策逻辑

### 6.1 顶层状态机

```
                ┌──────────────────────┐
                │     LAUNCHING         │── 打开小红书 App
                └──────────┬───────────┘
                           │  App 已启动
                           ▼
                ┌──────────────────────┐
        ┌──────▶│   EXPLORING_FEED     │── 浏览信息流（主要状态）
        │       └──────────┬───────────┘
        │                  │ 发现感兴趣的帖子
        │                  ▼
        │       ┌──────────────────────┐
        │       │    READING_POST      │── 进入帖子详情
        │       └──────────┬───────────┘
        │                  │ 决策：点赞/评论/退出
        │     ┌────────────┼────────────┐
        │     ▼            ▼            ▼
        │  ┌──────┐  ┌──────────┐  ┌────────┐
        │  │LIKE  │  │COMMENTING│  │EXIT    │── 返回信息流
        │  └──┬───┘  └────┬─────┘  └────┬───┘
        │     │           │              │
        │     ▼           ▼              │
        │  ┌──────────────────────┐      │
        │  │   POST_INTERACTION   │──────┘
        │  └──────────────────────┘
        │
        │  每 45 分钟或达到操作上限
        ▼
  ┌──────────────┐
  │   COOLDOWN   │── 停止操作，休息
  └──────────────┘
```

### 6.2 决策规则引擎

每次调用 DeepSeek 时，决策引擎提供以下信息帮助 AI 决策：

```
当前阶段: EXPLORING_FEED
建议策略:
  - 主要动作: scroll_down（浏览更多）
  - 偶尔动作: 如果看到与旅行相关的高赞帖子，可以进入查看
  - 避免: 连续快速操作
```

当处于不同阶段时，Prompt 中嵌入的阶段指导会变化：

| 阶段 | Prompt 附加指令 |
|------|---------------|
| EXPLORING_FEED | "浏览信息流中。大部分时候滚动。遇到高度匹配的帖子才点击进入。" |
| READING_POST | "在帖子详情页。仔细阅读标题和描述，判断是否值得互动。" |
| INTERACTING | "执行互动操作。点赞后退出，或评论后退出。" |
| COOLDOWN | "休息状态，不要做任何操作，等待冷却时间结束。" |

---

## 7. 安全性设计

### 7.1 防平台检测策略

| 策略 | 实现 |
|------|------|
| 操作时序随机化 | 每次操作间隔在基础值上 ±30-50% 随机抖动 |
| 阅读时间模拟 | 进入帖子后停留 5-15 秒再操作 |
| 滚动模式变化 | 有时快速扫过，有时慢速细看，有时回滚 |
| 人眼模拟 | 滚动到新内容后先 pause 再操作 |
| IP 考量 | 使用手机正常流量（非代理/VPN），避免触发风险控制 |
| 操作分布 | 浏览:点赞:评论 ≈ 100:10:1 |

### 7.2 异常检测与恢复

```kotlin
enum class AnomalyType {
    SAME_ACTION_LOOP,          // 连续 5 次相同动作 → 可能卡住
    NO_NEW_CONTENT,           // 滚动多次无新内容 → 可能到底了
    UNEXPECTED_PAGE,          // 出现预期外的页面（登录、验证码）
    ACTION_FAILED,            // 操作连续失败
    APP_CRASHED,              // 小红书闪退
    NETWORK_ERROR,            // DeepSeek API 不可达
    RATE_LIMIT_HIT            // 操作频率超限
}

fun handleAnomaly(type: AnomalyType): RecoveryAction {
    return when (type) {
        SAME_ACTION_LOOP -> {
            // 切换策略：返回首页重新开始，或等待更长时间
            wait(10.seconds)
            NAVIGATE_HOME
        }
        NO_NEW_CONTENT -> {
            // 切换 Tab 或刷新
            SWITCH_TAB
        }
        UNEXPECTED_PAGE -> {
            // 截屏记录，暂停执行，通知用户
            PAUSE_AND_NOTIFY_USER
        }
        APP_CRASHED -> {
            // 重新启动小红书
            RELAUNCH_APP
        }
        // ...
    }
}
```

### 7.3 用户控制与安全机制

- **紧急停止**：通知栏快捷按钮，一键终止所有操作
- **操作确认**：可配置为执行点赞/评论前需要用户确认
- **每日配额**：用户设置每日最大操作数（点赞/评论）
- **黑名单**：用户可指定某些关键词或内容类型不互动
- **运行时间窗口**：可设置仅在特定时间段运行

---

## 8. 配置系统

### 8.1 配置分层

```
用户配置 (User Config)           ← UI 界面设置
  └── 兴趣配置、频率限制、运行时间

任务配置 (Task Config)           ← YAML / JSON
  └── 当前任务的目标和行为参数

系统默认 (Default Config)        ← 内嵌安全默认值
  └── 安全的操作频率基准、最大限制
```

### 8.2 配置项总览

```yaml
# config/agent-default.yaml

ai:
  model: "deepseek-v4-flash"
  temperature: 0.3
  max_tokens: 800
  api_timeout_ms: 30000
  retry:
    max_attempts: 3
    base_delay_ms: 1000

interests:
  categories:
    - name: "旅行"
      keywords: ["旅行", "攻略", "自驾", "民宿"]
      priority: 1
    - name: "美食"
      keywords: ["探店", "美食", "烘焙"]
      priority: 2

behavior:
  like_threshold: 70            # % 兴趣匹配阈值
  comment_threshold: 85         # % 兴趣匹配阈值（评论需更高）
  min_post_view_sec: 3          # 最少浏览时间
  max_post_view_sec: 20         # 最多浏览时间
  scroll_cooldown_ms: 3000      # 滚动间隔
  scroll_variation: 0.5         # 随机变化系数

safety:
  max_likes_per_hour: 30
  max_comments_per_hour: 5
  max_session_minutes: 45
  cooldown_minutes: 15
  max_daily_likes: 200
  max_daily_comments: 30
  same_action_threshold: 5      # 连续 N 次相同动作触发异常

xhs_specific:
  app_package: "com.xingin.xhs"
  app_launch_activity: "com.xingin.xhs.index.IndexActivityV2"
  feed_scroll_duration_ms: 600
  comment_max_length: 1000
```

---

## 9. 实现路线图

### Phase 1 — 基础设施 (MVP) ⭐

| 步骤 | 组件 | 交付物 |
|------|------|--------|
| 1.1 | Android 项目骨架 | Gradle 配置、权限声明、基础 Activity |
| 1.2 | Accessibility Service | 注册服务、获取 UI 树、基本点击/滚动 |
| 1.3 | MediaProjection | 截图权限、截图保存 |
| 1.4 | DeepSeek API Client | HTTP 调用、基础 Prompt、响应解析 |

**里程碑**: 能够通过 DeepSeek 控制手机执行"打开小红书→滚动"的完整链路。

### Phase 2 — 感知与决策

| 步骤 | 组件 | 交付物 |
|------|------|--------|
| 2.1 | UI 树压缩 | 语义化摘要、卡片边界识别 |
| 2.2 | OCR 集成 | Tesseract/ML Kit、文字提取与合并 |
| 2.3 | 状态机 | 阶段管理、状态转换 |
| 2.4 | 核心 Prompt 优化 | 系统提示词迭代、few-shot 示例 |

**里程碑**: 能识别内容流中的帖子，判断是否与兴趣匹配，并做决策。

### Phase 3 — 互动能力

| 步骤 | 组件 | 交付物 |
|------|------|--------|
| 3.1 | 点赞操作 | 元素定位 → 点击点赞 |
| 3.2 | 评论操作 | 进入评论 → 输入 → 发送 |
| 3.3 | 评论生成 | DeepSeek 生成自然的评论内容 |
| 3.4 | 兴趣匹配 | 基于标题/描述的语义匹配 |

**里程碑**: 完整闭环：浏览→识别→点赞/评论。

### Phase 4 — 稳健性与安全

| 步骤 | 组件 | 交付物 |
|------|------|--------|
| 4.1 | 防检测系统 | 随机延迟、行为变化 |
| 4.2 | 异常恢复 | 卡住检测、崩溃恢复 |
| 4.3 | 用户控制面板 | Compose UI、状态展示、紧急停止 |
| 4.4 | 通知与控制 | 前台服务、通知栏控制 |

**里程碑**: 可长时间稳定运行的完整产品。

### Phase 5 — 优化

| 步骤 | 组件 | 交付物 |
|------|------|--------|
| 5.1 | Token 优化 | 屏幕表示压缩、减少 API 调用 |
| 5.2 | 历史记忆 | 更好的重复帖子检测 |
| 5.3 | 多任务 | 支持多个自动化任务切换 |
| 5.4 | 数据分析 | 操作统计、改进建议 |

---

## 10. 荣耀 V20 适配说明

### 10.1 设备规格

| 参数 | 值 |
|------|-----|
| **SoC** | 麒麟 980 (7nm) |
| **CPU** | 2×Cortex-A76 2.6GHz + 2×Cortex-A76 1.92GHz + 4×Cortex-A55 1.8GHz |
| **NPU** | 双核 NPU (支持 AI 加速) |
| **内存** | 6GB / 8GB |
| **存储** | 128GB / 256GB |
| **屏幕** | 2310×1080, 6.26英寸 |
| **系统** | Android 9 → 10 (可升级至 10/EMUI 10) |
| **Android API** | 28-29 |

### 10.2 适配要点

| 项目 | 说明 |
|------|------|
| **Target SDK** | targetSdk = 29 (Android 10)，保持与 EMUI 兼容 |
| **前台服务** | Android 10 需要 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| **MediaProjection** | Android 10 引入 MediaProjection 的截图权限变更，需处理 |
| **Accessibility** | EMUI 对无障碍服务的后台限制较少，但需要用户在设置中手动开启 |
| **NPU 利用** | ML Kit 可自动利用 NPU 加速 OCR，无需额外配置 |
| **省电策略** | EMUI 的省电管理可能杀后台，需要引导用户将 App 加入受保护应用列表 |
| **权限清单** | • `BIND_ACCESSIBILITY_SERVICE` — 核心<br>• `FOREGROUND_SERVICE` — 保活<br>• `SYSTEM_ALERT_WINDOW` — 悬浮窗（可选）<br>• `INTERNET` — 网络<br>• MediaProjection 运行时权限 |

### 10.3 已知限制与应对

| 限制 | 应对 |
|------|------|
| **EMUI 后台限制严格** | 使用前台服务 + 通知；引导用户加入受保护应用 |
| **麒麟 980 算力有限** | 本地 OCR 使用轻量模型；避免实时视频处理 |
| **屏幕分辨率较高** | OCR 前可降采样到 720p 级别 |
| **无 Google Play 服务** | 使用 Tesseract (tess-two) 而非 ML Kit（后者依赖 GMS） |

---

## 11. 风险与应对

### 11.1 技术风险

| 风险 | 可能性 | 影响 | 应对 |
|------|--------|------|------|
| **DeepSeek API 不稳定/延迟** | 中 | 高 | 本地缓存上次决策 + 超时重试降级 |
| **Accessibility 服务被系统杀死** | 高 | 中 | 前台服务保活 + 自动重启机制 |
| **UI 变化（小红书更新）** | 中 | 高 | UI 识别基于语义而非坐标/xpath；定期更新适配 |
| **OCR 准确率不足** | 中 | 中 | 结合 UI Tree 文本做交叉验证 |
| **Token 消耗过大** | 高 | 中 | 压缩 UI 树，减少历史保留，使用 `max_tokens` 控制 |

### 11.2 平台风险

| 风险 | 应对 |
|------|------|
| **小红书反爬/反自动化检测** | 频率控制 + 行为随机化 + 模拟人类操作特征 |
| **账号限流/封禁** | 。建议使用小号；合理配置每日上限 |
| **DeepSeek API 合规** | 确保 API 使用符合 DeepSeek 服务条款 |

### 11.3 法律与伦理

> ⚠️ **重要提示**：本工具仅供个人学习和研究使用。使用自动化工具操作社交媒体平台可能违反该平台的服务条款。请：
> - 使用自己的账号，或专门注册用于测试的小号
> - 遵守平台社区规范
> - 不要用于刷量、灌水等违规行为
> - 作者不对使用本工具产生的任何后果负责

---

## 附录

### A. 数据模型定义

```kotlin
// 核心数据模型 (model/ 包)

@Serializable
data class ScreenState(
    val pageType: PageType,
    val uiElements: List<UiElement>,
    val ocrTexts: List<OcrTextBlock>,
    val timestamp: Long
)

@Serializable
data class UiElement(
    val id: Int,                    // 唯一标识
    val text: String?,              // 显示文本
    val contentDesc: String?,       // 内容描述
    val className: String,          // 控件类型
    val bounds: Rect,               // 屏幕坐标
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean?,        // 点赞状态
    val children: List<UiElement>   // 子元素
)

@Serializable
data class AgentAction(
    val type: ActionType,
    val targetId: Int? = null,
    val coordinate: Coordinate? = null,
    val text: String? = null,
    val durationMs: Long? = null,
    val reason: String = ""
)

@Serializable
enum class ActionType {
    SCROLL_DOWN, SCROLL_UP, TAP, TAP_COORD,
    BACK, HOME, WAIT, LIKE, ENTER_COMMENT,
    TYPE_COMMENT, ENTER_POST, EXIT_POST
}
```

### B. DeepSeek 调用示例

**Request**:
```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    {
      "role": "system",
      "content": "[系统提示词]"
    },
    {
      "role": "user",
      "content": "[当前屏幕状态 + 上下文]"
    }
  ],
  "temperature": 0.3,
  "max_tokens": 800,
  "response_format": { "type": "json_object" }
}
```

**Response**:
```json
{
  "actions": [
    {
      "type": "enter_post",
      "target_id": 2,
      "reason": "第二篇帖子是关于云南大理骑行的，与旅行兴趣高度匹配"
    }
  ],
  "observation": "发现信息流中有多篇旅行相关帖子，其中一篇关于大理洱海骑行的与用户旅行兴趣匹配度高",
  "reasoning": "优先检查高优先级兴趣（旅行），这篇帖子标题包含'大理'、'骑行'关键词，点赞数8560说明质量不错"
}
```

---

> **文档状态**: Draft v0.1  
> **下一步**: 创建 Android 项目骨架 → 实现 Phase 1
