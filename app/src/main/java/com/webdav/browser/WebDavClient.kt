package com.webdav.browser

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder

data class DavItem(
    val name: String, val href: String, val isDir: Boolean,
    val size: Long = 0, val date: String = ""
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = ext in setOf("jpg","jpeg","png","gif","bmp","webp","svg","avif","jxl","tiff","ico")
    val isVideo get() = ext in setOf("mp4","mkv","avi","mov","webm","m4v","ts","flv","3gp","wmv")
    val isMedia get() = isImage || isVideo
}

class WebDavClient(private val baseUrl: String, user: String, pass: String) {
    private val client = OkHttpClient.Builder().apply {
        if (user.isNotBlank()) authenticator { _, resp ->
            resp.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
        }
    }.build()

    fun listDir(path: String): List<DavItem> {
        val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val body = """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop>
            <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
            </D:prop></D:propfind>""".toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()
        val xml = client.newCall(req).execute().body?.string() ?: return emptyList()
        return parse(xml, path)
    }

    fun delete(path: String): Boolean {
        val url = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        return client.newCall(Request.Builder().url(url).delete().build()).execute().isSuccessful
    }

    fun fileUrl(path: String) = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    fun getClient() = client

    private fun parse(xml: String, curPath: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        var href = ""; var sz = 0L; var dt = ""; var isCol = false; var inR = false; var tag = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.lowercase()
                    if (tag == "response") { inR = true; href = ""; sz = 0; dt = ""; isCol = false }
                    if (tag == "collection") isCol = true
                }
                XmlPullParser.TEXT -> if (inR) when (tag) {
                    "href" -> href = p.text?.trim() ?: ""
                    "getcontentlength" -> sz = p.text?.trim()?.toLongOrNull() ?: 0
                    "getlastmodified" -> dt = p.text?.trim() ?: ""
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "response" && inR) {
                        inR = false
                        val dec = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                        val nc = curPath.trimEnd('/'); val nh = dec.trimEnd('/')
                        if (nh != nc && dec != curPath && nh.isNotEmpty()) {
                            val n = dec.trimEnd('/').substringAfterLast('/')
                            if (n.isNotBlank()) items.add(DavItem(n, dec, isCol, sz, dt))
                        }
                    }; tag = ""
                }
            }; p.next()
        }; return items
    }
}
