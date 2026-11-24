package com.aibuilt.arin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ProviderException(val code: Int, message: String) : Exception(message)

sealed class UserCheck {
    data class Ok(val login: String, val fineGrainedHint: Boolean) : UserCheck()
    data class Invalid(val message: String) : UserCheck()
}

data class RepoResult(val existed: Boolean, val fullName: String)

class GitHubClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun req(url: String, method: String = "GET", token: String? = null, body: String? = null): Request {
        val b = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        if (method != "GET") {
            b.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        return b.build()
    }

    private inline fun <T> call(request: Request, parse: (code: Int, body: String) -> T): T {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return parse(resp.code, text)
            throw mapError(resp.code, text)
        }
    }

    private fun mapError(code: Int, body: String): ProviderException {
        val githubMsg = runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        return when (code) {
            401 -> ProviderException(code, "GitHub rejected the token (401 Unauthorized). It was revoked or mistyped — reconnect GitHub in Settings.")
            403 -> ProviderException(code, "GitHub refused the call (403 Forbidden). Rate limit reached or the token lacks the required scope.")
            404 -> ProviderException(
                code,
                "GitHub: Not Found — the repo doesn't exist or the token can't see it. " +
                    "Fine-grained tokens need Contents + Administration write; classic tokens need the repo scope." +
                    (githubMsg?.let { " ($it)" } ?: "")
            )
            else -> ProviderException(code, "GitHub returned HTTP $code" + (githubMsg?.let { ": $it" } ?: ""))
        }
    }

    suspend fun checkUser(token: String): UserCheck = withContext(Dispatchers.IO) {
        try {
            call(req("https://api.github.com/user", token = token)) { _, body ->
                val obj = json.parseToJsonElement(body).jsonObject
                val login = obj["login"]?.jsonPrimitive?.content.orEmpty()
                val tokenType = obj["token_type"]?.jsonPrimitive?.content.orEmpty()
                UserCheck.Ok(login, tokenType.contains("app", ignoreCase = true))
            }
        } catch (e: ProviderException) {
            UserCheck.Invalid(e.message ?: "Token check failed")
        }
    }

    suspend fun repoExists(token: String, owner: String, repo: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                call(req("https://api.github.com/repos/$owner/$repo", token = token)) { _, _ -> true }
            }.getOrDefault(false)
        }

    suspend fun ensureRepo(token: String, owner: String, repo: String): RepoResult =
        withContext(Dispatchers.IO) {
            if (repoExists(token, owner, repo)) RepoResult(true, "$owner/$repo")
            else {
                val payload = buildJsonObject {
                    put("name", repo)
                    put("description", "Built by Arin")
                    put("auto_init", true)
                }
                call(
                    req(
                        "https://api.github.com/user/repos",
                        method = "POST",
                        token = token,
                        body = payload.toString()
                    )
                ) { _, body ->
                    val fullName = runCatching {
                        json.parseToJsonElement(body).jsonObject["full_name"]?.jsonPrimitive?.content
                    }.getOrNull() ?: "$owner/$repo"
                    RepoResult(false, fullName)
                }
            }
        }

    suspend fun remainingRateLimit(token: String?): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            call(req("https://api.github.com/rate_limit", token = token)) { _, body ->
                val core = json.parseToJsonElement(body).jsonObject["resources"]
                    ?.jsonObject?.get("core")?.jsonObject
                val rem = core?.get("remaining")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val lim = core?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                rem to lim
            }
        }.getOrNull()
    }
}
