'use strict'
/** 本地视频库：扫描用户选定的文件夹，产出可播放的视频条目。 */
const fs = require('fs')
const path = require('path')

const VIDEO_EXT = new Set([
  '.mp4',
  '.mkv',
  '.webm',
  '.mov',
  '.m4v',
  '.avi',
  '.ts',
  '.flv',
  '.wmv',
  '.mpg',
  '.mpeg',
  '.rmvb',
  '.m2ts',
])

const SKIP_DIR = new Set([
  '$recycle.bin',
  'system volume information',
  'node_modules',
  '.git',
  'windows',
  'program files',
  'program files (x86)',
  'appdata',
])

function isVideo(name) {
  return VIDEO_EXT.has(path.extname(name).toLowerCase())
}

async function walk(root, out, state, depth = 0) {
  if (state.count >= 40000 || depth > 8) return
  let entries
  try {
    entries = await fs.promises.readdir(root, { withFileTypes: true })
  } catch (_) {
    return
  }
  for (const entry of entries) {
    if (state.count >= 40000) return
    const full = path.join(root, entry.name)
    if (entry.isDirectory()) {
      if (entry.name.startsWith('.')) continue
      if (SKIP_DIR.has(entry.name.toLowerCase())) continue
      await walk(full, out, state, depth + 1)
    } else if (entry.isFile() && isVideo(entry.name)) {
      let stat
      try {
        stat = await fs.promises.stat(full)
      } catch (_) {
        continue
      }
      state.count += 1
      out.push({
        key: full,
        path: full,
        name: entry.name,
        dir: root,
        size: stat.size,
        mtime: Math.round(stat.mtimeMs),
      })
    }
  }
}

async function scan(folders, onProgress) {
  const out = []
  const state = { count: 0 }
  for (let i = 0; i < folders.length; i += 1) {
    const folder = folders[i]
    if (!folder) continue
    if (onProgress) onProgress({ index: i, total: folders.length, folder })
    await walk(folder, out, state)
    if (onProgress) onProgress({ index: i + 1, total: folders.length, folder, found: out.length })
  }
  out.sort((a, b) => b.mtime - a.mtime)
  return out
}

module.exports = { scan, isVideo, VIDEO_EXT }
