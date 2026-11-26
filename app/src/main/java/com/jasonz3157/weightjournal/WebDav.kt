package com.jasonz3157.weightjournal

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SYNC_FILE = "weight-journal.json"
private val strongEtag = Regex("\"[\\x21\\x23-\\x7E]*\"")
private val nutstoreBareEtag = Regex("[A-Za-z0-9_+.:=/\\-]+")
private val json = "application/json".toMediaType()
private val xml = "application/xml".toMediaType()
private val lockBody = """
    <?xml version="1.0" encoding="utf-8"?>
    <lockinfo xmlns="DAV:">
        <lockscope><exclusive /></lockscope>
        <locktype><write /></locktype>
    </lockinfo>
""".trimIndent().toRequestBody(xml)
private val etagProperty = """<propfind xmlns="DAV:"><prop><getetag/></prop></propfind>""".toRequestBody(xml)
private const val MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024

internal data class DavSettings(val address: HttpUrl, val username: String, val password: String) {
    val isNutstore get() = address.host == "dav.jianguoyun.com" && address.port == 443
    val fileUrl: HttpUrl
        get() {
            if (address.encodedPath.endsWith(".json", ignoreCase = true)) return address
            val path = address.encodedPath.trimEnd('/')
            return address.newBuilder().encodedPath("$path/$SYNC_FILE").build()
        }

    companion object {
        fun validate(address: String, username: String, password: String): DavSettings {
            val input = address.trim()
            val url = input.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.encodedUsername.isEmpty() &&
                url.encodedPassword.isEmpty() && url.encodedQuery == null && url.encodedFragment == null &&
                '@' !in input.substringAfter("://").substringBefore('/')) {
                "请输入 HTTPS WebDAV 地址，用户名和密码请单独填写。"
            }
            require(username.isNotBlank() && username.none { it == ':' || it == '\n' || it == '\r' }) {
                "请输入有效的 WebDAV 用户名。"
            }
            require(password.isNotEmpty()) { "请输入 WebDAV 密码。" }
            return DavSettings(url, username, password)
        }
    }
}

internal class DavException(message: String, cause: Throwable? = null) : IOException(message, cause)

private data class FileLock(val token: String, val created: Boolean)
private data class DavSnapshot(val journal: Journal?, val exists: Boolean, val etag: String?)
private class LockUnavailable : IOException()

internal class WebDavClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun exchange(settings: DavSettings, local: () -> Journal): Journal {
        val request = Request.Builder().url(settings.fileUrl)
            .header("Cache-Control", "no-cache, no-store")
            .header("Authorization", Credentials.basic(settings.username, settings.password, Charsets.UTF_8))
            .build()
        if (!settings.isNutstore) return exchangeWithEtag(request, local)
        val initial = getSnapshot(request, nutstore = true)
        val remote = initial.journal
        if (remote != null && remote.merge(local()) === remote) return remote
        if (initial.etag != null) return exchangeWithEtag(request, local, nutstore = true, initial = initial)
        return try {
            exchangeWithLock(request, local)
        } catch (_: LockUnavailable) {
            exchangeWithEtag(request, local, nutstore = true)
        }
    }

    private fun exchangeWithEtag(
        request: Request,
        local: () -> Journal,
        nutstore: Boolean = false,
        initial: DavSnapshot? = null,
    ): Journal {
        repeat(4) { attempt ->
            var snapshot = initial.takeIf { attempt == 0 } ?: getSnapshot(request, nutstore)
            var remote = snapshot.journal ?: Journal()
            var merged = remote.merge(local())
            if (snapshot.journal != null && merged === remote) return remote

            if (snapshot.exists && snapshot.etag == null && nutstore) {
                snapshot = getSnapshotWithDiscoveredEtag(request) ?: return@repeat
                remote = snapshot.journal ?: Journal()
                merged = remote.merge(local())
                if (snapshot.journal != null && merged === remote) return remote
            }

            val (name, value) = when {
                !snapshot.exists -> "If-None-Match" to "*"
                else -> "If-Match" to (snapshot.etag
                    ?: throw DavException("WebDAV 服务器需提供强 ETag 才能安全写入。"))
            }
            val put = request.newBuilder().header(name, value)
                .put(merged.toJson().toRequestBody(json)).build()
            http.newCall(put).execute().use { response ->
                if (response.isSuccessful) return merged
                if (response.code != 412) throw responseError(response)
            }
        }
        throw DavException("同步文件正在被其他设备更新，请稍后重试。")
    }

    private fun getSnapshot(request: Request, nutstore: Boolean): DavSnapshot =
        http.newCall(request).execute().use { response -> response.versionedSnapshot(nutstore) }

    private fun Response.versionedSnapshot(nutstore: Boolean): DavSnapshot =
        DavSnapshot(
            journal = readSnapshot(this, allowEmpty = nutstore),
            exists = code != 404,
            etag = if (code == 404) null else usableEtag(header("ETag"), nutstore),
        )

    private fun getSnapshotWithDiscoveredEtag(request: Request): DavSnapshot? {
        val etag = discoverEtag(request)
            ?: throw DavException("坚果云未提供可用于安全写入的文件锁或 ETag。本地记录已保留。")
        val conditionalRead = request.newBuilder().header("If-Match", etag).build()
        val snapshot = http.newCall(conditionalRead).execute().use { response ->
            if (response.code == 404 || response.code == 412) null else response.versionedSnapshot(nutstore = true)
        } ?: return null
        val currentEtag = snapshot.etag ?: discoverEtag(request)
        return snapshot.copy(etag = etag).takeIf { currentEtag == etag }
    }

    private fun discoverEtag(request: Request): String? {
        val head = request.newBuilder().head().build()
        http.newCall(head).execute().use { response ->
            if (response.isSuccessful) usableEtag(response.header("ETag"), nutstore = true)?.let { return it }
            else if (response.code !in setOf(403, 404, 405, 501)) throw responseError(response)
        }
        val propfind = request.newBuilder().header("Depth", "0").method("PROPFIND", etagProperty).build()
        return http.newCall(propfind).execute().use { response ->
            if (response.code == 207) usableEtag(response.readDavPropertyEtag(), nutstore = true) else null
        }
    }

    private fun usableEtag(value: String?, nutstore: Boolean): String? {
        val tag = value?.trim() ?: return null
        return tag.takeIf {
            strongEtag.matches(it) ||
                (nutstore && !it.startsWith("W/", ignoreCase = true) && nutstoreBareEtag.matches(it))
        }
    }

    private fun exchangeWithLock(request: Request, local: () -> Journal): Journal {
        val lockRequest = request.newBuilder()
            .header("Depth", "0")
            .header("Timeout", "Second-60")
            .method("LOCK", lockBody)
            .build()

        repeat(4) { attempt ->
            val lock = acquireLock(lockRequest)
            if (lock != null) {
                try {
                    val snapshot = if (lock.created) null else http.newCall(request).execute().use { response ->
                        if (response.code == 404) throw DavException("锁定后的同步文件找不到，请重新同步。")
                        readSnapshot(response, allowEmpty = true)
                    }
                    val remote = snapshot ?: Journal()
                    val merged = remote.merge(local())
                    if (snapshot != null && merged === remote) return remote

                    val put = request.newBuilder().header("If", "(${lock.token})")
                        .put(merged.toJson().toRequestBody(json)).build()
                    http.newCall(put).execute().use { response ->
                        if (response.isSuccessful) return merged
                        if (response.code != 412 && response.code != 423) throw responseError(response)
                    }
                } finally {
                    releaseLock(request, lock.token)
                }
            }
            if (attempt < 3) Thread.sleep(200L * (attempt + 1))
        }
        throw DavException("坚果云同步文件正在被其他设备更新，请稍后重试。")
    }

    private fun acquireLock(request: Request): FileLock? = http.newCall(request).execute().use { response ->
        if (response.code == 423) return@use null
        if (response.code == 405 || response.code == 501) throw LockUnavailable()
        if (response.code != 200 && response.code != 201) throw responseError(response)
        val token = response.readDavLockToken() ?: throw LockUnavailable()
        FileLock(token, created = response.code == 201)
    }

    private fun releaseLock(request: Request, token: String) {
        val unlock = request.newBuilder().header("Lock-Token", token).method("UNLOCK", null).build()
        try {
            http.newCall(unlock).execute().close()
        } catch (_: IOException) {
            // 连接中断时, 服务器会在文件锁超时后释放它.
        }
    }

    private fun readSnapshot(response: Response, allowEmpty: Boolean = false): Journal? {
        if (response.code == 404) return null
        if (!response.isSuccessful) throw responseError(response)
        val body = response.body ?: throw DavException("无法读取 WebDAV 同步文件。")
        val source = body.source()
        if (allowEmpty && !source.request(1)) return null
        if (source.request(MAX_SNAPSHOT_BYTES + 1)) throw DavException("WebDAV 同步文件过大。")
        return try {
            Journal.parse(source.readUtf8())
        } catch (error: JSONException) {
            throw DavException("WebDAV 同步文件格式不正确，请确认所填地址。", error)
        } catch (error: IllegalArgumentException) {
            throw DavException("WebDAV 同步文件格式不正确，请确认所填地址。", error)
        }
    }

    private fun responseError(response: Response): DavException {
        val action = when (response.request.method) {
            "GET" -> "读取文件"
            "PUT" -> "写入文件"
            "LOCK" -> "锁定文件"
            else -> "同步"
        }
        val message = when (response.code) {
            400 -> "WebDAV 请求无效，请检查地址。"
            401 -> "WebDAV 登录失败，请检查用户名和密码。"
            403 -> "WebDAV 拒绝${action}（HTTP 403），请检查文件权限。"
            404, 409 -> "找不到 WebDAV 目录或文件，请检查地址。"
            405, 501 -> "WebDAV 服务器不支持${action}（HTTP ${response.code}）。"
            413 -> "同步文件超出了服务器允许的大小。"
            423 -> "WebDAV 同步文件已锁定，请稍后重试。"
            429 -> "WebDAV 请求过于频繁，请稍后重试。"
            507 -> "WebDAV 存储空间不足。"
            else -> "WebDAV ${action}失败（HTTP ${response.code}）。"
        }
        return DavException(message)
    }
}
