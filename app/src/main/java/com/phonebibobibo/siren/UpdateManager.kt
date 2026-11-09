package com.phonebibobibo.siren

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    data class Release(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val notes: String
    )

    sealed interface CheckResult {
        data class Available(val release: Release) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open("$RELEASES_API/latest")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return@runCatching CheckResult.Failed("仓库还没有发布版本")
            }
            if (code !in 200..299) {
                val hint = runCatching { JSONObject(body).optString("message") }.getOrNull()
                return@runCatching CheckResult.Failed(
                    if (hint.isNullOrBlank()) "检查失败（HTTP $code）" else "检查失败：$hint"
                )
            }

            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { return@runCatching CheckResult.Failed("版本信息不完整") }
            val remoteVersion = tag.removePrefix("v").removePrefix("V")
            if (!isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
                return@runCatching CheckResult.UpToDate
            }

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkName = name
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                return@runCatching CheckResult.Failed("最新版本没有附带 APK")
            }
            CheckResult.Available(
                Release(
                    version = remoteVersion,
                    tag = tag,
                    apkUrl = apkUrl,
                    apkName = apkName,
                    apkSize = apkSize,
                    notes = json.optString("body").trim()
                )
            )
        }.getOrElse { error ->
            CheckResult.Failed(networkMessage(error))
        }
    }

    suspend fun download(
        release: Release,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, release.apkName.ifBlank { "phonebibobibo-${release.version}.apk" })
        if (target.isFile && target.length() > 0L &&
            (release.apkSize <= 0L || target.length() == release.apkSize)
        ) {
            verifyPackage(target)
            onProgress(1f)
            return@withContext target
        }

        val partial = File(directory, "${target.name}.part")
        partial.delete()
        val connection = open(release.apkUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("下载失败（HTTP $code）")
            }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: release.apkSize
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (release.apkSize > 0L && partial.length() != release.apkSize) {
                throw IOException("下载不完整，请重试")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                throw IOException("无法保存安装包")
            }
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
        verifyPackage(target)
        onProgress(1f)
        target
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun verifyPackage(apk: File) {
        if (!apk.isFile || apk.length() == 0L) throw IOException("安装包为空")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("安装包无法识别")
        if (info.packageName != context.packageName) {
            throw IOException("安装包包名不匹配")
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PhoneSiren/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun networkMessage(error: Throwable): String = when (error) {
        is IOException -> "网络不可用，请稍后重试"
        else -> "检查失败：${error.message ?: "未知错误"}"
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val left = versionParts(remote)
        val right = versionParts(current)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun versionParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    private companion object {
        const val RELEASES_API =
            "https://api.github.com/repos/johnson020202/phonebibobibo/releases"
    }
}
