package com.glassplayer.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * 夸克网盘网页端接口。
 *
 * 官方开放平台只对企业开放，这里走网页端（drive-pc）接口，和 AList / quarkpan 等
 * 第三方客户端同一套：扫码拿 ticket → 用 ticket 换 Cookie → Cookie 调文件接口。
 * 所有请求都必须带 Cookie，下载直链也一样，所以播放器侧要注入同样的请求头。
 */
class QuarkApi(private val context: Context, private val settings: SettingsStore) {

    data class QuarkFile(
        val fid: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val updatedAt: Long,
    )

    data class QrSession(val token: String, val url: String)

    private companion object {
        const val DRIVE = "https://drive-pc.quark.cn/1/clouddrive"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/94.0.4606.71 Safari/537.36 Core/1.94.225.400 QQBrowser/12.2.5544.400"
        const val REFERER = "https://pan.quark.cn/"
        // 官方 PC 客户端 UA。浏览器 UA 会被当成「网页下载」，大文件直接报
        // download file size limit；换成客户端身份才按会员额度放行。
        const val CLIENT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 " +
                "Safari/537.36 Channel/pckk_other_ch"
    }

    val isLoggedIn: Boolean get() = !settings.quarkCookie.isNullOrBlank()

    // ---------- 扫码登录 ----------

    fun requestQrSession(): QrSession {
        val requestId = UUID.randomUUID().toString()
        val body = getRaw(
            "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin" +
                "?client_id=532&v=1.2&request_id=$requestId",
            cookie = null,
        )
        val json = JSONObject(body)
        if (json.optInt("status") != 2000000) {
            throw IllegalError(json.optString("message").ifBlank { "获取二维码失败" })
        }
        val token = json.optJSONObject("data")
            ?.optJSONObject("members")
            ?.optString("token")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalError("二维码 token 为空")
        val url = "https://su.quark.cn/4_eMHBJ" +
            "?token=$token&client_id=532&ssb=weblogin&uc_param_str=" +
            "&uc_biz_str=" + URLEncoder.encode("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0", "UTF-8")
        return QrSession(token, url)
    }

    /** 返回 service_ticket；还在等待扫码时返回 null。 */
    fun pollQrTicket(token: String): String? {
        val requestId = UUID.randomUUID().toString()
        val body = getRaw(
            "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken" +
                "?client_id=532&v=1.2&token=$token&request_id=$requestId",
            cookie = null,
        )
        val json = JSONObject(body)
        val members = json.optJSONObject("data")?.optJSONObject("members")
        val ticket = members?.optString("service_ticket")?.takeIf { it.isNotBlank() }
        if (ticket != null) return ticket
        val status = json.optInt("status")
        val message = json.optString("message")
        return when {
            // 2000000 = 正常等待；50004001 = 还没有扫码记录（也是等待）
            status == 2000000 || status == 50004001 -> null
            status in 50004000..50004999 ->
                throw IllegalError("二维码已过期或失效，请点「重新获取」")
            message.isNotBlank() && message != "ok" -> throw IllegalError(message)
            else -> null
        }
    }

    /** 用 service_ticket 换取网页端 Cookie，成功后写入设置。 */
    fun exchangeCookie(ticket: String): String {
        val url = URL("https://pan.quark.cn/account/info?st=$ticket&lw=scan")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", REFERER)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        val cookies = mutableListOf<String>()
        try {
            val code = conn.responseCode
            conn.headerFields.forEach { (key, values) ->
                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    values?.forEach { raw ->
                        val pair = raw.substringBefore(';').trim()
                        if (pair.contains('=') && !pair.startsWith("=")) {
                            val name = pair.substringBefore('=').trim()
                            if (name.isNotBlank() && cookies.none { it.startsWith("$name=") }) {
                                cookies += pair
                            }
                        }
                    }
                }
            }
            if (code !in 200..399) throw IllegalError("登录换取 Cookie 失败（HTTP $code）")
            runCatching { conn.inputStream?.close() }
        } finally {
            runCatching { conn.disconnect() }
        }
        if (cookies.isEmpty()) throw IllegalError("登录成功但没有拿到 Cookie，请重试")
        val cookie = cookies.joinToString("; ")
        settings.quarkCookie = cookie
        return cookie
    }

    fun fetchUserName() {
        runCatching {
            val body = getRaw("https://pan.quark.cn/account/info", cookie = settings.quarkCookie)
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@runCatching
            val name = listOf("nickname", "nick_name", "nick", "user_name")
                .firstNotNullOfOrNull { key -> data.optString(key).takeIf { it.isNotBlank() } }
            if (name != null) settings.quarkUserName = name
        }
    }

    fun logout() {
        settings.quarkCookie = null
        settings.quarkUserName = null
    }

    // ---------- 文件接口 ----------

    fun list(folderId: String, page: Int = 1, size: Int = 100): Pair<List<QuarkFile>, Boolean> {
        val url = "$DRIVE/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$folderId" +
            "&_page=$page&_size=$size&_fetch_total=1&fetch_all_file=1&fetch_risk_file_name=1" +
            "&_sort=file_type:asc,updated_at:desc"
        val json = JSONObject(getRaw(url, cookie = requireCookie()))
        ensureOk(json)
        val data = json.optJSONObject("data") ?: throw IllegalError("网盘返回为空")
        val list = data.optJSONArray("list")
        val files = ArrayList<QuarkFile>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val fid = item.optString("fid").takeIf { it.isNotBlank() } ?: continue
                files += QuarkFile(
                    fid = fid,
                    name = item.optString("file_name").ifBlank { "未命名" },
                    isDir = item.optInt("dir") == 1 || item.optBoolean("dir"),
                    size = item.optLong("size"),
                    updatedAt = item.optLong("updated_at"),
                )
            }
        }
        val total = data.optJSONObject("metadata")?.optLong("_total") ?: files.size.toLong()
        return files to (page.toLong() * size < total)
    }

    /**
     * 「流播」用的转码地址：官方在线播放走的是 file/v2/play/project 返回的 m3u8，
     * CDN 是播放专用节点，不受下载接口的限速影响。
     */
    fun streamUrl(fid: String): String {
        fun body(platform: Boolean, pdir: Boolean): String {
            val json = JSONObject()
                .put("fid", fid)
                .put("resolutions", "low,normal,high,super,2k,4k")
                .put("supports", "fmp4_av,m3u8,dolby_vision")
            if (platform) json.put("platform", "pc")
            if (pdir) json.put("pdir_fid", "0")
            return json.toString()
        }

        // 服务端回 plf_invalid（平台标识不合法）：补 platform 字段、换参数组合各试一次
        data class Attempt(val query: String, val body: String, val ua: String)

        val base = "https://drive.quark.cn/1/clouddrive"
        val plain = "pr=ucpro&fr=pc"
        val attempts = listOf(
            Attempt(plain, body(platform = true, pdir = false), CLIENT_UA),
            Attempt(plain, body(platform = false, pdir = false), CLIENT_UA),
            Attempt(plain, body(platform = true, pdir = true), CLIENT_UA),
            Attempt("pr=ucpro&fr=pc&uc_param_str=", body(platform = true, pdir = false), CLIENT_UA),
            Attempt(
                "pr=ucpro&fr=pc&sys=win32&ve=2.5.20&uc_param_str=",
                body(platform = true, pdir = false),
                CLIENT_UA,
            ),
            Attempt(plain, body(platform = true, pdir = false), UA),
        )

        var lastError: Exception = IllegalError("没有拿到转码播放地址")
        for (attempt in attempts) {
            val result = runCatching {
                val raw = postRaw(
                    "$base/file/v2/play/project?${attempt.query}",
                    cookie = requireCookie(),
                    json = attempt.body,
                    ua = attempt.ua,
                )
                val json = JSONObject(raw)
                ensureOk(json)
                val data = json.optJSONObject("data") ?: throw IllegalError("没有拿到转码播放地址")
                val list = data.optJSONArray("video_list") ?: throw IllegalError("没有拿到转码播放地址")
                if (list.length() == 0) throw IllegalError("文件还在转码，稍等一下再试")
                for (i in 0 until list.length()) {
                    val url = list.optJSONObject(i)
                        ?.optJSONObject("video_info")
                        ?.optString("url").orEmpty()
                    if (url.isNotBlank()) return@runCatching url
                }
                throw IllegalError("转码播放地址为空，这个文件可能不支持在线转码")
            }
            result.getOrNull()?.let {
                DebugLog.record("quark", "stream url ok · ${attempt.query}")
                return it
            }
            lastError = result.exceptionOrNull() as? Exception ?: lastError
            DebugLog.record(
                "quark",
                "stream url fail · ${attempt.query} · platform=${attempt.body.contains("platform")} · ${lastError.message}",
            )
        }
        throw lastError
    }

    fun downloadUrl(fid: String): String {
        // drive-pc 与 drive 两套域名都可能被灰度，任一个 400 时换另一个再试
        val bases = listOf("https://drive.quark.cn/1/clouddrive", DRIVE)
        val uas = listOf(CLIENT_UA, UA)
        var lastError: Exception? = null
        for (base in bases) for (ua in uas) {
            val result = runCatching {
                val body = postRaw(
                    "$base/file/download?pr=ucpro&fr=pc&uc_param_str=",
                    cookie = requireCookie(),
                    json = JSONObject().put("fids", org.json.JSONArray().put(fid)).toString(),
                    ua = ua,
                )
                val json = JSONObject(body)
                ensureOk(json)
                val array = json.optJSONArray("data") ?: throw IllegalError("没有拿到下载链接")
                val first = array.optJSONObject(0) ?: throw IllegalError("没有拿到下载链接")
                first.optString("download_url").takeIf { it.isNotBlank() }
                    ?: throw IllegalError("下载链接为空，文件可能已被删除")
            }
            val urlPeek = "${base.substringAfter("//").substringBefore('/')} ua=${if (ua == CLIENT_UA) "client" else "browser"}"
            result.getOrNull()?.let {
                DebugLog.record("quark", "download url ok · $urlPeek")
                return it
            }
            DebugLog.record(
                "quark",
                "download url fail · $urlPeek · ${result.exceptionOrNull()?.message}",
            )
            lastError = result.exceptionOrNull() as? Exception
        }
        throw lastError ?: IllegalError("没有拿到下载链接")
    }

    // ---------- 底层 ----------

    private fun mergeResponseCookies(conn: HttpURLConnection) {
        val current = settings.quarkCookie.orEmpty()
        val map = LinkedHashMap<String, String>()
        current.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            map[it.substringBefore('=')] = it.substringAfter('=')
        }
        conn.headerFields.forEach { (key, values) ->
            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { raw ->
                    val pair = raw.substringBefore(';').trim()
                    if (pair.contains('=') && !pair.startsWith("=")) {
                        map[pair.substringBefore('=')] = pair.substringAfter('=')
                    }
                }
            }
        }
        val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (merged.isNotBlank() && merged != settings.quarkCookie) {
            settings.quarkCookie = merged
            DebugLog.record("quark", "cookie refreshed (${map.size} entries)")
        }
    }

    /** 解析夸克分享链接里的 pwd_id。 */
    fun sharePwdId(url: String): String? =
        Regex("pan\\.quark\\.cn/s/([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)

    data class ShareEntry(
        val fid: String,
        val token: String,
        val name: String,
        val size: Long,
        val isDir: Boolean,
    )

    /** 用分享链接换 (pwd_id, stoken)；提取码为空时从链接的 pwd 参数补。 */
    fun shareSession(url: String, passcode: String): Pair<String, String> {
        val cookie = settings.quarkCookie?.takeIf { it.isNotBlank() }
        val pwdId = sharePwdId(url) ?: throw IllegalError("不是有效的夸克分享链接")
        val pwd = passcode.ifBlank {
            runCatching { Uri.parse(url).getQueryParameter("pwd") }.getOrNull().orEmpty()
        }
        val shareReferer = "https://pan.quark.cn/s/$pwdId"
        val tokenUrl = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/token" +
            "?pr=ucpro&fr=pc&uc_param_str="
        val tokenJson = JSONObject(
            postRaw(
                tokenUrl,
                cookie,
                JSONObject().put("pwd_id", pwdId).put("passcode", pwd).toString(),
                referer = shareReferer,
            ),
        )
        ensureOk(tokenJson)
        val stoken = tokenJson.optJSONObject("data")?.optString("stoken").orEmpty()
        if (stoken.isBlank()) throw IllegalError("分享链接已失效或提取码错误")
        return pwdId to stoken
    }

    /**
     * 预览分享目录（不转存）：pdirFid=0 为分享根目录，传子目录 fid 可继续下钻。
     * @param session 已换好的 (pwd_id, stoken)；递归遍历同一分享时传进来，避免每层都重新换 token。
     */
    fun shareEntries(
        url: String,
        passcode: String,
        pdirFid: String = "0",
        session: Pair<String, String>? = null,
    ): List<ShareEntry> {
        val cookie = settings.quarkCookie?.takeIf { it.isNotBlank() }
        val (pwdId, stoken) = session ?: shareSession(url, passcode)
        val shareReferer = "https://pan.quark.cn/s/$pwdId"

        val entries = ArrayList<ShareEntry>()
        var page = 1
        while (true) {
            val detailUrl = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/detail" +
                "?pr=ucpro&fr=pc&uc_param_str=&pwd_id=$pwdId" +
                "&stoken=${URLEncoder.encode(stoken, "UTF-8")}" +
                "&pdir_fid=$pdirFid&_page=$page&_size=100&_fetch_total=1" +
                "&_fetch_banner=1&_fetch_share=1&_sort=file_type:asc,file_name:asc"
            val detailJson = JSONObject(getRaw(detailUrl, cookie, referer = shareReferer))
            ensureOk(detailJson)
            val list = detailJson.optJSONObject("data")?.optJSONArray("list") ?: break
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val fid = item.optString("fid").takeIf { it.isNotBlank() } ?: continue
                entries += ShareEntry(
                    fid = fid,
                    token = item.optString("share_fid_token"),
                    name = item.optString("file_name").ifBlank { "未命名" },
                    size = item.optLong("size"),
                    isDir = item.optInt("dir") == 1 || item.optBoolean("dir") ||
                        item.optString("file_type") == "folder",
                )
            }
            if (list.length() < 100) break
            page++
        }
        return entries
    }

    /**
     * 把公开分享转存到自己的网盘根目录。
     * 流程：sharepage/token 换 stoken → detail 拿 fid/token → save 建任务 → 轮询 task。
     * @return 转存的文件/文件夹数量
     */
    fun saveShare(url: String, passcode: String): Int {
        requireCookie()
        val entries = shareEntries(url, passcode)
        if (entries.isEmpty()) throw IllegalError("分享里没有可转存的文件")
        return saveShareItems(url, passcode, entries)
    }

    /**
     * 只转存指定条目（文件夹盘「同步新增」用）。
     * 条目来自分享 detail 的 fid + share_fid_token，可以跨目录混在一批里提交。
     * @return 提交成功的条目数
     */
    fun saveShareItems(url: String, passcode: String, items: List<ShareEntry>): Int {
        requireCookie()
        val saveable = items.filter { it.fid.isNotBlank() && it.token.isNotBlank() }
        if (saveable.isEmpty()) return 0
        val (pwdId, stoken) = shareSession(url, passcode)
        val shareReferer = "https://pan.quark.cn/s/$pwdId"
        val fids = saveable.map { it.fid }
        val tokens = saveable.map { it.token }

        val saveUrl = "$DRIVE/share/sharepage/save?pr=ucpro&fr=pc&uc_param_str="
        val body = JSONObject()
            .put("fid_list", JSONArray(fids))
            .put("fid_token_list", JSONArray(tokens))
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("to_pdir_fid", "0")
            .put("scene", "link")
        val saveJson = JSONObject(postRaw(saveUrl, requireCookie(), body.toString(), referer = shareReferer))
        ensureOk(saveJson)
        val taskId = saveJson.optJSONObject("data")?.optString("task_id").orEmpty()
        if (taskId.isBlank()) throw IllegalError("转存任务创建失败")

        repeat(30) {
            Thread.sleep(1000)
            val taskUrl = "$DRIVE/task?pr=ucpro&fr=pc&uc_param_str=&task_id=$taskId"
            val taskJson = JSONObject(getRaw(taskUrl, requireCookie(), referer = shareReferer))
            ensureOk(taskJson)
            when (taskJson.optJSONObject("data")?.optInt("status", 1)) {
                2 -> return saveable.size
                3 -> throw IllegalError("转存失败：分享可能已失效")
            }
        }
        throw IllegalError("转存超时，稍后到网盘刷新看看")
    }

    private fun requireCookie(): String =
        settings.quarkCookie?.takeIf { it.isNotBlank() } ?: throw IllegalError("还没有登录夸克网盘")

    private fun ensureOk(json: JSONObject) {
        val status = json.optInt("status", 200)
        if (status != 200 || json.optInt("code", 0) != 0) {
            val message = json.optString("message").ifBlank { "请求失败（$status）" }
            if (status == 401 || json.optInt("code") == 31001) {
                throw IllegalError("登录已过期，请重新扫码登录")
            }
            throw IllegalError(message)
        }
    }

    private fun getRaw(
        url: String,
        cookie: String?,
        ua: String = UA,
        referer: String = REFERER,
    ): String = request("GET", url, cookie, null, ua, referer)

    private fun postRaw(
        url: String,
        cookie: String?,
        json: String,
        ua: String = UA,
        referer: String = REFERER,
    ): String = request("POST", url, cookie, json, ua, referer)

    private fun request(
        method: String,
        url: String,
        cookie: String?,
        body: String?,
        ua: String = UA,
        referer: String = REFERER,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Referer", referer)
            setRequestProperty("Origin", "https://pan.quark.cn")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            DebugLog.record("quark", "$method ${url.substringBefore('?')} → HTTP $code")
            if (url.contains("drive.quark.cn") || url.contains("drive-pc.quark.cn") ||
                url.contains("/account/")
            ) {
                mergeResponseCookies(conn)
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toString("UTF-8")
            } ?: ""
            if (code !in 200..299) {
                if (code == 401 || code == 403) throw IllegalError("登录已过期，请重新扫码登录")
                val detail = runCatching { JSONObject(text).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() && it != "ok" }
                val friendly = when {
                    detail == null -> "网盘请求失败（HTTP $code）"
                    detail.contains("size limit", ignoreCase = true) ->
                        "这个文件超过网盘允许的下载体积（会员额度没有生效，可能被按网页端限流）"
                    else -> "网盘请求失败（HTTP $code）：$detail"
                }
                throw IllegalError(friendly)
            }
            return text
        } catch (e: IllegalError) {
            throw e
        } catch (_: Exception) {
            throw IllegalError("网络请求失败，请检查网络后重试")
        } finally {
            // 以前只 close 流不 disconnect，连接会留在 keep-alive 池里，
            // 一轮整季解析下来能积几十条，弱网时更容易卡住
            runCatching { conn.disconnect() }
        }
    }
}

/** 可展示给用户看的错误。 */
class IllegalError(message: String) : Exception(message)
