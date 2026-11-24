package com.glassplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassplayer.data.MipanApi
import com.glassplayer.data.PublicResourceApis
import com.glassplayer.data.ResourceSource
import com.glassplayer.data.QuarkApi
import com.glassplayer.data.QuarkFolder
import com.glassplayer.data.QuarkFolderSync
import com.glassplayer.data.ResourceLink
import com.glassplayer.data.ResourceSearchResult
import com.glassplayer.data.SettingsStore
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 资源搜索页：聚合公开分享的网盘资源（数据来自觅盘），默认只看夸克。
 * 搜索后会批量校验链接有效性，剔除失效链接；点结果先在应用内预览分享目录，再决定是否转存。
 */
@Composable
fun ResourcePane(
    settings: SettingsStore,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
    onSheetVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val colors = GlasmioTheme.colors
    val quark = remember(context, settings) { QuarkApi(context, settings) }

    var query by rememberSaveable { mutableStateOf(settings.resourceLastQuery) }
    var selectedSource by rememberSaveable { mutableIntStateOf(0) }
    // rememberSaveable 存的是下标：枚举增删后旧下标可能越界，这里统一夹一次
    val sourceIndex = selectedSource.coerceIn(0, ResourceSource.entries.lastIndex)
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf(0) }
    var raw by remember { mutableStateOf<List<ResourceLink>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var displayLimit by remember { mutableIntStateOf(40) }
    var checking by remember { mutableStateOf(false) }
    var checkingMore by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<ResourceLink?>(null) }
    // 收藏的「文件夹盘」：只存链接，目录内容每次打开实时读
    var folders by remember { mutableStateOf(settings.quarkFolders()) }
    var folderPreview by remember { mutableStateOf<QuarkFolder?>(null) }
    var showAddFolder by remember { mutableStateOf(false) }

    LaunchedEffect(preview, folderPreview, showAddFolder) {
        onSheetVisibleChange(preview != null || folderPreview != null || showAddFolder)
    }

    fun statusOf(link: ResourceLink): String =
        statuses[link.url] ?: statuses[MipanApi.dedupeKey(link.url)] ?: "unknown"

    fun search() {
        val keyword = query.trim()
        if (keyword.isBlank() || loading) return
        focusManager.clearFocus()
        val selected = listOf(ResourceSource.entries[sourceIndex])
        loading = true
        message = "正在搜索 ${selected.first().label}…"
        scope.launch {
            val results = coroutineScope {
                selected.map { source ->
                    async(Dispatchers.IO) {
                        source to runCatching { PublicResourceApis.search(source, keyword) }
                    }
                }.awaitAll()
            }
            val all = ArrayList<ResourceLink>()
            val failed = ArrayList<String>()
            results.forEach { (source, result) ->
                // 只保留夸克资源：觅盘等聚合源会同时返回百度/阿里/115/UC，
                // 本应用只做夸克，其他网盘链接点了也播不了，直接过滤掉
                result.onSuccess { links -> all += links.filter { it.isQuarkResource() } }
                    .onFailure { failed += source.label }
            }
            if (all.isEmpty()) {
                raw = emptyList()
                statuses = emptyMap()
                message = if (failed.isEmpty()) "没有找到夸克资源" else "全部来源请求失败：${failed.joinToString("、")}"
                loading = false
                return@launch
            }
            settings.resourceLastQuery = keyword
            // 同一链接可能被多个站点收录：合并来源标签，优先保留带提取码的。
            val merged = LinkedHashMap<String, ResourceLink>()
            all.forEach { link ->
                val key = link.type + "|" + MipanApi.dedupeKey(link.url)
                val old = merged[key]
                if (old == null) {
                    merged[key] = link
                } else {
                    val newer = if (link.datetime > old.datetime) link else old
                    val password = if (old.password.isNotBlank()) old.password else link.password
                    merged[key] = newer.copy(
                        password = password,
                        site = mergeSites(old.site, link.site),
                    )
                }
            }
            // 同一来源内同名资源只保留最新一条；不同来源的同名结果保留，方便对比。
            val latest = merged.values
                .sortedByDescending { it.datetime }
                .distinctBy { it.note.trim().lowercase() + "|" + it.site + "|" + it.source }
            total = latest.size
            raw = latest
            statuses = emptyMap()
            displayLimit = 40
            searchGeneration++
            message = buildString {
                append("找到 $total 条 · ${selected.first().label}")
                if (failed.isNotEmpty()) append(" · 失败：${failed.joinToString("、")}")
                append("，正在校验本页…")
            }
            loading = false
        }
    }

    val baseList = remember(raw) {
        raw.sortedByDescending { it.datetime }
    }
    val shown = remember(baseList, displayLimit, statuses) {
        baseList.take(displayLimit).filter { statusOf(it) != "invalid" }
    }

    // 只校验当前这一页（40 条），不再一次性校验 1000+ 条；换筛选/换关键词会自动重来。
    LaunchedEffect(searchGeneration) {
        if (baseList.isEmpty()) return@LaunchedEffect
        displayLimit = 40
        statuses = emptyMap()
        checking = true
        val page = baseList.take(40)
        val checks = runCatching {
            withContext(Dispatchers.IO) { MipanApi.checkLinks(page) }
        }.getOrNull()
        if (checks != null) statuses = checks
        checking = false
        message = if (checks == null) {
            "本页链接未验证 · 共 ${baseList.size} 条"
        } else {
            "本页 ${page.count { statusOf(it) != "invalid" }} 条有效 · " +
                "剔除 ${page.count { statusOf(it) == "invalid" }} 条失效 · 共 ${baseList.size} 条"
        }
    }

    fun loadMore() {
        if (checkingMore || displayLimit >= baseList.size) return
        checkingMore = true
        val start = displayLimit
        scope.launch {
            val page = baseList.drop(start).take(40)
            val checks = runCatching {
                withContext(Dispatchers.IO) { MipanApi.checkLinks(page) }
            }.getOrNull()
            if (checks != null) statuses = statuses + checks
            displayLimit = start + 40
            checkingMore = false
            message = "本页 ${page.count { statusOf(it) != "invalid" }} 条有效 · " +
                "剔除 ${page.count { statusOf(it) == "invalid" }} 条失效 · 共 ${baseList.size} 条"
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜剧集、电影、软件…", maxLines = 1) },
                modifier = Modifier.weight(1f),
            )
            GlassPill(
                text = if (loading) "处理中…" else "搜索",
                selected = true,
                onClick = { search() },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResourceSource.entries.forEachIndexed { index, source ->
                GlassPill(
                    text = sourceChipLabel(source),
                    selected = sourceIndex == index,
                    onClick = {
                        focusManager.clearFocus()
                        selectedSource = index
                    },
                )
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.content.copy(alpha = 0.62f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件夹盘",
                style = MaterialTheme.typography.labelSmall,
                color = colors.content.copy(alpha = 0.45f),
            )
            folders.forEach { folder ->
                GlassPill(
                    text = folder.name,
                    selected = folderPreview?.url == folder.url,
                    onClick = { folderPreview = folder },
                )
            }
            GlassPill(text = "＋ 添加", onClick = { showAddFolder = true })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.type + it.url }) { link ->
                ResourceCard(
                    link = link,
                    status = statusOf(link),
                    onClick = { preview = link },
                )
            }
            if (displayLimit < baseList.size) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        GlassPill(
                            text = if (checkingMore) "校验中…" else "加载更多（还有 ${baseList.size - displayLimit} 条）",
                            onClick = { loadMore() },
                        )
                    }
                }
            }
        }
    }

    preview?.let { link ->
        SharePreviewSheet(
            link = link,
            quark = quark,
            settings = settings,
            onDismiss = { preview = null },
            onSaved = {
                preview = null
                onSaved()
            },
        )
    }

    folderPreview?.let { folder ->
        SharePreviewSheet(
            link = ResourceLink(
                type = "quark",
                url = folder.url,
                password = folder.code,
                note = folder.name,
                datetime = "",
                source = "文件夹盘",
                site = "收藏",
            ),
            quark = quark,
            settings = settings,
            folder = folder,
            onDismiss = { folderPreview = null },
            onSaved = { onSaved() },
            onFolderUpdated = { updated ->
                settings.saveQuarkFolder(updated)
                folders = settings.quarkFolders()
                folderPreview = updated
            },
            onRemoveFolder = {
                settings.removeQuarkFolder(folder.url)
                folders = settings.quarkFolders()
                folderPreview = null
                Toast.makeText(context, "已移除「${folder.name}」", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showAddFolder) {
        AddFolderSheet(
            quark = quark,
            settings = settings,
            onDismiss = { showAddFolder = false },
            onAdded = { folder ->
                settings.saveQuarkFolder(folder)
                folders = settings.quarkFolders()
                showAddFolder = false
                folderPreview = folder
                Toast.makeText(context, "已收藏「${folder.name}」", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun SharePreviewSheet(
    link: ResourceLink,
    quark: QuarkApi,
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    folder: QuarkFolder? = null,
    onFolderUpdated: (QuarkFolder) -> Unit = {},
    onRemoveFolder: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = GlasmioTheme.colors
    var entries by remember { mutableStateOf<List<QuarkApi.ShareEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(listOf("0" to "分享目录")) }
    val currentFid = path.last().first

    LaunchedEffect(link.url, currentFid) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { quark.shareEntries(link.url, link.password, currentFid) }
        }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "无法读取分享目录" }
        loading = false
    }

    GlassSheet(
        visible = true,
        onDismissRequest = onDismiss,
        title = if (folder != null) "文件夹盘" else "分享预览",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = link.note,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.content,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (path.size > 1) {
                    GlassPill(text = "返回上级", onClick = { path = path.dropLast(1) })
                }
                Text(
                    text = path.joinToString(" / ") { it.second },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.content.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when {
                loading -> Text("正在读取分享目录…", color = colors.content.copy(alpha = 0.6f))
                error != null -> Text("读取失败：$error", color = colors.content.copy(alpha = 0.7f))
                entries.isEmpty() -> Text("这个目录是空的", color = colors.content.copy(alpha = 0.6f))
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.fid }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = entry.isDir) {
                                    path = path + (entry.fid to entry.name)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = if (entry.isDir) "📁" else "🎬", fontSize = 15.sp)
                            Spacer(Modifier.padding(horizontal = 5.dp))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (entry.isDir) "文件夹" else formatBytes(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.content.copy(alpha = 0.42f),
                            )
                        }
                    }
                }
            }

            folder?.let {
                Text(
                    text = "已同步 ${it.syncedKeys.size} 个视频 · 同步只转存新增文件，转存后在「网盘」页播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.content.copy(alpha = 0.48f),
                )
            }
            syncStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.content.copy(alpha = 0.7f),
                )
            }
            saveError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.content.copy(alpha = 0.7f),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (folder != null) {
                    GlassPill(
                        text = if (syncing) "同步中…" else "同步新增",
                        selected = true,
                        onClick = {
                            if (!syncing) {
                                syncing = true
                                syncStatus = "正在读取目录…"
                                scope.launch {
                                    // 按钮只在 folder != null 时才会出现；用非空局部变量，
                                    // 避免 lambda 捕获可空参数导致智能转换失效
                                    var liveFolder: QuarkFolder = folder
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            // 根目录里已有的同名文件（之前整包保存过）不再重复转存；
                                            // 翻前 3 页（最多 300 条），覆盖大部分历史保存
                                            val existing = runCatching {
                                                val names = HashSet<String>()
                                                var pageNo = 1
                                                while (pageNo <= 3) {
                                                    val (files, hasMore) = quark.list("0", pageNo)
                                                    files.forEach { if (!it.isDir) names += it.name }
                                                    if (!hasMore) break
                                                    pageNo++
                                                }
                                                names
                                            }.getOrDefault(emptySet())
                                            val videos = QuarkFolderSync.collectVideos(quark, liveFolder)
                                            QuarkFolderSync.saveMissing(
                                                api = quark,
                                                folder = liveFolder,
                                                videos = videos,
                                                existingNames = existing,
                                                onBatchSaved = { keys ->
                                                    liveFolder = liveFolder.copy(
                                                        syncedKeys = liveFolder.syncedKeys + keys,
                                                    )
                                                    val snapshot = liveFolder
                                                    scope.launch {
                                                        settings.saveQuarkFolder(snapshot)
                                                        onFolderUpdated(snapshot)
                                                    }
                                                },
                                                onProgress = { done, total ->
                                                    scope.launch {
                                                        syncStatus = "正在转存 $done / $total…"
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    result.onSuccess { outcome ->
                                        val updated = liveFolder.copy(
                                            syncedKeys = liveFolder.syncedKeys + outcome.addedKeys,
                                        )
                                        if (outcome.added > 0 || outcome.addedKeys.isNotEmpty()) {
                                            settings.saveQuarkFolder(updated)
                                        }
                                        onFolderUpdated(updated)
                                        syncStatus = if (outcome.added == 0) {
                                            "已是最新：目录里共 ${outcome.found} 个视频"
                                        } else {
                                            "新增 ${outcome.added} 个视频，已转存到网盘"
                                        }
                                        if (outcome.added > 0) {
                                            Toast.makeText(
                                                context,
                                                "已转存 ${outcome.added} 个新增视频",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onSaved()
                                        }
                                    }.onFailure {
                                        syncStatus = "同步失败：" + (it.message ?: "未知错误")
                                    }
                                    syncing = false
                                }
                            }
                        },
                    )
                }
                GlassPill(
                    text = if (saving) "保存中…" else "保存整个分享",
                    selected = folder == null,
                    onClick = {
                        if (!saving) {
                            saving = true
                            saveError = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { quark.saveShare(link.url, link.password) }
                                }
                                    .onSuccess { count ->
                                        Toast.makeText(context, "已保存 $count 项到网盘", Toast.LENGTH_SHORT).show()
                                        onSaved()
                                    }
                                    .onFailure { saveError = "保存失败：" + (it.message ?: "未知错误") }
                                saving = false
                            }
                        }
                    },
                )
                if (folder != null) {
                    GlassPill(
                        text = "移除收藏",
                        onClick = {
                            if (!syncing) onRemoveFolder()
                        },
                    )
                }
                GlassPill(
                    text = "复制链接+提取码",
                    onClick = {
                        copyText(context, MipanApi.shareText(link))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                )
                GlassPill(
                    text = "浏览器打开",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
            // 底部导航是浮在内容之上的，留出高度，避免“保存整个分享”被导航栏压住。
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun AddFolderSheet(
    quark: QuarkApi,
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onAdded: (QuarkFolder) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = GlasmioTheme.colors
    var link by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GlassSheet(
        visible = true,
        onDismissRequest = { if (!busy) onDismiss() },
        title = "添加夸克文件夹",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "收藏一个夸克分享文件夹：之后对方往文件夹里更新文件，" +
                    "点「同步新增」就能把新文件转存进来，不用再换分享链接。",
                style = MaterialTheme.typography.labelMedium,
                color = colors.content.copy(alpha = 0.6f),
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                singleLine = true,
                label = { Text("分享链接") },
                placeholder = { Text("https://pan.quark.cn/s/xxxx") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                label = { Text("提取码（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.content.copy(alpha = 0.75f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "取消",
                    onClick = { if (!busy) onDismiss() },
                )
                GlassPill(
                    text = if (busy) "读取中…" else "收藏",
                    selected = true,
                    onClick = {
                        if (!busy) {
                            val trimmed = link.trim()
                            val pwdId = quark.sharePwdId(trimmed)
                            if (pwdId == null) {
                                error = "链接格式不对：应形如 https://pan.quark.cn/s/xxxx"
                            } else if (settings.quarkCookie.isNullOrBlank()) {
                                error = "请先在「网盘」页扫码登录夸克账号"
                            } else {
                                busy = true
                                error = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            quark.shareEntries(trimmed, code.trim())
                                        }
                                    }.onSuccess { entries ->
                                        val resolved = name.trim().ifBlank {
                                            entries.singleOrNull()?.takeIf { it.isDir }?.name
                                                ?: "夸克文件夹 ${pwdId.take(6)}"
                                        }
                                        val folder = QuarkFolder(
                                            url = trimmed,
                                            code = code.trim(),
                                            name = resolved,
                                            addedAt = System.currentTimeMillis(),
                                            syncedKeys = emptySet(),
                                        )
                                        onAdded(folder)
                                    }.onFailure {
                                        error = "读取失败：" + (it.message ?: "链接可能已失效")
                                    }
                                    busy = false
                                }
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun ResourceCard(link: ResourceLink, status: String, onClick: () -> Unit) {
    val colors = GlasmioTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.elevatedBackground.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = MipanApi.typeLabel(link.type),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.content,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent.copy(alpha = 0.22f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Text(
                text = link.site,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = siteColor(link.site),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(siteColor(link.site).copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            if (link.password.isNotBlank()) {
                Text(
                    text = "提取码 ${link.password}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.content.copy(alpha = 0.6f),
                )
            }
            if (status == "valid") {
                Text(
                    text = "有效",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = androidx.compose.ui.graphics.Color(0xFF34C759),
                )
            } else if (status == "unknown") {
                Text(
                    text = "未验证",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.content.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = link.datetime.take(10),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.content.copy(alpha = 0.4f),
            )
        }
        Text(
            text = link.note,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = link.url,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.content.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        copyText(context, MipanApi.shareText(link))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.padding(horizontal = 3.dp))
            Text(
                text = "预览 / 保存",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.accent,
            )
        }
    }
}

private fun sourceChipLabel(source: ResourceSource): String = when (source) {
    ResourceSource.GUGESO -> "谷哥"
    ResourceSource.KKSO -> "KK"
    ResourceSource.PANPANSOU -> "盘盘"
    ResourceSource.PANSOUDE -> "Pansou"
    else -> source.label
}

/**
 * 只认夸克资源。
 * 觅盘按网盘类型分桶返回（quark/baidu/aliyun/uc/…），聚合站点也可能带出别的盘，
 * 本应用只支持夸克，其他网盘链接点了也播不了。
 */
private fun ResourceLink.isQuarkResource(): Boolean =
    type.equals("quark", true) ||
        url.contains("pan.quark.cn", true) ||
        url.contains("drive.quark.cn", true)

private fun mergeSites(a: String, b: String): String =
    (a.split(" / ") + b.split(" / ")).filter { it.isNotBlank() }.distinct().joinToString(" / ")

private fun siteColor(site: String): Color = when {
    site.contains("谷哥") -> Color(0xFF2E9E5B)
    site.contains("KK") -> Color(0xFFE08A2E)
    site.contains("盘盘") -> Color(0xFF8E6BD8)
    site.contains("Pansou") -> Color(0xFFD95C8A)
    else -> Color(0xFF4C8DFF)
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("网盘资源", text))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
