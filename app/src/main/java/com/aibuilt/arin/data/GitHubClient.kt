package com.aibuilt.arin.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ProviderException(val code: Int, message: String) : Exception(message)

sealed class UserCheck {
    data class Ok(val login: String, val fineGrainedHint: Boolean) : UserCheck()
    data class Invalid(val message: String) : UserCheck()
}

data class RepoResult(val existed: Boolean, val fullName: String)

data class UploadOutcome(val path: String, val committedSha: String?, val attempts: Int)

/**
 * One file to upload. [localPath] points at a file on disk whose bytes are
 * read and base64-encoded; if [localPath] is null, [bytes] is used directly.
 */
data class FileUpload(
    val remotePath: String,
    val localPath: String? = null,
    val bytes: ByteArray? = null
) {
    fun contentBytes(): ByteArray = when {
        localPath != null -> File(localPath).readBytes()
        bytes != null -> bytes
        else -> ByteArray(0)
    }
}

@Serializable
data class PutContentsResponse(
    val commit: CommitRef? = null
) {
    @Serializable
    data class CommitRef(val sha: String? = null)
}

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

    private fun <T> call(request: Request, parse: (code: Int, body: String) -> T): T {
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

    /**
     * Uploads each file via the GitHub Contents API:
     * PUT /repos/{owner}/{repo}/contents/{path} with base64 content and the
     * message "Add files via Arin". Files are committed strictly one after
     * another — one PUT per file, no git/trees batching — and every PUT is
     * retried up to 3 attempts (2 s delay) on transient 422/502 responses.
     */
    suspend fun uploadFiles(
        token: String,
        owner: String,
        repo: String,
        branch: String = "main",
        files: List<FileUpload>,
        onProgress: suspend (index: Int, total: Int, path: String) -> Unit = { _, _, _ -> }
    ): List<UploadOutcome> = withContext(Dispatchers.IO) {
        val results = mutableListOf<UploadOutcome>()
        files.forEachIndexed { index, upload ->
            onProgress(index, files.size, upload.remotePath)
            val encoded = Base64.encodeToString(upload.contentBytes(), Base64.NO_WRAP)
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val payload = buildJsonObject {
                        put("message", "Add files via Arin")
                        put("branch", branch)
                        put("content", encoded)
                    }
                    val url = "https://api.github.com/repos/$owner/$repo/contents/${upload.remotePath.trimStart('/')}"
                    val outcome = call(req(url, method = "PUT", token = token, body = payload.toString())) { _, body ->
                        val parsed = runCatching {
                            json.decodeFromString(PutContentsResponse.serializer(), body)
                        }.getOrNull()
                        UploadOutcome(upload.remotePath, parsed?.commit?.sha, attempt)
                    }
                    results.add(outcome)
                    break
                } catch (e: ProviderException) {
                    val transient = e.code == 422 || e.code == 502
                    if (transient && attempt < MAX_PUT_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                    } else {
                        throw ProviderException(
                            e.code,
                            "Uploading '${upload.remotePath}' failed after $attempt attempt(s): " +
                                "GitHub HTTP ${e.code}${e.message?.let { " — $it" } ?: ""}"
                        )
                    }
                }
            }
        }
        results
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

    companion object {
        const val MAX_PUT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
