'use strict'
/**
 * 主进程能力统一入口：渲染层只通过 window.gp.invoke(method, ...args) 调用。
 * 所有网络请求都在主进程完成，渲染层不接触 Cookie，也不会遇到 CORS。
 */
const { ipcMain, dialog, shell, clipboard, BrowserWindow, app } = require('electron')
const fs = require('fs')
const path = require('path')
const QRCode = require('qrcode')

const { QuarkApi } = require('./api/quark')
const resources = require('./api/resources')
const danmakuApi = require('./api/danmaku')
const { tencent } = require('./api/tencent')
danmakuApi.tencent = tencent
const localLibrary = require('./localLibrary')

function fail(e) {
  return { ok: false, error: { message: (e && e.message) || '操作失败', code: (e && e.code) || 'error' } }
}
function done(data) {
  return { ok: true, data }
}

function register(ctx) {
  const { store, media, getWindow } = ctx
  const quark = new QuarkApi(store)

  const send = (type, payload) => {
    const win = getWindow()
    if (win && !win.isDestroyed()) win.webContents.send('gp:event', { type, payload })
  }

  const handlers = {
    /* ---------- 应用 / 窗口 ---------- */
    'app:info': () => ({
      version: app.getVersion(),
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      node: process.versions.node,
      platform: process.platform,
      userData: app.getPath('userData'),
      mediaPort: media.port,
    }),
    'window:minimize': () => getWindow()?.minimize(),
    'window:toggleMax': () => {
      const win = getWindow()
      if (!win) return false
      if (win.isMaximized()) win.unmaximize()
      else win.maximize()
      return win.isMaximized()
    },
    'window:close': () => getWindow()?.close(),
    'window:isMax': () => !!getWindow()?.isMaximized(),
    'window:fullscreen': (value) => {
      const win = getWindow()
      if (!win) return false
      win.setFullScreen(!!value)
      return win.isFullScreen()
    },

    /* ---------- 系统对话框 / 剪贴板 ---------- */
    'dialog:pickFolders': async () => {
      const win = getWindow()
      const res = await dialog.showOpenDialog(win, {
        title: '选择视频文件夹',
        properties: ['openDirectory', 'multiSelections'],
      })
      return res.canceled ? [] : res.filePaths
    },
    'dialog:pickFiles': async (filters) => {
      const win = getWindow()
      const res = await dialog.showOpenDialog(win, {
        title: '选择文件',
        properties: ['openFile', 'multiSelections'],
        filters: filters || [{ name: '视频', extensions: ['mp4', 'mkv', 'webm', 'mov', 'avi', 'ts', 'm4v', 'flv', 'wmv'] }],
      })
      return res.canceled ? [] : res.filePaths
    },
    'dialog:pickSubtitle': async () => {
      const win = getWindow()
      const res = await dialog.showOpenDialog(win, {
        title: '选择字幕文件',
        properties: ['openFile'],
        filters: [{ name: '字幕', extensions: ['srt', 'vtt', 'ass', 'ssa'] }],
      })
      return res.canceled ? [] : res.filePaths
    },
    'shell:openExternal': (url) => shell.openExternal(url),
    'shell:showItem': (p) => shell.showItemInFolder(p),
    'clipboard:write': (text) => clipboard.writeText(String(text ?? '')),
    'fs:readText': (p) => fs.readFileSync(p, 'utf8'),
    'fs:exists': (p) => fs.existsSync(p),

    /* ---------- 设置 / 数据 ---------- */
    'settings:get': () => store.settings,
    'settings:patch': (patch) => store.patchSettings(patch),
    'store:snapshot': () => ({
      settings: store.settings,
      positions: store.get('positions'),
      favorites: store.get('favorites'),
      cloudMeta: store.get('cloudMeta'),
      recent: store.get('recent'),
      hiddenCloud: store.get('hiddenCloudFolders'),
    }),
    'store:savePosition': ({ key, pos, duration }) => {
      store.savePosition(key, pos, duration)
      return true
    },
    'store:clearPosition': (key) => {
      store.clearPosition(key)
      return true
    },
    'store:resetPositions': () => {
      store.resetPositions()
      return true
    },
    'store:toggleFavorite': (key) => store.toggleFavorite(key),
    'store:videoPref': ({ key }) => store.videoPref(key),
    'store:saveVideoPref': ({ key, patch }) => store.saveVideoPref(key, patch),
    'store:saveCloudMeta': (meta) => {
      store.saveCloudMeta(meta)
      return true
    },
    'store:toggleHiddenCloud': (fid) => store.toggleHiddenCloud(fid),
    'store:saveCloudMetaBulk': (list) => {
      list.forEach((m) => store.saveCloudMeta(m))
      return true
    },

    /* ---------- 夸克网盘 ---------- */
    'quark:qr': async () => {
      const { token, scanUrl } = await quark.requestQr()
      const dataUrl = await QRCode.toDataURL(scanUrl, {
        margin: 1,
        width: 360,
        errorCorrectionLevel: 'M',
        color: { dark: '#0d1120', light: '#ffffff' },
      })
      return { token, scanUrl, dataUrl }
    },
    'quark:poll': (token) => quark.pollQr(token),
    'quark:status': async () => {
      if (!quark.loggedIn) return { loggedIn: false }
      try {
        const profile = await quark.fetchProfile()
        return { loggedIn: true, ...(profile || {}) }
      } catch (e) {
        return { loggedIn: !!(e.code !== 'expired' && quark.loggedIn), expired: e.code === 'expired', message: e.message }
      }
    },
    'quark:logout': () => {
      quark.logout()
      return true
    },
    'quark:list': async ({ fid = '0', page = 1, size = 100 }) => {
      const res = await quark.listDir(fid, page, size)
      res.list.forEach((f) =>
        store.saveCloudMeta({
          fid: f.fid,
          name: f.name,
          size: f.size,
          updatedAt: f.updatedAt,
          parentFid: fid,
          isDir: f.isDir,
        })
      )
      return res
    },
    'quark:play': async ({ fid, meta, mode }) => {
      const playMode = mode || store.settings.quarkPlayMode || 'stream'
      const result = await quark.playable(fid, playMode)
      if (meta) {
        const prev = store.cloudMeta(fid) || {}
        store.saveCloudMeta({ ...prev, ...meta, fid })
      }
      return {
        url: media.remote(result.url),
        kind: result.kind,
        mode: result.mode,
        warning: result.warning || null,
        raw: result.url,
      }
    },
    'quark:sharePreview': ({ url, passcode, pdirFid }) => quark.sharePreview(url, passcode, pdirFid || '0'),
    'quark:saveShare': async ({ url, passcode }) => {
      const res = await quark.saveShare(url, passcode)
      return res
    },

    /* ---------- 资源搜索 ---------- */
    'res:sources': () => resources.SOURCES,
    'res:search': async ({ sources, keyword }) => {
      const { list, errors } = await resources.searchAll(sources, keyword, {
        onProgress: (source, count) => send('res:progress', { source, count }),
        onBatch: (batch) => send('res:batch', batch),
      })
      return { list, errors }
    },
    'res:check': ({ links }) => resources.checkLinks(links),

    /* ---------- 弹幕 ---------- */
    'danmaku:search': async ({ source, keyword, serverUrl }) => {
      const kw = danmakuApi.cleanTitle(keyword)
      if (source === 'tencent') return danmakuApi.tencent.search(String(keyword || kw))
      if (source === 'server') return danmakuApi.server.search(serverUrl, kw)
      return danmakuApi.bili.search(kw)
    },
    'danmaku:episodes': async ({ seasonId, source }) => {
      const id = String(seasonId)
      if (source === 'tencent' || id.includes('|')) {
        const [cid, vid] = id.split('|')
        return danmakuApi.tencent.episodes(cid, vid)
      }
      return danmakuApi.bili.episodes(id.replace('season:', ''))
    },
    'danmaku:tencentEpisode': async ({ id, index }) => {
      const [cid, vid] = String(id).split('|')
      return tencent.episodeAt(cid, vid, Number(index))
    },
    'danmaku:load': async ({ source, id, serverUrl, limit }) => {
      if (source === 'tencent') return { items: await danmakuApi.tencent.load(String(id), limit) }
      if (source === 'server') return { items: await danmakuApi.server.load(serverUrl, id, limit) }
      if (String(id).startsWith('cid:')) {
        const items = await danmakuApi.bili.loadByCid(String(id).slice(4), limit)
        return { items }
      }
      if (String(id).startsWith('season:')) {
        const eps = await danmakuApi.bili.episodes(String(id).slice(7))
        if (!eps.length) throw new Error('这部剧没有可用集数')
        const items = await danmakuApi.bili.loadByCid(String(eps[0].id).slice(4), limit)
        return { items, episode: eps[0] }
      }
      return danmakuApi.bili.loadByBvid(String(id), limit)
    },
    'danmaku:cleanTitle': (name) => danmakuApi.cleanTitle(name),

    /* ---------- 本地媒体 ---------- */
    'lib:scan': async ({ folders }) => {
      const items = await localLibrary.scan(folders, (p) => send('lib:progress', p))
      return items
    },
    'lib:videoExt': () => [...localLibrary.VIDEO_EXT],
    'media:local': (p) => media.local(p),
  }

  ipcMain.handle('gp', async (_event, method, ...args) => {
    const fn = handlers[method]
    if (!fn) return fail(new Error(`未知的方法：${method}`))
    try {
      return done(await fn(...args))
    } catch (e) {
      console.error('[ipc]', method, e && e.message)
      return fail(e)
    }
  })
}

module.exports = { register }
