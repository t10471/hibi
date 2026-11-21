package com.example.hibi.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared Gemini REST API client targeting `gemini-4-hydrogen` with `thinkingLevel = "HIGH"`,
 * automatic fallback to `gemini-flash-latest`, thought-part filtering, and cost tracking.
 */
object GeminiApiClient {

    const val PRIMARY_MODEL = "gemini-4-hydrogen"
    const val FALLBACK_MODEL = "gemini-flash-latest"
    const val THINKING_LEVEL = "HIGH"

    private val json = Json { ignoreUnknownKeys = true }

    fun generateContentText(
        client: OkHttpClient,
        apiKey: String,
        prompt: String,
        category: String,
        enableGoogleSearch: Boolean = false,
    ): String {
        val primaryPayload = buildPayload(prompt, enableGoogleSearch, useHydrogenHigh = true)
        val (primaryCode, primaryBody) = executeRequest(client, apiKey, PRIMARY_MODEL, primaryPayload)

        val (finalBody, modelUsed) = if (primaryCode == 400 || primaryCode == 404) {
            val fallbackPayload = buildPayload(prompt, enableGoogleSearch, useHydrogenHigh = false)
            val (fallbackCode, fallbackBody) = executeRequest(client, apiKey, FALLBACK_MODEL, fallbackPayload)
            if (fallbackCode !in 200..299) {
                error("Gemini APIエラー ($fallbackCode): $fallbackBody")
            }
            fallbackBody to FALLBACK_MODEL
        } else if (primaryCode !in 200..299) {
            error("Gemini APIエラー ($primaryCode): $primaryBody")
        } else {
            primaryBody to PRIMARY_MODEL
        }

        val jsonObj = json.parseToJsonElement(finalBody).jsonObject
        GeminiUsageTracker.recordFromJson(
            responseJson = jsonObj,
            category = category,
            fallbackModel = modelUsed,
        )
        return extractAnswerText(jsonObj)
    }

    private fun buildPayload(
        prompt: String,
        enableGoogleSearch: Boolean,
        useHydrogenHigh: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                putJsonArray("parts") {
                    addJsonObject {
                        put("text", prompt)
                    }
                }
            }
        }
        if (enableGoogleSearch) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonObject("google_search") {}
                }
            }
        }
        putJsonObject("generationConfig") {
            if (useHydrogenHigh) {
                put("maxOutputTokens", 16384)
                putJsonObject("thinkingConfig") {
                    put("thinkingLevel", THINKING_LEVEL)
                }
            } else {
                put("maxOutputTokens", 4096)
                put("temperature", 0.3)
            }
        }
    }

    private fun executeRequest(
        client: OkHttpClient,
        apiKey: String,
        model: String,
        payload: JsonObject,
    ): Pair<Int, String> {
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun extractAnswerText(jsonObj: JsonObject): String {
        val candidates = jsonObj["candidates"]?.jsonArray
        val firstCandidate = candidates?.firstOrNull()?.jsonObject
        val content = firstCandidate?.get("content")?.jsonObject
        val parts = content?.get("parts")?.jsonArray
        return parts
            ?.filter { part ->
                val isThought = part.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull == true
                !isThought
            }
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n\n")
            .orEmpty()
    }
}
