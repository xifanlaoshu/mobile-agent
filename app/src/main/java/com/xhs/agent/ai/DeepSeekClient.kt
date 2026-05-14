package com.xhs.agent.ai

import android.util.Log
import com.xhs.agent.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 客户端
 *
 * 负责：
 * 1. 将屏幕状态 + 上下文构建为 API 请求
 * 2. 调用 DeepSeek chat/completions 接口
 * 3. 解析返回的 JSON 为 AiResponse
 * 4. 错误处理和重试
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com/v1",
    private val model: String = "deepseek-v4-flash",
    private val temperature: Double = 0.3,
    private val maxTokens: Int = 800
) {
    companion object {
        private const val TAG = "DeepSeekClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // DeepSeek 可能较慢
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    // ============ DeepSeek API DTOs ============

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.3,
        @SerialName("max_tokens")
        val maxTokens: Int = 800,
        @SerialName("response_format")
        val responseFormat: ResponseFormat? = ResponseFormat("json_object")
    )

    @Serializable
    data class Message(
        val role: String,       // "system" | "user" | "assistant"
        val content: String
    )

    @Serializable
    data class ResponseFormat(
        val type: String        // "text" | "json_object"
    )

    @Serializable
    data class ChatResponse(
        val id: String? = null,
        val choices: List<Choice>? = null,
        val error: ApiError? = null
    )

    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: Message? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null
    )

    @Serializable
    data class ApiError(
        val message: String? = null,
        val code: String? = null
    )

    // ============ 核心 API ============

    /**
     * 向 DeepSeek 发送当前屏幕状态，获取决策动作
     *
     * @param screenState 当前屏幕状态
     * @param systemPrompt 系统提示词
     * @param userInterests 用户兴趣配置（用于填充 prompt 模板）
     * @param sessionContext 会话上下文
     * @return 解析后的 AiResponse
     */
    suspend fun decide(
        screenState: ScreenState,
        systemPrompt: String,
        userInterests: String,
        sessionContext: SessionContext
    ): Result<AiResponse> = withContext(Dispatchers.IO) {
        try {
            // 1. 构建用户 prompt（屏幕状态 + 上下文）
            val userPrompt = PromptBuilder.buildUserPrompt(
                screenState = screenState,
                userInterests = userInterests,
                sessionContext = sessionContext
            )

            // 2. 填充系统 prompt（替换占位符）
            val filledSystemPrompt = systemPrompt
                .replace("{user_interests_section}", formatInterestsSection(userInterests))
                .replace("{state_section}", formatStateSection(sessionContext))

            // 3. 构建请求
            val request = ChatRequest(
                model = model,
                messages = listOf(
                    Message(role = "system", content = filledSystemPrompt),
                    Message(role = "user", content = userPrompt)
                ),
                temperature = temperature,
                maxTokens = maxTokens,
                responseFormat = ResponseFormat("json_object")
            )

            // 4. 序列化请求
            val requestBodyJson = json.encodeToString(ChatRequest.serializer(), request)

            // 5. 发送 HTTP 请求
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBodyJson.toRequestBody(mediaType))
                .build()

            Log.d(TAG, "Sending request to DeepSeek (model=$model, tokens=$maxTokens)")

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code} $responseBody")
                return@withContext Result.failure(
                    ApiException("HTTP ${response.code}: $responseBody")
                )
            }

            // 6. 解析响应
            val chatResponse = json.decodeFromString(
                ChatResponse.serializer(),
                responseBody ?: ""
            )

            // 7. 检查 API 错误
            chatResponse.error?.let { error ->
                Log.e(TAG, "DeepSeek API error: ${error.message}")
                return@withContext Result.failure(
                    ApiException("DeepSeek error: ${error.message}")
                )
            }

            // 8. 提取 AI 回复
            val content = chatResponse.choices
                ?.firstOrNull()
                ?.message
                ?.content

            if (content.isNullOrBlank()) {
                return@withContext Result.failure(
                    ApiException("Empty response from DeepSeek")
                )
            }

            // 9. 解析 JSON 回复
            val aiResponse = try {
                json.decodeFromString(AiResponse.serializer(), content)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse AI response JSON: $content", e)
                return@withContext Result.failure(
                    ApiException("JSON parse error: ${e.message}")
                )
            }

            Log.d(TAG, "DeepSeek response: ${aiResponse.actions.size} actions, " +
                    "observation='${aiResponse.observation.take(50)}'")

            return@withContext Result.success(aiResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Network or unexpected error", e)
            return@withContext Result.failure(ApiException("Request failed: ${e.message}"))
        }
    }

    /**
     * 带重试的决策调用
     */
    suspend fun decideWithRetry(
        screenState: ScreenState,
        systemPrompt: String,
        userInterests: String,
        sessionContext: SessionContext,
        maxRetries: Int = 3
    ): Result<AiResponse> {
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            val result = decide(
                screenState, systemPrompt, userInterests, sessionContext
            )

            if (result.isSuccess) {
                val response = result.getOrThrow()

                // 验证响应是否包含有效动作
                if (response.actions.isNotEmpty()) {
                    return result
                }

                Log.w(TAG, "Attempt $attempt: empty actions list, retrying...")
                lastError = ApiException("Empty actions list")
            } else {
                lastError = result.exceptionOrNull()
                Log.w(TAG, "Attempt $attempt failed: ${lastError?.message}")

                // 只在网络错误时重试
                if (lastError !is ApiException || 
                    lastError.message?.contains("HTTP 4") == true) {
                    return result  // 4xx 错误不重试
                }
            }

            // 指数退避
            if (attempt < maxRetries) {
                val delayMs = 1000L * (1 shl (attempt - 1))
                Log.d(TAG, "Retrying in ${delayMs}ms...")
                kotlinx.coroutines.delay(delayMs)
            }
        }

        return Result.failure(
            lastError ?: ApiException("Max retries exceeded")
        )
    }

    // ============ Prompt 辅助 ============

    private fun formatInterestsSection(interests: String): String {
        return """
用户兴趣配置（按优先级排序）：
$interests

匹配规则：
- 当帖子标题/描述包含以上关键词时，认为是相关帖子
- 优先级越高的兴趣类别，越值得互动
- 只有当内容明显匹配时才点赞/评论
        """.trimIndent()
    }

    private fun formatStateSection(context: SessionContext): String {
        return """
当前会话状态：
- 浏览阶段: ${context.phase.name}
- 今日已点赞: ${context.totalLikesToday} 次
- 今日已评论: ${context.totalCommentsToday} 次
- 已互动帖子数: ${context.alreadyLiked.size + context.alreadyCommented.size}
- 会话开始时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(context.sessionStartTime))}

注意事项：
- 已经点赞过的帖子不要重复点赞
- 已经评论过的帖子不要重复评论
        """.trimIndent()
    }

    class ApiException(message: String) : Exception(message)
}
