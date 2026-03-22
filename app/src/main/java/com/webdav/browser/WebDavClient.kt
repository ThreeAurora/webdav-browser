package com.webdav.browser

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder

data class DavItem(
    val name: String, val href: String, val isDir: Boolean,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isMedia get() = isImage || isVideo
}

data class TrashItem(
    val name: String,           // 显示名（去掉时间戳前缀）
    val trashHref: String,      // 回收站中的路径
    val originalPath: String,   // 原始路径
    val size: Long = 0,
    val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
}

class WebDavClient(private val baseUrl: String, user: String, pass: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            if (user.isNotBlank()) authenticator { _, resp ->
                resp.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
            }
        }.build()

    // ===== 基础 WebDAV 操作 =====

    fun listDir(path: String): List<DavItem> {
        val url = buildRawUrl(path)
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
            <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
            </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parse(xml, path).filter { !it.name.startsWith(".webdav_trash") }
    }

    fun permanentDelete(path: String): Boolean {
        val url = buildRawUrl(path)
        return client.newCall(Request.Builder().url(url).delete().build()).execute().isSuccessful
    }

    fun move(fromPath: String, toPath: String): Boolean {
        val fromUrl = buildRawUrl(fromPath)
        val destUrl = buildEncodedUrl(toPath)
        val req = Request.Builder().url(fromUrl)
            .method("MOVE", null)
            .header("Destination", destUrl)
            .header("Overwrite", "F")
            .build()
        return client.newCall(req).execute().isSuccessful
    }

    fun mkdir(path: String): Boolean {
        val url = buildRawUrl(path)
        val resp = client.newCall(Request.Builder().url(url).method("MKCOL", null).build()).execute()
        return resp.isSuccessful || resp.code == 405 || resp.code == 301
    }

    fun mkdirs(path: String) {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        var cur = ""
        for (part in parts) {
            cur += "/$part"
            mkdir("$cur/")
        }
    }

    fun putText(path: String, content: String): Boolean {
        val url = buildRawUrl(path)
        val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        return client.newCall(Request.Builder().url(url).put(body).build()).execute().isSuccessful
    }

    fun getText(path: String): String? {
        val url = buildRawUrl(path)
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    fun exists(path: String): Boolean {
        val url = buildRawUrl(path)
        return try {
            client.newCall(Request.Builder().url(url).head().build()).execute().isSuccessful
        } catch (_: Exception) { false }
    }

    fun fileUrl(path: String) = buildRawUrl(path)
    fun getClient() = client

    // ===== 回收站核心逻辑 =====

    /** 获取路径所在的盘根目录，如 /D盘/照片/a.jpg → /D盘 */
    private fun getDriveRoot(path: String): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) "/${parts[0]}" else ""
    }

    /** 获取回收站路径 */
    fun getTrashDir(path: String): String {
        val root = getDriveRoot(path)
        return "$root/.webdav_trash"
    }

    /** 移到回收站（而非永久删除） */
    fun moveToTrash(filePath: String): Boolean {
        val trashDir = getTrashDir(filePath)

        // 创建回收站目录
        mkdir("$trashDir/")

        // 用时间戳保证不重名
        val fileName = filePath.trimEnd('/').substringAfterLast('/')
        val timestamp = System.currentTimeMillis()
        val trashName = "${timestamp}_${fileName}"
        val trashFilePath = "$trashDir/$trashName"

        // 先写 trashinfo（记录原始路径）
        putText("$trashFilePath.trashinfo", filePath)

        // 移动文件到回收站
        val success = move(filePath, trashFilePath)
        if (!success) {
            // 移动失败，清理 trashinfo
            permanentDelete("$trashFilePath.trashinfo")
        }
        return success
    }

    /** 从回收站还原 */
    fun restoreFromTrash(trashFilePath: String): Pair<Boolean, String> {
        // 读取原始路径
        val originalPath = getText("$trashFilePath.trashinfo")?.trim()
            ?: return Pair(false, "找不到原始路径信息")

        // 确保原始目录存在
        val dir = originalPath.substringBeforeLast('/')
        if (dir.isNotBlank()) mkdirs("$dir/")

        // 处理重名
        var targetPath = originalPath
        if (exists(targetPath)) {
            val dirPart = originalPath.substringBeforeLast('/')
            val fullName = originalPath.substringAfterLast('/')
            val dotIdx = fullName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) fullName.substring(0, dotIdx) else fullName
            val ext = if (dotIdx > 0) fullName.substring(dotIdx) else ""
            var counter = 1
            while (exists(targetPath)) {
                targetPath = "$dirPart/${baseName}_${counter}${ext}"
                counter++
                if (counter > 1000) return Pair(false, "重名文件过多")
            }
        }

        // 移回原位
        val success = move(trashFilePath, targetPath)
        if (success) {
            permanentDelete("$trashFilePath.trashinfo")
        }
        return Pair(success, if (success) "已还原到 $targetPath" else "还原失败")
    }

    /** 列出回收站内容 */
    fun listTrash(currentPath: String): List<TrashItem> {
        val trashDir = getTrashDir(currentPath)
        val url = buildRawUrl("$trashDir/")
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
            <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
            </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()
        val resp = try { client.newCall(req).execute() } catch (_: Exception) { return emptyList() }
        if (!resp.isSuccessful) return emptyList()
        val xml = resp.body?.string() ?: return emptyList()

        val allItems = parse(xml, "$trashDir/")
        // 过滤掉 .trashinfo 文件，只保留实际文件
        val dataFiles = allItems.filter { !it.name.endsWith(".trashinfo") && !it.isDir }

        return dataFiles.map { item ->
            // 去掉时间戳前缀：1234567890_filename.jpg → filename.jpg
            val displayName = item.name.substringAfter('_', item.name)
            // 尝试读 trashinfo 获取原始路径（异步加载时可以先留空）
            val infoPath = "${item.href}.trashinfo"
            val originalPath = try { getText(infoPath)?.trim() ?: "未知" } catch (_: Exception) { "未知" }
            TrashItem(displayName, item.href, originalPath, item.size, item.date)
        }
    }

    /** 清空某个盘的回收站 */
    fun emptyTrash(currentPath: String): Boolean {
        val trashDir = getTrashDir(currentPath)
        return permanentDelete("$trashDir/")
    }

    // ===== URL 构建 =====

    private fun buildRawUrl(path: String): String {
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** 对路径进行编码（用于 Destination 头） */
    private fun buildEncodedUrl(path: String): String {
        val segments = path.trimStart('/').split('/').map { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return baseUrl.trimEnd('/') + "/" + segments.joinToString("/")
    }

    // ===== XML 解析 =====

    private fun parse(xml: String, curPath: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val p = factory.newPullParser()
        p.setInput(StringReader(xml))
        var href = ""; var sz = 0L; var dt = ""; var isCol = false; var inR = false; var tag = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.lowercase()
                    if (tag == "response") { inR = true; href = ""; sz = 0; dt = ""; isCol = false }
                    if (tag == "collection") isCol = true
                }
                XmlPullParser.TEXT -> {
                    val text = p.text?.trim() ?: ""
                    if (inR && text.isNotEmpty()) when (tag) {
                        "href" -> href = text
                        "getcontentlength" -> sz = text.toLongOrNull() ?: 0
                        "getlastmodified" -> dt = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "response" && inR) {
                        inR = false
                        val dec = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                        val isSelf = dec.trimEnd('/') == curPath.trimEnd('/') ||
                            dec.trimEnd('/') == ("/" + curPath.trim('/')).trimEnd('/') ||
                            dec.trimEnd('/').isEmpty() && curPath.trim('/').isEmpty()
                        if (!isSelf && href.isNotEmpty()) {
                            val name = dec.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) items.add(DavItem(name, dec, isCol, sz, dt))
                        }
                    }; tag = ""
                }
            }; p.next()
        }; return items
    }
}
