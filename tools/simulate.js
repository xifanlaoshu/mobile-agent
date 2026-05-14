#!/usr/bin/env node
/**
 * XhsAgent 仿真测试 — 验证 AI 决策链路
 *
 * 不需要 Android 设备 / 模拟器。本地 Node.js 运行，用模拟的屏幕状态
 * 测试 DeepSeek 能否正确理解决策 prompt 并返回合法 JSON 动作。
 *
 * 使用:
 *   node tools/simulate.js
 *   或指定 API Key:
 *   set DEEPSEEK_API_KEY=sk-xxx && node tools/simulate.js
 */

const HTTPS = require("https");
const PATH = require("path");
const FS = require("fs");

// ======================== 配置 ========================

const API_KEY = process.env.DEEPSEEK_API_KEY || "";
const MODEL = "deepseek-v4-flash";
const BASE_URL = "api.deepseek.com";
const API_PATH = "/v1/chat/completions";

// 用环境变量覆盖，或者直接在代码里填（仅测试）
if (!API_KEY) {
  console.error(
    "❌ 请设置 DEEPSEEK_API_KEY 环境变量:\n" +
      "   set DEEPSEEK_API_KEY=sk-xxx && node tools/simulate.js"
  );
  process.exit(1);
}

// ======================== 加载配置 ========================

const PROJECT_ROOT = PATH.dirname(__dirname);

function loadSystemPrompt() {
  const raw = FS.readFileSync(
    PATH.join(PROJECT_ROOT, "config/prompts/system-prompt.md"),
    "utf8"
  );
  // 提取 ``` 代码块中的实际 prompt 内容
  const match = raw.match(/```\n([\s\S]*?)```/);
  return match ? match[1] : raw;
}

let SYSTEM_PROMPT_TEMPLATE = loadSystemPrompt();

// ======================== 用户兴趣配置 ========================

const USER_INTERESTS = `
用户兴趣配置（按优先级排序）：
[优先级 1] 旅行: 旅行, 旅游, 攻略, 自驾, 民宿, 机票, 签证, 古镇, 自然风光, 摄影, 景点, 打卡
[优先级 2] 美食: 美食, 探店, 菜谱, 烘焙, 咖啡, 甜品, 家常菜, 餐厅, 小吃, 饮茶
[优先级 3] 科技: 数码, 手机, App, 效率, AI, 人工智能, 编程, 生产力, 工具

匹配规则：
- 当帖子标题/描述包含以上关键词时，认为是相关帖子
- 优先级越高的兴趣类别，越值得互动
- 只有当内容明显匹配时才点赞/评论
`;

// ======================== 模拟屏幕状态 ========================

/**
 * 模拟 4 种典型场景的屏幕状态
 */
const SIMULATED_SCREENS = [
  // 场景 1: 刚打开发现页，信息流浏览
  {
    name: "信息流浏览 — 普通滚动",
    screenState: `=== 屏幕状态 ===
当前页面: 发现页 (发现Tab)

=== 页面结构 ===
  [标题栏]  搜索框
  [Tab栏]   推荐 | 穿搭 | 美食 | 旅行 | 家居 | 摄影
  ┌─ 内容卡片#1 (id: c1) ────────────────────
  │  作者: 北京吃货小分队          ♡ 未点赞
  │  标题: 北京胡同里的10家隐藏咖啡馆
  │  点赞: 1.2万   收藏: 5432
  │  bounds: [可点击]
  ├─ 内容卡片#2 (id: c2) ────────────────────
  │  作者: 护肤日记本              ♡ 未点赞
  │  标题: 冬季敏感肌护理避坑指南
  │  点赞: 8560    收藏: 2100
  ├─ 内容卡片#3 (id: c3) ────────────────────
  │  作者: 数码测评君              ♡ 未点赞
  │  标题: 2025年最值得买的5款真无线耳机
  │  点赞: 2.1万   收藏: 8999
  ├─ 内容卡片#4 (id: c4) ────────────────────
  │  作者: 旅行达人Lily            ♡ 未点赞
  │  标题: 云南大理洱海骑行全攻略（附路线图）
  │  点赞: 2.3万   收藏: 1.2万
  ├─ 内容卡片#5 (id: c5) ────────────────────
  │  作者: 居家好物分享            ♡ 未点赞
  │  标题: 让家里变高级的10个小物件
  │  点赞: 4321    收藏: 987
  ├─ 还有 12 张卡片（以下略）
  └──────────────────────────────────────────
  [底部导航] 首页 | 发现 | + | 消息 | 我

=== 操作历史 ===
  (本轮刚开始，无历史操作)

=== 时间信息 ===
当前时间: 14:35:22
运行时长: 0分5秒
今日已点赞: 0 次
今日已评论: 0 次`,
  },

  // 场景 2: 发现旅行相关帖子，应该进入详情并点赞
  {
    name: "信息流浏览 — 发现高匹配旅行帖子",
    screenState: `=== 屏幕状态 ===
当前页面: 发现页 (发现Tab)

=== 页面结构 ===
  [标题栏]  搜索框
  [Tab栏]   推荐 | 穿搭 | 美食 | 旅行 | 家居 | 摄影
  ┌─ 内容卡片#1 (id: c1) ────────────────────
  │  作者: 背包客小王              ♡ 未点赞
  │  标题: 新疆独库公路自驾完整攻略，此生必去！
  │  点赞: 3.8万   收藏: 2.5万
  │  bounds: [可点击]
  ├─ 内容卡片#2 (id: c2) ────────────────────
  │  作者: 民宿体验师Amy            ♡ 未点赞
  │  标题: 莫干山9家神仙民宿大盘点，每一家都想住
  │  点赞: 1.5万   收藏: 8900
  │  bounds: [可点击]
  ├─ 内容卡片#3 (id: c3) ────────────────────
  │  作者: 美妆分享                ♡ 未点赞
  │  标题: 日常通勤妆容教程，5分钟搞定
  │  点赞: 5678    收藏: 1234
  ├─ 还有 15 张卡片（以下略）
  └──────────────────────────────────────────
  [底部导航] 首页 | 发现 | + | 消息 | 我

=== 操作历史 ===
  [14:35:10] ✓ SCROLL_DOWN (已滚动浏览)
  [14:35:15] ✓ SCROLL_DOWN (继续下滑)

=== 时间信息 ===
当前时间: 14:35:30
运行时长: 0分30秒
今日已点赞: 0 次
今日已评论: 0 次`,
  },

  // 场景 3: 帖子详情页，已点赞，判断是否评论
  {
    name: "帖子详情页 — 判断是否评论",
    screenState: `=== 屏幕状态 ===
当前页面: 帖子详情 (POST_DETAIL)

=== 页面结构 ===
  [标题栏]  ←返回 | ...更多
  ┌─ 帖子头部 ──────────────────────────
  │  作者头像: 背包客小王
  │  作者名: 背包客小王
  │  关注按钮: [关注]
  ├─ 帖子正文 ──────────────────────────
  │  标题: 新疆独库公路自驾完整攻略，此生必去！
  │  
  │  正文摘要: 去年夏天花了15天自驾独库公路，
  │  整理了这份超详细攻略。包括路线规划、景点推荐、
  │  住宿预算、车辆准备……图文并茂，建议先收藏再看！
  │  
  │  [#自驾] [#新疆] [#旅行攻略] [#公路旅行]
  ├─ 互动栏 ────────────────────────────
  │  ♥ 已点赞 (3.8万)    ⭐ 收藏 (2.5万)
  │  💬 评论 (4521)      ↗ 分享
  └──────────────────────────────────────

=== 操作历史 ===
  [14:35:15] ✓ SCROLL_DOWN (浏览信息流)
  [14:35:20] ✓ ENTER_POST #c1 (进入独库公路帖子)
  [14:35:28] ✓ WAIT (模拟阅读，8秒)
  [14:35:30] ✓ LIKE #c1 (内容质量高，与旅行高度匹配)

=== 时间信息 ===
当前时间: 14:36:00
运行时长: 1分0秒
今日已点赞: 1 次
今日已评论: 0 次`,
  },

  // 场景 4: 评论页面，模拟输入评论
  {
    name: "评论页面 — 输入并发送评论",
    screenState: `=== 屏幕状态 ===
当前页面: 评论区 (COMMENT_SECTION)

=== 页面结构 ===
  [标题栏]  ←返回 | 共4521条评论
  ┌─ 评论区 ────────────────────────────
  │  ┌─ 评论#1 ─────────────────────────
  │  │ 用户: 自驾老司机
  │  │ 内容: 说实话，独库公路确实美，但新手最好别去
  │  │       ，弯道太多了。
  │  │ 赞 234  回复
  │  └──────────────────────────────────
  │  ┌─ 评论#2 ─────────────────────────
  │  │ 用户: 摄影师阿明
  │  │ 内容: 楼主拍得也太好了！用的什么相机？
  │  │ 赞 567  回复
  │  ├─ 还有 4520 条评论
  ├─ 输入框 ────────────────────────────
  │  [写评论...]  ──── 当前输入焦点在此
  │  [表情] [图片] @ [发送]            │
  └──────────────────────────────────────

=== 操作历史 ===
  [14:35:20] ✓ ENTER_POST #c1
  [14:35:28] ✓ WAIT (阅读8秒)
  [14:35:30] ✓ LIKE #c1
  [14:36:00] ✓ ENTER_COMMENT #c1

=== 时间信息 ===
当前时间: 14:36:05
运行时长: 1分5秒
今日已点赞: 1 次
今日已评论: 0 次`,
  },
];

// ======================== DeepSeek API 调用 ========================

function callDeepSeek(systemPrompt, userPrompt) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      model: MODEL,
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: userPrompt },
      ],
      temperature: 0.3,
      max_tokens: 1200,
      response_format: { type: "json_object" },
    });

    const req = HTTPS.request(
      {
        hostname: BASE_URL,
        path: API_PATH,
        method: "POST",
        headers: {
          Authorization: `Bearer ${API_KEY}`,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        timeout: 60000,
      },
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          if (res.statusCode !== 200) {
            reject(
              new Error(
                `HTTP ${res.statusCode}: ${data.slice(0, 300)}`
              )
            );
            return;
          }
          try {
            const json = JSON.parse(data);
            const content = json.choices?.[0]?.message?.content;
            if (!content) {
              reject(new Error("Empty response content"));
              return;
            }
            resolve(JSON.parse(content));
          } catch (e) {
            reject(new Error(`Parse error: ${e.message}\nRaw: ${data.slice(0, 300)}`));
          }
        });
      }
    );

    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Request timeout"));
    });

    req.write(body);
    req.end();
  });
}

// ======================== 验证响应 ========================

const VALID_ACTIONS = [
  "scroll_down",
  "scroll_up",
  "tap",
  "back",
  "wait",
  "like",
  "enter_comment",
  "type_comment",
  "enter_post",
  "exit_post",
  "navigate_tab",
];

/**
 * 规范化 AI 响应格式。DeepSeek 有时会输出不规范的格式，这里做兼容。
 */
function normalizeResponse(raw) {
  // 格式1: {"actions":[{"type":"scroll"}]} → 标准格式，直接返回
  if (Array.isArray(raw.actions)) {
    return raw;
  }

  // 格式2: {"action":"like","target_id":4} → 转换为标准格式
  if (typeof raw.action === "string") {
    const action = { type: raw.action };
    if (raw.target_id) action.target_id = raw.target_id;
    if (raw.text) action.text = raw.text;
    if (raw.duration_ms) action.duration_ms = raw.duration_ms;
    if (raw.reason) action.reason = raw.reason;
    return { actions: [action], observation: raw.observation || "", reasoning: raw.reasoning || "" };
  }

  // 格式3: {"action":{"type":"like",...}} → 转换为数组
  if (typeof raw.action === "object" && raw.action.type) {
    return { actions: [raw.action], observation: raw.observation || "", reasoning: raw.reasoning || "" };
  }

  return raw; // 返回原始对象让后续验证报错
}

function validateResponse(rawResponse, scenarioName) {
  const errors = [];

  if (!rawResponse || typeof rawResponse !== "object") {
    errors.push("响应不是 JSON 对象");
    return { errors, response: rawResponse };
  }

  // 先规范化格式
  const response = normalizeResponse(rawResponse);

  if (!Array.isArray(response.actions)) {
    errors.push("缺少 actions 数组（模型返回了不标准格式）");
    return { errors, response: rawResponse };
  }

  if (response.actions.length === 0) {
    errors.push("actions 数为空");
  }

  response.actions.forEach((action, i) => {
    if (!action.type) {
      errors.push(`action[${i}]: 缺少 type 字段`);
    } else if (!VALID_ACTIONS.includes(action.type)) {
      errors.push(`action[${i}]: 未知动作类型 '${action.type}'`);
    }
    if (!action.reason) {
      errors.push(`action[${i}]: 缺少 reason 字段`);
    }
    // 特定动作需要参数
    if (["tap", "like", "enter_comment", "enter_post"].includes(action.type)) {
      if (!action.target_id) {
        errors.push(
          `action[${i}]: ${action.type} 缺少 target_id`
        );
      }
    }
    if (action.type === "type_comment" && !action.text) {
      errors.push(`action[${i}]: type_comment 缺少 text`);
    }
    if (action.text && action.text.length > 200) {
      errors.push(
        `action[${i}]: 评论文本过长 (${action.text.length} 字符)`
      );
    }
  });

  return { errors, response };
}

// ======================== 主流程 ========================

function buildSystemPrompt(sessionState) {
  return SYSTEM_PROMPT_TEMPLATE.replace(
    "{user_interests_section}",
    USER_INTERESTS
  ).replace("{state_section}", sessionState);
}

function buildSessionState(scenarioIndex, scenario) {
  return `
当前会话状态：
- 场景: ${scenario.name}
- 今日已点赞: ${scenarioIndex * 2} 次
- 今日已评论: ${scenarioIndex} 次
- 会话开始时间: 14:35:00

注意事项：
- 已经点赞过的帖子不要重复点赞
- 已经评论过的帖子不要重复评论
- 记住当前所处阶段
  `;
}

async function main() {
  console.log("+==============================================+");
  console.log("|   XhsAgent AI Decision Simulation          |");
  console.log("|   Model: deepseek-v4-flash               |");
  console.log("+==============================================+\n");

  let totalLatency = 0;
  let totalActions = 0;
  let passCount = 0;
  let failCount = 0;

  for (let i = 0; i < SIMULATED_SCREENS.length; i++) {
    const scenario = SIMULATED_SCREENS[i];
    const separator = "-".repeat(60);
    console.log(separator);
    console.log(`\n📱 场景 ${i + 1}/${SIMULATED_SCREENS.length}: ${scenario.name}\n`);

    const sessionState = buildSessionState(i, scenario);
    const systemPrompt = buildSystemPrompt(sessionState);

    const startTime = Date.now();

    try {
      const response = await callDeepSeek(systemPrompt, scenario.screenState);
      const latency = Date.now() - startTime;
      totalLatency += latency;

      const { errors, response: normalized } = validateResponse(response, scenario.name);

      if (errors.length === 0) {
        passCount++;
        console.log(`✅ 决策通过  (${latency}ms)`);
        console.log(`   动作数: ${normalized.actions.length}`);
        normalized.actions.forEach((a, j) => {
          const details = [];
          if (a.target_id) details.push(`target=${a.target_id}`);
          if (a.text) details.push(`text="${a.text.slice(0, 40)}"`);
          if (a.duration_ms) details.push(`delay=${a.duration_ms}ms`);
          console.log(
            `   [${j + 1}] ${a.type.padEnd(15)} ${details.join(" | ")}`
          );
          if (a.reason) {
            console.log(`       理由: ${a.reason}`);
          }
        });
        if (normalized.observation) {
          console.log(`   观察: ${normalized.observation.slice(0, 100)}`);
        }
        if (normalized.reasoning) {
          console.log(`   逻辑: ${normalized.reasoning.slice(0, 120)}`);
        }
        totalActions += normalized.actions.length;
      } else {
        failCount++;
        console.log(`❌ 决策失败  (${latency}ms)`);
        console.log(`   响应内容:`);
        console.log(JSON.stringify(response, null, 2).slice(0, 500));
        console.log(`   错误:`);
        errors.forEach((e) => console.log(`   • ${e}`));
      }
    } catch (err) {
      failCount++;
      const latency = Date.now() - startTime;
      console.log(`💥 API 调用失败 (${latency}ms)`);
      console.log(`   错误: ${err.message}`);
    }
  }

  // ======================== 汇总 ========================
  console.log("\n" + "=".repeat(60));
  console.log("\n📊 测试汇总\n");
  console.log(
    `   场景总数:    ${SIMULATED_SCREENS.length}`
  );
  console.log(
    `   ✅ 通过:      ${passCount}`
  );
  console.log(
    `   ❌ 失败:      ${failCount}`
  );
  console.log(
    `   总动作数:    ${totalActions}`
  );
  console.log(
    `   平均延迟:    ${Math.round(totalLatency / SIMULATED_SCREENS.length)}ms`
  );
  console.log(
    `   总 Token 估计: ~${totalActions * 200 + SIMULATED_SCREENS.length * 500} (不含 system prompt)`
  );

  if (failCount > 0) {
    console.log("\n⚠️  有场景未通过验证。请检查上面的错误信息，调整 prompt 或屏幕状态格式。");
  } else {
    console.log("\n✅ 全部场景通过！AI 决策链路验证成功，可以进入 Android 集成阶段。");
  }
  console.log("");
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
