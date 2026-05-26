package com.composepreviewpro.renderer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin Anthropic Messages API client used by [com.composepreviewpro.mock.MockEngine]
 * when AI mocks are requested. Falls back silently to the generic
 * heuristic mock whenever the API is unreachable, returns an error, or
 * the response shape is unexpected — the renderer must never become
 * unusable because of a network blip.
 *
 * Activation: requires the `ANTHROPIC_API_KEY` env var on the renderer
 * process. The plugin's `Settings → Tools → Compose Preview Pro` panel
 * (future work) will write this; for now developers set it themselves
 * via `export ANTHROPIC_API_KEY=...` before launching IDEA.
 *
 * Caching: every (paramName, typeName, callerHint) triple is cached
 * in-memory for the lifetime of the renderer JVM. Re-using the cached
 * answer is free, deterministic, and keeps the Anthropic bill bounded.
 */
class AiMockClient(
    private val apiKey: String,
    private val model: String = "claude-haiku-4-5-20251001",
    private val httpClient: HttpClient = defaultClient(),
) {
    private val cache = mutableMapOf<CacheKey, String>()
    private val json = Json { ignoreUnknownKeys = true }

    private data class CacheKey(val paramName: String, val typeName: String, val hint: String)

    /**
     * Ask Claude for a realistic value for a String parameter.
     *
     * @param paramName e.g. `"email"`, `"flightNumber"`, `"venueName"`
     * @param typeName  always `"String"` for now — passed for future
     *                  expansion (rich types via JSON skeleton).
     * @param hint      additional context such as the surrounding data
     *                  class name (`"Booking"`, `"User"`) — improves
     *                  realism dramatically.
     * @return the model's suggestion, or null on any failure.
     */
    fun generateString(paramName: String, typeName: String = "String", hint: String = ""): String? {
        val key = CacheKey(paramName, typeName, hint)
        cache[key]?.let { return it }

        val prompt = buildPrompt(paramName, typeName, hint)
        return try {
            val response = sendRequest(prompt) ?: return null
            val sanitised = response.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: return null
            cache[key] = sanitised
            sanitised
        } catch (t: Throwable) {
            System.err.println("[ai-mock] failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun buildPrompt(paramName: String, typeName: String, hint: String): String =
        buildString {
            append("Generate ONE realistic example value for the parameter `").append(paramName)
            append("` of type ").append(typeName)
            if (hint.isNotBlank()) {
                append(" in the context of `").append(hint).append('`')
            }
            append(". Respond with ONLY the value, no quotes, no commentary, no markdown.")
        }

    private fun sendRequest(userPrompt: String): String? {
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(128))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(userPrompt))
                })
            })
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(10))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            System.err.println("[ai-mock] HTTP ${response.statusCode()}: ${response.body().take(200)}")
            return null
        }
        return extractText(response.body())
    }

    private fun extractText(body: String): String? {
        // Anthropic response: {"content":[{"type":"text","text":"..."}],...}
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val contentArray = root["content"]?.jsonArray ?: return null
        val firstText = contentArray
            .firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" }
            as? JsonObject
        return firstText?.get("text")?.jsonPrimitive?.contentOrNull
    }

    companion object {
        /** Loaded once on renderer startup. Null when env var unset. */
        @JvmStatic
        val FROM_ENV: AiMockClient? by lazy {
            val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
            if (key != null) {
                System.err.println("[ai-mock] activated with ANTHROPIC_API_KEY (length=${key.length})")
                AiMockClient(apiKey = key)
            } else null
        }

        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }
}

/**
 * Lightweight Serializable bridge — exists only to keep this file
 * self-contained for future kotlinx-serialization tooling additions.
 */
@Serializable
private data class AiMockRequestStub(val paramName: String, val typeName: String, val hint: String)
