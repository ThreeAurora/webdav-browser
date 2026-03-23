package com.webdav.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*

// ===== 调色板 =====
val BG = Color(0xFF0D1117)
val CARD = Color(0xFF161B22)
val CARD_LIGHT = Color(0xFF1C2333)
val ACCENT = Color(0xFF58A6FF)
val ACCENT2 = Color(0xFF3FB950)
val DANGER = Color(0xFFf85149)
val TEXT1 = Color(0xFFE6EDF3)
val TEXT2 = Color(0xFF8B949E)
val TEXT3 = Color(0xFF484F58)
val DIVIDER = Color(0xFF21262D)
val GRADIENT_TOP = Color(0xFF161B22)
val GRADIENT_BOT = Color(0xFF0D1117)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermission()
        val settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = BG, surface = CARD, primary = ACCENT
            )) {
                Surface(color = BG) { AppRoot(settings) }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }
    }
}

enum class Screen { LOGIN, BROWSER, VIEWER, TRASH, SPLIT }

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 -> "${b}B"
    b < 1048576 -> "${"%.1f".format(b / 1024.0)}K"
    b < 1073741824 -> "${"%.1f".format(b / 1048576.0)}M"
    else -> "${"%.2f".format(b / 1073741824.0)}G"
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ========== 根组件 ==========
@Composable
fun AppRoot(settings: Settings) {
    var screen by remember { mutableStateOf(if (settings.isConfigured()) Screen.BROWSER else Screen.LOGIN) }
    var client by remember { mutableStateOf(
        if (settings.isConfigured()) WebDavClient(settings.serverUrl, settings.username, settings.password) else null
    )}
    var curPath by remember { mutableStateOf("/") }
    var allItems by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var allMedia by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var viewIdx by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(settings.sortBy) }
    var sortAsc by remember { mutableStateOf(settings.sortAsc) }
    var page by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun sortList(list: List<DavItem>): List<DavItem> {
        val filtered = list.filter { !it.isTrash && (settings.showHidden || !it.isHidden) }
        val dirs = filtered.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = filtered.filter { !it.isDir }.let { f ->
            when (sortBy) {
                "ext" -> f.sortedWith(compareBy({ it.ext }, { it.name.lowercase() }))
                "size" -> f.sortedBy { it.size }
                "date" -> f.sortedBy { it.date }
                else -> f.sortedBy { it.name.lowercase() }
            }.let { if (!sortAsc) it.reversed() else it }
        }
        return (if (sortAsc) dirs else dirs.reversed()) + files
    }

    fun load(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = client!!.listDir(path)
                withContext(Dispatchers.Main) {
                    curPath = path; allItems = sortList(r); allMedia = allItems.filter { it.isMedia }; page = 0
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun resort(by: String? = null) {
        if (by != null) { if (sortBy == by) sortAsc = !sortAsc else { sortBy = by; sortAsc = true } }
        settings.sortBy = sortBy; settings.sortAsc = sortAsc
        allItems = sortList(allItems); allMedia = allItems.filter { it.isMedia }
    }

    when (screen) {
        Screen.LOGIN -> LoginPage(settings) {
            client = WebDavClient(settings.serverUrl, settings.username, settings.password)
            screen = Screen.BROWSER; load("/")
        }
        Screen.BROWSER -> {
            LaunchedEffect(Unit) { if (allItems.isEmpty()) load("/") }
            BackHandler {
                if (curPath == "/") (ctx as? ComponentActivity)?.finish()
                else { val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/") }
            }
            BrowserPage(allItems, allMedia, curPath, sortBy, sortAsc, page, settings,
                onNav = { if (it.isDir) load(it.href) else if (it.isMedia) {
                    viewIdx = allMedia.indexOf(it).coerceAtLeast(0); screen = Screen.VIEWER
                }},
                onUp = { val p = curPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    load(if (p.endsWith("/")) p else "$p/") },
                onHome = { load("/") },
                onSort = { resort(it) },
                onToggle = { sortAsc = !sortAsc; resort() },
                onSettings = { screen = Screen.LOGIN },
                onTrash = { screen = Screen.TRASH },
                onSplit = { screen = Screen.SPLIT },
                onPageChange = { page = it }
            )
        }
        Screen.VIEWER -> {
            BackHandler { screen = Screen.BROWSER; load(curPath) }
            ViewerPage(allMedia, viewIdx, client!!, settings,
                onBack = { screen = Screen.BROWSER; load(curPath) },
                onDel = { d -> allItems = allItems.filter { it.href != d.href }
                    allMedia = allMedia.filter { it.href != d.href } }
            )
        }
        Screen.TRASH -> {
            BackHandler { screen = Screen.BROWSER }
            TrashPage(client!!, curPath) { screen = Screen.BROWSER; load(curPath) }
        }
        Screen.SPLIT -> {
            BackHandler { screen = Screen.BROWSER; load(curPath) }
            SplitPage(client!!, settings) { screen = Screen.BROWSER; load(curPath) }
        }
    }
}

// ========== 美化通用组件 ==========
@Composable
fun GradientBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFF1C2333), Color(0xFF161B22))))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun Pill(text: String, active: Boolean = false, color: Color = ACCENT, onClick: () -> Unit) {
    val bg = if (active) color.copy(alpha = 0.2f) else Color.Transparent
    val fg = if (active) color else TEXT2
    val border = if (active) color.copy(alpha = 0.5f) else TEXT3.copy(alpha = 0.3f)
    Text(text, color = fg, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .border(1.dp, border, RoundedCornerShape(20.dp)).background(bg)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
fun ActionBtn(emoji: String, label: String = "", color: Color = TEXT2, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(emoji, fontSize = 22.sp)
        if (label.isNotEmpty()) Text(label, color = color, fontSize = 9.sp)
    }
}

// ========== 登录页（美化版） ==========
@Composable
fun LoginPage(s: Settings, onConnect: () -> Unit) {
    var url by remember {
        val init = s.serverUrl.ifBlank { "http://" }
        mutableStateOf(TextFieldValue(init, selection = TextRange(init.length)))
    }
    var user by remember { mutableStateOf(s.username) }
    var pass by remember { mutableStateOf(s.password) }
    var cd by remember { mutableStateOf(s.confirmDelete) }
    var dp by remember { mutableStateOf(s.deleteButtonPos) }
    var ps by remember { mutableStateOf(s.pageSize) }
    var sh by remember { mutableStateOf(s.showHidden) }
    var vm by remember { mutableStateOf(s.viewMode) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(GRADIENT_TOP, GRADIENT_BOT)))
            .padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("🌐", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("WebDAV 浏览器", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TEXT1,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 24.dp))

        SectionCard("连接") {
            OutlinedTextField(url, { url = it }, label = { Text("服务器地址") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("http://192.168.1.100:5000") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACCENT))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(user, { user = it }, label = { Text("用户名（可空）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACCENT))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pass, { pass = it }, label = { Text("密码（可空）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACCENT))
        }

        SectionCard("显示") {
            Label("每页数量")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(20, 50, 100, 200).forEach { n ->
                    Pill("$n", ps == n) { ps = n }
                }
            }
            Spacer(Modifier.height(8.dp))
            Label("默认视图")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("grid" to "网格", "list" to "列表", "waterfall" to "瀑布").forEach { (v, l) ->
                    Pill(l, vm == v) { vm = v }
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingRow("显示隐藏文件", sh) { sh = it }
        }

        SectionCard("删除") {
            SettingRow("删除前确认", cd) { cd = it }
            Spacer(Modifier.height(8.dp))
            Label("删除按钮位置")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("top-left" to "↖左上", "top-right" to "↗右上",
                    "bottom-left" to "↙左下", "bottom-right" to "↘右下").forEach { (v, l) ->
                    Pill(l, dp == v, DANGER) { dp = v }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            s.serverUrl = url.text.trimEnd('/'); s.username = user; s.password = pass
            s.confirmDelete = cd; s.deleteButtonPos = dp; s.pageSize = ps; s.showHidden = sh; s.viewMode = vm
            onConnect()
        }, modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT)) {
            Text("连接", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(CARD).padding(16.dp)
    ) {
        Text(title, color = ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun Label(t: String) { Text(t, color = TEXT2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }

@Composable
fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TEXT1, fontSize = 14.sp); Spacer(Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = ACCENT, checkedTrackColor = ACCENT.copy(alpha = 0.3f)))
    }
}

// ========== 浏览页（美化版） ==========
@Composable
fun BrowserPage(
    allItems: List<DavItem>, allMedia: List<DavItem>, path: String,
    sortBy: String, sortAsc: Boolean, page: Int, settings: Settings,
    onNav: (DavItem) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onSort: (String) -> Unit, onToggle: () -> Unit, onSettings: () -> Unit,
    onTrash: () -> Unit, onSplit: () -> Unit, onPageChange: (Int) -> Unit
) {
    var viewMode by remember { mutableStateOf(settings.viewMode) }
    val pageSize = settings.pageSize
    val folders = allItems.filter { it.isDir }
    val files = allItems.filter { !it.isDir }
    val totalPages = ((files.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val curPage = page.coerceIn(0, totalPages - 1)
    val pageFiles = files.drop(curPage * pageSize).take(pageSize)

    Column(Modifier.fillMaxSize().background(BG)) {
        // 顶栏
        GradientBar {
            ActionBtn("⬆") { onUp() }; ActionBtn("🏠") { onHome() }
            Text(path, color = TEXT2, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            ActionBtn(when (viewMode) { "list" -> "☰"; "waterfall" -> "▥"; else -> "▦" }) {
                viewMode = when (viewMode) { "grid" -> "list"; "list" -> "waterfall"; else -> "grid" }
                settings.viewMode = viewMode
            }
            ActionBtn("📂", "分栏") { onSplit() }
            ActionBtn("♻") { onTrash() }
            ActionBtn("⚙") { onSettings() }
        }

        // 排序栏
        Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("name" to "名称", "ext" to "后缀", "size" to "大小", "date" to "日期").forEach { (k, l) ->
                Pill(l, sortBy == k) { onSort(k) }
            }
            Pill(if (sortAsc) "↑升" else "↓降") { onToggle() }
        }

        // 统计
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${folders.size}文件夹  ${files.size}文件", color = TEXT3, fontSize = 10.sp)
        }

        // 内容
        Box(Modifier.weight(1f)) {
            when (viewMode) {
                "list" -> ListView(folders, pageFiles, curPage, settings, onNav)
                "waterfall" -> WaterfallView(folders, pageFiles, curPage, settings, onNav)
                else -> GridView(folders, pageFiles, curPage, settings, onNav)
            }
        }

        // 分页
        if (totalPages > 1) {
            Row(Modifier.fillMaxWidth().background(CARD).padding(6.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PBtn("◀◀") { onPageChange(0) }; Spacer(Modifier.width(6.dp))
                PBtn("◀") { onPageChange((curPage - 1).coerceAtLeast(0)) }; Spacer(Modifier.width(10.dp))
                Text("${curPage + 1}/$totalPages", color = TEXT1, fontSize = 13.sp); Spacer(Modifier.width(10.dp))
                PBtn("▶") { onPageChange((curPage + 1).coerceAtMost(totalPages - 1)) }; Spacer(Modifier.width(6.dp))
                PBtn("▶▶") { onPageChange(totalPages - 1) }
            }
        }
    }
}

// ===== 三种视图 =====
@Composable
fun GridView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings, onNav: (DavItem) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (p == 0) folders.forEach { f -> item(span = { GridItemSpan(3) }) { FolderRow(f) { onNav(f) } } }
        items(files.size) { i -> FileGridCell(files[i], s) { onNav(files[i]) } }
    }
}

@Composable
fun ListView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings, onNav: (DavItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (p == 0) items(folders.size) { FolderRow(folders[it]) { onNav(folders[it]) } }
        items(files.size) { FileListRow(files[it], s) { onNav(files[it]) } }
    }
}

@Composable
fun WaterfallView(folders: List<DavItem>, files: List<DavItem>, p: Int, s: Settings, onNav: (DavItem) -> Unit) {
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), Modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalItemSpacing = 5.dp) {
        if (p == 0) folders.forEach { f -> item(span = StaggeredGridItemSpan.FullLine) { FolderRow(f) { onNav(f) } } }
        items(files.size) { WaterfallCell(files[it], s) { onNav(files[it]) } }
    }
}

@Composable
fun FolderRow(item: DavItem, onClick: () -> Unit) {
    val a = if (item.isHidden) 0.45f else 1f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CARD_LIGHT)
        .clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("📁", fontSize = 20.sp, modifier = Modifier.graphicsLayer(alpha = a))
        Spacer(Modifier.width(10.dp))
        Text(item.name, color = TEXT1.copy(alpha = a), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FileGridCell(item: DavItem, s: Settings, onClick: () -> Unit) {
    val a = if (item.isHidden) 0.4f else 1f
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(CARD).clickable { onClick() }.graphicsLayer(alpha = a)) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(item.fileIcon, fontSize = 28.sp) }
        if (item.size > 0) Text(fmtSize(item.size), fontSize = 8.sp, color = TEXT2,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xCC0D1117), RoundedCornerShape(0.dp,0.dp,6.dp,0.dp)).padding(3.dp))
        Text(item.name, fontSize = 9.sp, color = TEXT1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0D1117)).padding(3.dp))
    }
}

@Composable
fun FileListRow(item: DavItem, s: Settings, onClick: () -> Unit) {
    val a = if (item.isHidden) 0.4f else 1f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD)
        .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp).graphicsLayer(alpha = a),
        verticalAlignment = Alignment.CenterVertically) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)))
        else Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(CARD_LIGHT),
            contentAlignment = Alignment.Center) { Text(item.fileIcon, fontSize = 20.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = TEXT1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(".${item.ext}", color = ACCENT, fontSize = 10.sp)
                if (item.size > 0) Text(fmtSize(item.size), color = TEXT2, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun WaterfallCell(item: DavItem, s: Settings, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CARD).clickable { onClick() }
        .graphicsLayer(alpha = if (item.isHidden) 0.4f else 1f)) {
        if (item.isImage) AsyncImage(model = s.serverUrl.trimEnd('/') + "/" + item.href.trimStart('/'),
            contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp))
        else Box(Modifier.fillMaxWidth().height(90.dp).background(CARD_LIGHT), contentAlignment = Alignment.Center) {
            Text(item.fileIcon, fontSize = 32.sp) }
        Column(Modifier.padding(6.dp)) {
            Text(item.name, color = TEXT1, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.size > 0) Text(fmtSize(item.size), color = TEXT2, fontSize = 9.sp)
        }
    }
}

@Composable fun PBtn(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 14.sp, color = TEXT1, modifier = Modifier.clip(RoundedCornerShape(6.dp))
        .background(CARD_LIGHT).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 5.dp))
}

// ========== 分栏文件管理 ==========
@Composable
fun SplitPage(client: WebDavClient, settings: Settings, onBack: () -> Unit) {
    val localClient = remember { LocalFileClient() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 左栏：手机本地
    var localPath by remember { mutableStateOf(localClient.getDefaultRoot()) }
    var localFiles by remember { mutableStateOf<List<LocalFile>>(emptyList()) }
    var localSelected by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 右栏：WebDAV
    var davPath by remember { mutableStateOf("/") }
    var davFiles by remember { mutableStateOf<List<DavItem>>(emptyList()) }
    var davSelected by remember { mutableStateOf<Set<String>>(emptySet()) }

    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }

    // 重命名
    var renameTarget by remember { mutableStateOf<Any?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun loadLocal() { localFiles = localClient.listDir(localPath, settings.showHidden); localSelected = emptySet() }
    fun loadDav() { scope.launch(Dispatchers.IO) {
        val r = client.listDir(davPath).filter { !it.isTrash && (settings.showHidden || !it.isHidden) }
        withContext(Dispatchers.Main) { davFiles = r; davSelected = emptySet() }
    }}

    LaunchedEffect(Unit) { loadLocal(); loadDav() }

    // 传输操作
    fun copyLocalToDav() {
        if (localSelected.isEmpty()) return; busy = true
        scope.launch(Dispatchers.IO) {
            var ok = 0; var fail = 0
            localSelected.forEach { path ->
                val name = path.substringAfterLast('/')
                busyMsg = "上传 $name..."
                val data = localClient.readBytes(path)
                if (data != null && client.uploadBytes("$davPath$name", data)) ok++ else fail++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "上传完成：${ok}成功 ${fail}失败", Toast.LENGTH_SHORT).show()
                busy = false; busyMsg = ""; loadDav()
            }
        }
    }

    fun copyDavToLocal() {
        if (davSelected.isEmpty()) return; busy = true
        scope.launch(Dispatchers.IO) {
            var ok = 0; var fail = 0
            davSelected.forEach { href ->
                val name = href.trimEnd('/').substringAfterLast('/')
                busyMsg = "下载 $name..."
                val data = client.downloadBytes(href)
                if (data != null && localClient.writeBytes("$localPath/$name", data)) ok++ else fail++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "下载完成：${ok}成功 ${fail}失败", Toast.LENGTH_SHORT).show()
                busy = false; busyMsg = ""; loadLocal()
            }
        }
    }

    fun deleteLocalSelected() {
        localSelected.forEach { localClient.delete(it) }
        Toast.makeText(ctx, "已删除 ${localSelected.size} 项", Toast.LENGTH_SHORT).show()
        loadLocal()
    }

    fun deleteDavSelected() {
        scope.launch(Dispatchers.IO) {
            davSelected.forEach { client.moveToTrash(it) }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "已移到回收站 ${davSelected.size} 项", Toast.LENGTH_SHORT).show()
                loadDav()
            }
        }
    }

    // 重命名弹窗
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(renameText, { renameText = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ACCENT))
            },
            confirmButton = { TextButton({
                val target = renameTarget
                if (target is LocalFile) {
                    localClient.rename(target.path, renameText)
                    loadLocal()
                } else if (target is DavItem) {
                    scope.launch(Dispatchers.IO) {
                        client.davRename(target.href, renameText)
                        withContext(Dispatchers.Main) { loadDav() }
                    }
                }
                renameTarget = null
            }) { Text("确定", color = ACCENT) }},
            dismissButton = { TextButton({ renameTarget = null }) { Text("取消") }}
        )
    }

    Column(Modifier.fillMaxSize().background(BG)) {
        // 顶栏
        GradientBar {
            ActionBtn("✕") { onBack() }
            Spacer(Modifier.width(8.dp))
            Text("📱 手机  ↔  💻 电脑", color = TEXT1, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (busy) Text(busyMsg, color = ACCENT, fontSize = 11.sp)
        }

        // 分栏
        Row(Modifier.weight(1f)) {
            // ===== 左栏：手机 =====
            Column(Modifier.weight(1f).background(BG)) {
                // 路径栏
                Row(Modifier.fillMaxWidth().background(CARD).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", fontSize = 14.sp)
                    Text(localPath.removePrefix(localClient.getDefaultRoot()).ifEmpty { "/" },
                        color = TEXT2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    Text("⬆", fontSize = 14.sp, modifier = Modifier.clickable {
                        val parent = localPath.substringBeforeLast('/')
                        if (parent.isNotEmpty() && parent != localPath) { localPath = parent; loadLocal() }
                    })
                }

                LazyColumn(Modifier.weight(1f).padding(horizontal = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(localFiles.size) { i ->
                        val f = localFiles[i]
                        val sel = localSelected.contains(f.path)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (sel) ACCENT.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    if (localSelected.isNotEmpty()) {
                                        localSelected = if (sel) localSelected - f.path else localSelected + f.path
                                    } else if (f.isDir) { localPath = f.path; loadLocal() }
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (localSelected.isNotEmpty()) {
                                            localSelected = if (sel) localSelected - f.path else localSelected + f.path
                                        } else if (f.isDir) { localPath = f.path; loadLocal() }
                                    },
                                    onLongClick = {
                                        localSelected = if (sel) localSelected - f.path else localSelected + f.path
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.fileIcon, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = if (f.isHidden) TEXT3 else TEXT1, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (f.size > 0) Text(fmtSize(f.size), color = TEXT3, fontSize = 9.sp)
                            }
                            // 重命名按钮
                            Text("✏", fontSize = 12.sp, modifier = Modifier.clickable {
                                renameText = f.name; renameTarget = f
                            }.padding(4.dp))
                        }
                    }
                }

                // 左栏选中操作栏
                if (localSelected.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().background(CARD_LIGHT).padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionBtn("➡️", "上传到电脑", ACCENT) { copyLocalToDav() }
                        ActionBtn("🗑", "删除", DANGER) { deleteLocalSelected() }
                        ActionBtn("✕", "取消") { localSelected = emptySet() }
                        Text("${localSelected.size}项", color = TEXT2, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }

            // 分隔线
            Box(Modifier.width(2.dp).fillMaxHeight().background(
                Brush.verticalGradient(listOf(ACCENT.copy(alpha = 0.3f), ACCENT2.copy(alpha = 0.3f)))))

            // ===== 右栏：WebDAV =====
            Column(Modifier.weight(1f).background(BG)) {
                Row(Modifier.fillMaxWidth().background(CARD).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("💻", fontSize = 14.sp)
                    Text(davPath, color = TEXT2, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    Text("⬆", fontSize = 14.sp, modifier = Modifier.clickable {
                        if (davPath != "/") {
                            davPath = davPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                            if (!davPath.endsWith("/")) davPath += "/"
                            loadDav()
                        }
                    })
                }

                LazyColumn(Modifier.weight(1f).padding(horizontal = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(davFiles.size) { i ->
                        val f = davFiles[i]
                        val sel = davSelected.contains(f.href)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (sel) ACCENT2.copy(alpha = 0.15f) else Color.Transparent)
                                .combinedClickable(
                                    onClick = {
                                        if (davSelected.isNotEmpty()) {
                                            davSelected = if (sel) davSelected - f.href else davSelected + f.href
                                        } else if (f.isDir) { davPath = f.href; loadDav() }
                                    },
                                    onLongClick = {
                                        davSelected = if (sel) davSelected - f.href else davSelected + f.href
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.fileIcon, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = if (f.isHidden) TEXT3 else TEXT1, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (f.size > 0) Text(fmtSize(f.size), color = TEXT3, fontSize = 9.sp)
                            }
                            Text("✏", fontSize = 12.sp, modifier = Modifier.clickable {
                                renameText = f.name; renameTarget = f
                            }.padding(4.dp))
                        }
                    }
                }

                if (davSelected.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().background(CARD_LIGHT).padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionBtn("⬅️", "下载到手机", ACCENT2) { copyDavToLocal() }
                        ActionBtn("🗑", "回收站", DANGER) { deleteDavSelected() }
                        ActionBtn("✕", "取消") { davSelected = emptySet() }
                        Text("${davSelected.size}项", color = TEXT2, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }
}

// ========== 全屏浏览页 ==========
@Composable
fun ViewerPage(
    items: List<DavItem>, startIdx: Int, client: WebDavClient, settings: Settings,
    onBack: () -> Unit, onDel: (DavItem) -> Unit
) {
    var idx by remember { mutableStateOf(startIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var local by remember { mutableStateOf(items.toList()) }
    var showDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }; var offsetY by remember { mutableStateOf(0f) }
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    LaunchedEffect(idx) { scale = 1f; offsetX = 0f; offsetY = 0f }
    if (local.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val cur = local[idx.coerceIn(0, local.size - 1)]

    fun doDelete() { val d = cur; scope.launch(Dispatchers.IO) {
        val ok = client.moveToTrash(d.href)
        withContext(Dispatchers.Main) {
            if (ok) { Toast.makeText(ctx, "已移到回收站", Toast.LENGTH_SHORT).show(); onDel(d)
                local = local.filter { it.href != d.href }
                if (local.isEmpty()) { onBack(); return@withContext }
                if (idx >= local.size) idx = 0
            } else Toast.makeText(ctx, "失败", Toast.LENGTH_SHORT).show()
        }
    }}

    fun nav(d: Int) { if (local.isNotEmpty()) idx = (idx + d + local.size) % local.size }

    if (showDlg) AlertDialog(onDismissRequest = { showDlg = false },
        title = { Text("移到回收站") }, text = { Text(cur.name) },
        confirmButton = { TextButton({ showDlg = false; doDelete() }) { Text("确定", color = DANGER) }},
        dismissButton = { TextButton({ showDlg = false }) { Text("取消") }})

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (cur.isImage) AsyncImage(model = client.fileUrl(cur.href), contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale,
                    translationX = offsetX, translationY = offsetY))
            else if (cur.isVideo) VPlayer(client.fileUrl(cur.href), client,
                { playerRef = it }, { playerRef = null })
        }

        // 手势层
        Box(Modifier.fillMaxWidth().then(if (cur.isVideo) Modifier.fillMaxHeight(0.85f) else Modifier.fillMaxHeight())
            .pointerInput(idx, cur.isVideo, scale) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val sX = down.position.x; val sY = down.position.y; val sT = System.currentTimeMillis()
                        var eX = sX; var eY = sY; var moved = false; var ptrs = 1
                        var initDist = 0f; var initScale = scale

                        while (true) {
                            val ev = awaitPointerEvent(); val ps = ev.changes.filter { it.pressed }
                            if (ps.isEmpty()) { ev.changes.firstOrNull()?.let { eX = it.position.x; eY = it.position.y }; break }
                            if (ps.size >= 2 && cur.isImage) {
                                ptrs = 2
                                val p1 = ps[0].position; val p2 = ps[1].position
                                val dist = kotlin.math.sqrt((p2.x-p1.x)*(p2.x-p1.x)+(p2.y-p1.y)*(p2.y-p1.y))
                                if (initDist == 0f) { initDist = dist; initScale = scale }
                                else if (dist > 0f) {
                                    scale = (initScale * dist / initDist).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        val mx = (ps[0].position.x+ps[1].position.x)/2
                                        val my = (ps[0].position.y+ps[1].position.y)/2
                                        val pmx = (ps[0].previousPosition.x+ps[1].previousPosition.x)/2
                                        val pmy = (ps[0].previousPosition.y+ps[1].previousPosition.y)/2
                                        offsetX += mx-pmx; offsetY += my-pmy
                                        val mxx = (scale-1f)*size.width/2; val mxy = (scale-1f)*size.height/2
                                        offsetX = offsetX.coerceIn(-mxx, mxx); offsetY = offsetY.coerceIn(-mxy, mxy)
                                    } else { offsetX = 0f; offsetY = 0f }
                                }
                                ps.forEach { it.consume() }
                            } else if (ps.size == 1) {
                                val c = ps[0]; eX = c.position.x; eY = c.position.y
                                if (scale > 1.05f && cur.isImage && ptrs == 1) {
                                    val px = c.position.x-c.previousPosition.x; val py = c.position.y-c.previousPosition.y
                                    if (kotlin.math.abs(px)>1||kotlin.math.abs(py)>1) {
                                        moved = true; offsetX += px; offsetY += py
                                        val mxx = (scale-1f)*size.width/2; val mxy = (scale-1f)*size.height/2
                                        offsetX = offsetX.coerceIn(-mxx, mxx); offsetY = offsetY.coerceIn(-mxy, mxy)
                                        c.consume()
                                    }
                                } else { if (kotlin.math.abs(eX-sX)>20||kotlin.math.abs(eY-sY)>20) moved = true }
                            }
                        }
                        if (ptrs >= 2) continue
                        val dx = eX-sX; val dy = eY-sY; val dt = System.currentTimeMillis()-sT
                        if (scale > 1.05f && moved) continue
                        if (kotlin.math.abs(dy)>80 && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f && scale<=1.05f) {
                            if (dy > 0) nav(-1) else nav(1)
                        } else if (!moved && dt < 300) {
                            val now = System.currentTimeMillis()
                            if (cur.isImage && now-lastTapTime<350) {
                                if (scale>1.1f){scale=1f;offsetX=0f;offsetY=0f} else scale=2.5f; lastTapTime=0L
                            } else {
                                lastTapTime = now; val tx = sX; val tw = size.width
                                scope.launch { delay(360)
                                    if (lastTapTime == now) {
                                        if (cur.isVideo) { playerRef?.let { p -> when {
                                            tx<tw*0.35f -> p.seekTo((p.currentPosition-5000).coerceAtLeast(0))
                                            tx>tw*0.65f -> p.seekTo((p.currentPosition+5000).coerceAtMost(p.duration))
                                            else -> p.playWhenReady = !p.playWhenReady
                                        }}} else if (scale<=1.05f) { if (tx<tw*0.4f) nav(-1) else if (tx>tw*0.6f) nav(1) }
                                    }
                                }
                            }
                        }
                    }
                }
            })

        // 顶部信息
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x88000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("${idx+1}/${local.size}  ${if(cur.size>0)"(${fmtSize(cur.size)}) " else ""}${cur.name}",
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopStart)
            .padding(8.dp).clip(CircleShape).background(Color(0x66333333)).clickable { onBack() }
            .padding(horizontal = 14.dp, vertical = 8.dp))

        if (scale > 1.05f) Text("${(scale*100).toInt()}%  双击复原", color = Color(0xAAFFFFFF), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))

        val al = when(settings.deleteButtonPos){"top-left"->Alignment.TopStart;"top-right"->Alignment.TopEnd
            "bottom-left"->Alignment.BottomStart;else->Alignment.BottomEnd}
        val pd = when(settings.deleteButtonPos){"top-left"->Modifier.padding(start=8.dp,top=52.dp)
            "top-right"->Modifier.padding(end=8.dp,top=52.dp);"bottom-left"->Modifier.padding(start=8.dp,bottom=16.dp)
            else->Modifier.padding(end=8.dp,bottom=16.dp)}
        Text("🗑️", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(al).then(pd)
            .clip(CircleShape).background(Color(0x99DD2222)).clickable {
                if (settings.confirmDelete) showDlg = true else doDelete()
            }.padding(14.dp))

        if (scale <= 1.05f) {
            val hint = if (cur.isVideo) "← → 快进退 · ↕ 切换" else "← → 切换 · ↕ 切换"
            Text(hint, color = Color(0x55FFFFFF), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }
    }
}

// ========== 视频播放器 ==========
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VPlayer(url: String, client: WebDavClient, onCreated: (ExoPlayer) -> Unit, onReleased: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.getClient()))
        ).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true }
    }
    var pos by remember { mutableStateOf(0L) }; var dur by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }; var seekV by remember { mutableStateOf(0f) }
    LaunchedEffect(player) { onCreated(player); while (true) {
        if (!seeking) { pos = player.currentPosition.coerceAtLeast(0); dur = player.duration.let { if (it<0) 0 else it } }
        delay(500)
    }}
    DisposableEffect(url) { onDispose { onReleased(); player.release() } }

    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
            update = { it.player = player }, modifier = Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(start = 12.dp, end = 100.dp, top = 2.dp, bottom = 4.dp)) {
            Slider(value = if (seeking) seekV else if (dur>0) pos.toFloat()/dur.toFloat() else 0f,
                onValueChange = { seeking = true; seekV = it },
                onValueChangeFinished = { player.seekTo((seekV*dur).toLong()); seeking = false },
                modifier = Modifier.fillMaxWidth().height(20.dp),
                colors = SliderDefaults.colors(thumbColor = ACCENT, activeTrackColor = ACCENT, inactiveTrackColor = Color(0xFF333333)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(if (seeking)(seekV*dur).toLong() else pos), color = TEXT2, fontSize = 11.sp)
                Text(fmtTime(dur), color = TEXT3, fontSize = 11.sp)
            }
        }
    }
}

// ========== 回收站 ==========
@Composable
fun TrashPage(client: WebDavClient, currentPath: String, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }; var showDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    fun load() { loading = true; scope.launch(Dispatchers.IO) {
        val r = client.listTrash(currentPath); withContext(Dispatchers.Main) { items = r; loading = false }
    }}
    LaunchedEffect(Unit) { load() }

    if (showDlg) AlertDialog(onDismissRequest = { showDlg = false },
        title = { Text("清空回收站") }, text = { Text("永久删除 ${items.size} 个文件？") },
        confirmButton = { TextButton({ showDlg = false; scope.launch(Dispatchers.IO) {
            client.emptyTrash(currentPath); withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show(); items = emptyList()
            }}}) { Text("删除", color = DANGER) }},
        dismissButton = { TextButton({ showDlg = false }) { Text("取消") }})

    Column(Modifier.fillMaxSize().background(BG)) {
        GradientBar {
            ActionBtn("✕") { onBack() }; Spacer(Modifier.width(8.dp))
            Text("♻ 回收站", color = TEXT1, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            ActionBtn("🔄") { load() }; Spacer(Modifier.width(4.dp))
            if (items.isNotEmpty()) Text("清空", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(DANGER.copy(alpha = 0.8f))
                    .clickable { showDlg = true }.padding(horizontal = 12.dp, vertical = 5.dp))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中...", color = TEXT2) }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♻", fontSize = 48.sp); Text("回收站为空", color = TEXT2, modifier = Modifier.padding(top = 8.dp))
                }}
            else -> { Text("  ${items.size} 个文件", color = TEXT3, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(items) { _, e -> TrashRow(e, client, ctx, scope,
                        { items = items.filter { it.id != e.id } }, { items = items.filter { it.id != e.id } })
                    }
                }
            }
        }
    }
}

@Composable
fun TrashRow(e: TrashEntry, client: WebDavClient, ctx: android.content.Context, scope: CoroutineScope,
    onOk: () -> Unit, onDel: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(when { e.isImage -> "🖼️"; e.isVideo -> "🎬"; else -> "📄" }, fontSize = 24.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(e.fileName, color = TEXT1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row { if (e.size>0) Text(fmtSize(e.size), color = TEXT3, fontSize = 10.sp)
                Text("  ${e.date}", color = TEXT3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (busy) Text("...", color = ACCENT)
        else {
            Text("还原", color = ACCENT, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(ACCENT.copy(alpha = 0.15f)).clickable { busy = true
                    scope.launch(Dispatchers.IO) { val (ok, msg) = client.restoreFromTrash(e)
                        withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); busy = false; if (ok) onOk() }
                    }}.padding(horizontal = 10.dp, vertical = 5.dp))
            Spacer(Modifier.width(4.dp))
            Text("彻删", color = DANGER, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(DANGER.copy(alpha = 0.15f)).clickable { busy = true
                    scope.launch(Dispatchers.IO) { client.permanentDeleteTrashEntry(e)
                        withContext(Dispatchers.Main) { Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show(); busy = false; onDel() }
                    }}.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
