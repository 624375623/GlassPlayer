package com.glassplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * SAF 文件夹字幕扫描。
 *
 * 不依赖 androidx.documentfile（工程没有这个依赖），直接用 DocumentsContract 查询子文档。
 * 目录层级 / 数量都设上限：用户可能误选整个网盘或 Download 根目录，递归几千个节点会把
 * 主线程外的扫描拖到几十秒，也会把字幕库撑爆。
 */
object SubtitleFolderScanner {

    data class ScanResult(
        val rootName: String,
        val files: List<SubtitleFile>,
        val truncated: Boolean,
    )

    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa")
    private const val MAX_DIRS = 160
    private const val MAX_FILES = 300

    fun isSubtitleName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in subtitleExtensions

    fun scan(context: Context, treeUri: Uri): ScanResult {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return ScanResult("字幕文件夹", emptyList(), false)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(context, rootDocUri) ?: "字幕文件夹"

        val files = ArrayList<SubtitleFile>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(rootId to "")
        var visitedDirs = 0
        var truncated = false

        while (pending.isNotEmpty() && !truncated) {
            val (dirId, path) = pending.removeLast()
            visitedDirs++
            if (visitedDirs > MAX_DIRS) {
                truncated = true
                break
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val cursor = runCatching {
                resolver.query(childrenUri, projection, null, null, null)
            }.getOrNull() ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (files.size >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) {
                        val childPath = if (path.isBlank()) name else "$path/$name"
                        pending.add(id to childPath)
                    } else if (isSubtitleName(name)) {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        files += SubtitleFile(
                            uri = documentUri.toString(),
                            name = name,
                            folder = path,
                        )
                    }
                }
            }
        }

        return ScanResult(
            rootName = rootName,
            files = files.distinctBy { it.uri }.sortedBy { it.name.lowercase() },
            truncated = truncated,
        )
    }

    private fun queryName(context: Context, documentUri: Uri): String? = runCatching {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
