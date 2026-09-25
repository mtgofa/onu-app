package com.hg8145v5.manager.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A new version announced on GitHub Releases. */
data class AppRelease(
    val versionCode: Int,
    val tagName: String,
    val downloadUrl: String,
    val sha256: String?,
)

/**
 * Auto-update via GitHub Releases "latest" (works with a PUBLIC repo, no token needed).
 *
 * The release must follow this contract:
 *   name      -> "ONU Manager 2.6 (code 8)"        (lower/upper-case insensitive)
 *   tag_name  -> "2.6"
 *   assets    -> contains one *.apk (browser_download_url)
 *   body      -> optional line "sha256: <64-hex>"  (verified when present)
 */
class UpdateCheck(
    private val owner: String,
    private val repo: String,
    private val currentCode: Int,
) {
    sealed interface Result {
        data object NotConfigured : Result
        data object UpToDate : Result
        data class Available(val rel: AppRelease) : Result
        data class Failed(val reason: String) : Result
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun configured() = owner.isNotBlank() && repo.isNotBlank()

    fun fetch(): Result {
        if (!configured()) return Result.NotConfigured
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ONU-Manager-HG8145V5")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) Result.Failed("no releases yet")
                else if (!resp.isSuccessful) Result.Failed("HTTP ${resp.code}")
                else parse(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "network error")
        }
    }

    private fun parse(json: String): Result {
        if (json.isBlank()) return Result.Failed("empty response")
        val o = try { JSONObject(json) } catch (e: Exception) { return Result.Failed("bad JSON") }
        val name = o.optString("name")
        val code = Regex("""code\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return Result.Failed("no \"code N\" in release name")
        if (code <= currentCode) return Result.UpToDate
        val assets = o.optJSONArray("assets")
        var url = ""
        if (assets != null) for (i in 0 until assets.length()) {
            val u = assets.getJSONObject(i).optString("browser_download_url")
            if (u.endsWith(".apk")) { url = u; break }
        }
        if (url.isBlank()) return Result.Failed("no apk asset in release")
        val body = o.optString("body", "")
        val sha = Regex("""sha256[:=]\s*([0-9a-fA-F]{64})""").find(body)?.groupValues?.get(1)?.lowercase()
        return Result.Available(AppRelease(code, o.optString("tag_name"), url, sha))
    }

    /** Download the release APK into [dir], verify SHA-256 when known; null on any failure. */
    fun download(rel: AppRelease, dir: File): File? = try {
        dir.mkdirs()
        val file = File(dir, "update.apk")
        val bytes = client.newCall(Request.Builder().url(rel.downloadUrl).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes() ?: return null
            }
        rel.sha256?.let { expected ->
            val got = sha256(bytes)
            if (got != expected) return null
        }
        file.writeBytes(bytes)
        file
    } catch (e: Exception) { null }

    private fun sha256(b: ByteArray): String =
        BigInteger(1, MessageDigest.getInstance("SHA-256").digest(b))
            .toString(16).padStart(64, '0')
}