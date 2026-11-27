'use strict'
const { app, BrowserWindow, session, shell, Menu } = require('electron')
const path = require('path')
const fs = require('fs')

const { Store } = require('./store')
const { MediaServer } = require('./mediaServer')
const { register } = require('./ipc')

const IS_DEV = !!process.env.GP_DEV

// 开发模式(app.getName() = package.json 的 name)与打包版(productName)默认会用两个不同的
// userData 目录，导致打包后登录态、播放记录全丢。这里显式统一到 GlassPlayer。
// 命令行显式指定 --user-data-dir（无头测试用）时不覆盖。
if (!process.argv.some((a) => a.startsWith('--user-data-dir'))) {
  app.setPath('userData', path.join(app.getPath('appData'), 'GlassPlayer'))
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
  process.exit(0)
}

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')
app.commandLine.appendSwitch('disable-features', 'HardwareMediaKeyHandling')
app.commandLine.appendSwitch('enable-features', 'PlatformHEVCDecoderSupport')

let mainWindow = null
let store = null
let media = null

const ICON = path.join(__dirname, '..', 'build', 'icon.png')

function attachQuarkHeaders(quarkStore) {
  // 兜底：万一有请求绕过代理直连夸克 CDN，也把登录态与身份头补上
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['*://*.quark.cn/*', '*://*.myqcloud.com/*'] },
    (details, callback) => {
      const headers = { ...details.requestHeaders }
      const target = details.url
      if (/quark\.cn/i.test(target) && !/\/cas\//i.test(target) && !/uop\.quark\.cn/i.test(target)) {
        const cookie = quarkStore.settings.quarkCookie
        if (cookie) headers.Cookie = cookie
        headers.Referer = 'https://pan.quark.cn/'
        if (/quark-cloud-drive/i.test(headers['User-Agent'] || '') === false) {
          headers['User-Agent'] =
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
            'quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 ' +
            'Safari/537.36 Channel/pckk_other_ch'
        }
      }
      callback({ requestHeaders: headers })
    }
  )
}

async function createWindow() {
  const bounds = store.get('windowBounds') || { width: 1320, height: 860 }
  mainWindow = new BrowserWindow({
    width: bounds.width,
    height: bounds.height,
    x: bounds.x,
    y: bounds.y,
    minWidth: 1024,
    minHeight: 660,
    frame: false,
    show: false,
    backgroundColor: '#eef1f8',
    icon: fs.existsSync(ICON) ? ICON : undefined,
    title: 'GlassPlayer',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true,
      spellcheck: false,
      backgroundThrottling: false,
    },
  })

  mainWindow.once('ready-to-show', () => mainWindow.show())

  mainWindow.on('close', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      const b = mainWindow.getNormalBounds()
      store.set('windowBounds', b)
    }
    store.flush()
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.webContents.on('will-navigate', (event, url) => {
    const devUrl = process.env.GP_DEV
    if (devUrl && url.startsWith(devUrl)) return
    if (url.startsWith('file://')) return
    event.preventDefault()
    if (/^https?:/.test(url)) shell.openExternal(url)
  })

  mainWindow.on('enter-full-screen', () => mainWindow.webContents.send('gp:event', { type: 'window:fullscreen', payload: true }))
  mainWindow.on('leave-full-screen', () => mainWindow.webContents.send('gp:event', { type: 'window:fullscreen', payload: false }))
  mainWindow.on('maximize', () => mainWindow.webContents.send('gp:event', { type: 'window:max', payload: true }))
  mainWindow.on('unmaximize', () => mainWindow.webContents.send('gp:event', { type: 'window:max', payload: false }))

  if (process.env.GP_SHOT) {
    // 冒烟测试用：截一张图然后退出
    mainWindow.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        try {
          const jsFile = process.env.GP_SHOT_JS_FILE
          if (jsFile) {
            const code = fs.readFileSync(jsFile, 'utf8')
            const jsResult = await mainWindow.webContents.executeJavaScript(code, true)
            console.log('[shot-js]', typeof jsResult === 'string' ? jsResult : JSON.stringify(jsResult))
            await new Promise((r) => setTimeout(r, Number(process.env.GP_SHOT_JS_WAIT || 9000)))
          }
          const img = await mainWindow.webContents.capturePage()
          fs.writeFileSync(process.env.GP_SHOT, img.toPNG())
          console.log('[shot] saved', process.env.GP_SHOT)
        } catch (e) {
          console.error('[shot] failed', e.message)
        }
        app.quit()
      }, Number(process.env.GP_SHOT_DELAY || 3500))
    })
  }

  if (IS_DEV) {
    await mainWindow.loadURL(process.env.GP_DEV)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    await mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'), {
      hash: process.env.GP_SHOT_TAB || undefined,
    })
  }
}

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.focus()
  }
})

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null)
  // 机器上配了代理（Clash / 公司网关）时显式接管，海外资源站才连得上
  const proxyEnv =
    process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy
  if (proxyEnv) {
    const proxyRules = proxyEnv.replace(/^https?:\/\//i, '')
    session.defaultSession.setProxy({ proxyRules, proxyBypassRules: '<local>' }).catch(() => {})
  }
  store = new Store()
  media = new MediaServer(store)
  await media.start()
  attachQuarkHeaders(store)
  register({ store, media, getWindow: () => mainWindow })
  await createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (store) store.flush()
  if (media) media.stop()
  app.quit()
})

process.on('uncaughtException', (e) => {
  console.error('[main] uncaught', e)
})
