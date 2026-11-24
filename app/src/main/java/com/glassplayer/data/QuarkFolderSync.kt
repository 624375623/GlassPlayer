package com.glassplayer.data

/**
 * 「夸克文件夹盘」的同步逻辑。
 *
 * 用户只收藏一次分享文件夹的链接；对方往这个文件夹里加文件后，
 * 这里重新读实时目录、跟本地记录比对，只把新增的视频转存进自己的网盘，
 * 因此不需要重新换链接，也不会每次把整季重复转存一遍。
 */
object QuarkFolderSync {

    // 与网盘页保持一致：只认视频文件
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "ts", "m2ts", "mts",
        "rmvb", "rm", "webm", "3gp", "mpg", "mpeg", "ogv", "asf", "vob",
    )

    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /** 文件指纹：同名同大小视为同一个文件，换个分享链接也能对上。 */
    fun key(name: String, size: Long): String = "$name|$size"

    fun key(entry: QuarkApi.ShareEntry): String = key(entry.name, entry.size)

    /**
     * 递归收集分享里的视频。
     * 只换一次 stoken，逐层下钻（最多 6 层、最多 [maxFiles] 条），避免深目录把请求放大。
     */
    fun collectVideos(
        api: QuarkApi,
        folder: QuarkFolder,
        maxFiles: Int = 2000,
        maxDirs: Int = 300,
    ): List<QuarkApi.ShareEntry> {
        val session = api.shareSession(folder.url, folder.code)
        val out = ArrayList<QuarkApi.ShareEntry>()
        val seenKeys = HashSet<String>()
        val dirs = ArrayDeque<Pair<String, Int>>()
        dirs.add("0" to 0)
        val visitedDirs = HashSet<String>()
        var requests = 0
        while (dirs.isNotEmpty() && out.size < maxFiles && requests < maxDirs) {
            val (fid, depth) = dirs.removeFirst()
            if (!visitedDirs.add(fid)) continue
            requests++
            val entries = api.shareEntries(folder.url, folder.code, fid, session)
            entries.forEach { entry ->
                if (entry.isDir) {
                    if (depth < 6) dirs.add(entry.fid to depth + 1)
                } else if (isVideoName(entry.name)) {
                    if (seenKeys.add(key(entry))) out += entry
                }
            }
        }
        return out
    }

    data class SyncOutcome(
        /** 目录里的视频总数 */
        val found: Int,
        /** 本次新转存的数量 */
        val added: Int,
        /** 本次新转存文件的指纹，调用方合并进 syncedKeys */
        val addedKeys: Set<String>,
    )

    /**
     * 只转存本机没记录过的视频。分批提交（默认 60 条一批），
     * 一次几百条塞进一个转存任务容易被服务端拒或超时。
     *
     * @param existingNames 网盘根目录里已有的文件名。之前「保存整个分享」过的文件
     *   虽然没有同步记录，这里也直接跳过，避免重复转存出一堆同名副本。
     * @param onBatchSaved 每批成功后回调（用于立刻落盘记录，中途失败也不会重复转存）。
     */
    fun saveMissing(
        api: QuarkApi,
        folder: QuarkFolder,
        videos: List<QuarkApi.ShareEntry>,
        existingNames: Set<String> = emptySet(),
        batchSize: Int = 60,
        onBatchSaved: (Set<String>) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncOutcome {
        // 网盘里已有同名文件：视为已同步，只补记录，不再转存
        val alreadyThere = videos.filter { it.name in existingNames }
        val knownNow = folder.syncedKeys + alreadyThere.map { key(it) }
        val missing = videos.filter { key(it) !in knownNow }
        if (missing.isEmpty()) {
            return SyncOutcome(videos.size, 0, alreadyThere.map { key(it) }.toSet())
        }
        val addedKeys = HashSet<String>()
        var done = 0
        missing.chunked(batchSize).forEach { batch ->
            val count = api.saveShareItems(folder.url, folder.code, batch)
            if (count <= 0) throw IllegalError("转存失败：这一批没有文件成功")
            val batchKeys = batch.map { key(it) }.toSet()
            batch.forEach { addedKeys += key(it) }
            onBatchSaved(batchKeys)
            done += count
            onProgress(done, missing.size)
        }
        // 已有同名文件也要落进 syncedKeys，否则下次还会被当成新增
        addedKeys += alreadyThere.map { key(it) }
        return SyncOutcome(videos.size, done, addedKeys)
    }
}
