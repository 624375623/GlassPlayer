'use strict'
/**
 * 夸克网盘网页端接口（与手机版 QuarkApi.kt 同一套链路）：
 * 扫码拿 ticket → 用 ticket 换 Cookie → Cookie 调文件接口 → 拿转码 m3u8 / 下载直链。
 */
const crypto = require('crypto')

const UA =
  'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/94.0.4606.71 Safari/537.36 Core/1.94.225.400 QQBrowser/12.2.5544.400'
const CLIENT_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 ' +
  'Safari/537.36 Channel/pckk_other_ch'
const REFERER = 'https://pan.quark.cn/'
const DRIVE = 'https://drive-pc.quark.cn/1/clouddrive'
const DRIVE_ALT = 'https://drive.quark.cn/1/clouddrive'
const DRIVE_H = 'https://drive-h.quark.cn/1/clouddrive'

class QuarkError extends Error {
  constructor(message, code) {
    super(message)
    this.code = code || 'quark_error'
  }
}

function uuid() {
  return crypto.randomUUID()
}

async function timeoutFetch(url, options = {}, ms = 25000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

function mergeCookieString(base, extra) {
  const map = new Map()
  String(base || '')
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s.includes('='))
    .forEach((pair) => map.set(pair.slice(0, pair.indexOf('=')), pair.slice(pair.indexOf('=') + 1)))
  extra.forEach((raw) => {
    const pair = String(raw).split(';')[0].trim()
    if (!pair.includes('=') || pair.startsWith('=')) return
    const i = pair.indexOf('=')
    map.set(pair.slice(0, i), pair.slice(i + 1))
  })
  return [...map.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
}

class QuarkApi {
  constructor(store) {
    this.store = store
  }

  get cookie() {
    const c = this.store.settings.quarkCookie
    return c && c.trim() ? c : null
  }

  get loggedIn() {
    return !!this.cookie
  }

  requireCookie() {
    if (!this.cookie) throw new QuarkError('还没有登录夸克网盘', 'nologin')
    return this.cookie
  }

  absorbCookies(res) {
    if (!res || !res.headers) return
    let list = []
    if (typeof res.headers.getSetCookie === 'function') list = res.headers.getSetCookie()
    if (!list || !list.length) {
      const single = res.headers.get('set-cookie')
      if (single) list = [single]
    }
    if (!list.length) return
    const merged = mergeCookieString(this.store.settings.quarkCookie, list)
    if (merged && merged !== this.store.settings.quarkCookie) this.store.patchSettings({ quarkCookie: merged })
  }

  async request(method, url, { body, ua = UA, referer = REFERER, cookie, timeout = 25000 } = {}) {
    const headers = {
      'User-Agent': ua,
      Accept: 'application/json, text/plain, */*',
      'Accept-Language': 'zh-CN,zh;q=0.9',
      Referer: referer,
      Origin: 'https://pan.quark.cn',
    }
    const useCookie = cookie === undefined ? this.cookie : cookie
    if (useCookie) headers.Cookie = useCookie
    const init = { method, headers, redirect: 'follow' }
    if (body !== undefined) {
      const text = typeof body === 'string' ? body : JSON.stringify(body)
      headers['Content-Type'] = 'application/json'
      init.body = text
    }
    let res
    try {
      res = await timeoutFetch(url, init, timeout)
    } catch (e) {
      throw new QuarkError(
        e.name === 'AbortError' ? '网盘请求超时，请检查网络' : '网络请求失败，请检查网络后重试',
        'network'
      )
    }
    if (/drive(-pc|-h)?\.quark\.cn|pan\.quark\.cn/.test(url)) this.absorbCookies(res)
    const text = await res.text()
    if (res.status === 401 || res.status === 403) throw new QuarkError('登录已过期，请重新扫码登录', 'expired')
    if (res.status < 200 || res.status >= 300) {
      let detail = ''
      try {
        detail = JSON.parse(text).message || ''
      } catch (_) {}
      if (/size limit/i.test(detail)) {
        throw new QuarkError('这个文件超过网盘允许的下载体积（会员额度没有生效）', 'sizelimit')
      }
      throw new QuarkError(`网盘请求失败（HTTP ${res.status}）${detail ? '：' + detail : ''}`, 'http')
    }
    return text
  }

  async json(method, url, opts) {
    const text = await this.request(method, url, opts)
    let data
    try {
      data = JSON.parse(text)
    } catch (_) {
      throw new QuarkError('网盘返回了非 JSON 内容，可能被风控，请稍后重试', 'badjson')
    }
    this.ensureOk(data)
    return data
  }

  ensureOk(data) {
    const status = Number(data.status ?? 200)
    const code = Number(data.code ?? 0)
    if (status !== 200 || (code !== 0 && code !== undefined)) {
      const message = data.message && data.message !== 'ok' ? data.message : `请求失败（${status}）`
      if (status === 401 || code === 31001) throw new QuarkError('登录已过期，请重新扫码登录', 'expired')
      throw new QuarkError(message, 'biz')
    }
  }

  /* ================= 扫码登录 ================= */

  async requestQr() {
    const url =
      'https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=' + uuid()
    const data = JSON.parse(await this.request('GET', url, { cookie: null }))
    if (Number(data.status) !== 2000000) {
      throw new QuarkError(data.message || '获取二维码失败', 'qr')
    }
    const token = data.data && data.data.members && data.data.members.token
    if (!token) throw new QuarkError('二维码 token 为空', 'qr')
    const biz = encodeURIComponent('S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0')
    const scanUrl =
      'https://su.quark.cn/4_eMHBJ?token=' +
      encodeURIComponent(token) +
      '&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=' +
      biz
    return { token, scanUrl }
  }

  /** @returns {{state:'wait'|'ok'|'expired', message?:string}} */
  async pollQr(token) {
    const url =
      'https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token=' +
      encodeURIComponent(token) +
      '&request_id=' +
      uuid()
    const data = JSON.parse(await this.request('GET', url, { cookie: null }))
    const ticket = data.data && data.data.members && data.data.members.service_ticket
    if (ticket) {
      await this.exchangeCookie(ticket)
      return { state: 'ok' }
    }
    const status = Number(data.status)
    if (status === 2000000 || status === 50004001) return { state: 'wait' }
    if (status >= 50004000 && status <= 50004999) return { state: 'expired', message: '二维码已过期' }
    const message = String(data.message || '')
    if (message && message !== 'ok') return { state: 'error', message }
    return { state: 'wait' }
  }

  async exchangeCookie(ticket) {
    const url = `https://pan.quark.cn/account/info?st=${encodeURIComponent(ticket)}&lw=scan`
    const res = await timeoutFetch(
      url,
      { method: 'GET', headers: { 'User-Agent': UA, Referer: REFERER, Accept: 'application/json, text/plain, */*' }, redirect: 'manual' },
      20000
    )
    this.absorbCookies(res)
    try {
      await res.arrayBuffer()
    } catch (_) {}
    if (!this.cookie) throw new QuarkError('登录成功但没有拿到 Cookie，请重试', 'cookie')
    // 补一次带 Cookie 的请求，确保会话 cookie 轮换到位
    await this.fetchProfile().catch(() => null)
    return this.cookie
  }

  async fetchProfile() {
    if (!this.cookie) return null
    const text = await this.request('GET', 'https://pan.quark.cn/account/info', {
      ua: CLIENT_UA,
      referer: REFERER,
    })
    let data = {}
    try {
      data = JSON.parse(text)
    } catch (_) {
      return null
    }
    const d = data.data || {}
    const nickname =
      d.nickname || d.nick_name || d.nick || d.user_name || d.email || (this.store.settings.quarkUserName ?? null)
    const vip = d.member_type || d.vip_type || d.member?.type || null
    this.store.patchSettings({ quarkUserName: nickname || '夸克用户', quarkVip: vip ? String(vip) : null })
    return { nickname, vip }
  }

  logout() {
    this.store.patchSettings({ quarkCookie: null, quarkUserName: null, quarkVip: null })
  }

  /* ================= 文件 ================= */

  mapFile(item) {
    return {
      fid: String(item.fid || ''),
      name: item.file_name || item.name || '未命名',
      isDir: Number(item.dir) === 1 || item.dir === true || item.file_type === 'folder',
      size: Number(item.size) || 0,
      updatedAt: Number(item.updated_at) || Number(item.created_at) || 0,
      parentFid: String(item.pdir_fid || ''),
      fileType: item.file_type || '',
    }
  }

  async listDir(folderId = '0', page = 1, size = 100) {
    const url =
      `${DRIVE}/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=${encodeURIComponent(folderId)}` +
      `&_page=${page}&_size=${size}&_fetch_total=1&fetch_all_file=1&fetch_risk_file_name=1` +
      '&_sort=file_type:asc,updated_at:desc'
    let data
    try {
      data = await this.json('GET', url)
    } catch (e) {
      // drive-pc 偶发 400，换 drive 域名再试一次
      if (e.code === 'http' || e.code === 'badjson') {
        data = await this.json('GET', url.replace(DRIVE, DRIVE_ALT))
      } else throw e
    }
    const body = data.data || {}
    const list = Array.isArray(body.list) ? body.list.map((x) => this.mapFile(x)).filter((x) => x.fid) : []
    const total = Number(body.metadata && body.metadata._total) || list.length
    return { list, hasMore: page * size < total, total }
  }

  /** 流播：官方在线播放的转码 m3u8（CDN 是播放节点，不受下载通道限速）。 */
  async streamUrl(fid) {
    const bodyFor = (platform, pdir) => {
      const b = { fid, resolutions: 'low,normal,high,super,2k,4k', supports: 'fmp4_av,m3u8,dolby_vision' }
      if (platform) b.platform = 'pc'
      if (pdir) b.pdir_fid = '0'
      return b
    }
    const attempts = [
      { base: DRIVE_ALT, query: 'pr=ucpro&fr=pc', body: bodyFor(true, false), ua: CLIENT_UA },
      { base: DRIVE_ALT, query: 'pr=ucpro&fr=pc', body: bodyFor(false, false), ua: CLIENT_UA },
      { base: DRIVE_ALT, query: 'pr=ucpro&fr=pc', body: bodyFor(true, true), ua: CLIENT_UA },
      { base: DRIVE_ALT, query: 'pr=ucpro&fr=pc&uc_param_str=', body: bodyFor(true, false), ua: CLIENT_UA },
      {
        base: DRIVE_ALT,
        query: 'pr=ucpro&fr=pc&sys=win32&ve=2.5.20&uc_param_str=',
        body: bodyFor(true, false),
        ua: CLIENT_UA,
      },
      { base: DRIVE_ALT, query: 'pr=ucpro&fr=pc', body: bodyFor(true, false), ua: UA },
      { base: DRIVE, query: 'pr=ucpro&fr=pc', body: bodyFor(true, false), ua: CLIENT_UA },
    ]
    let lastError = new QuarkError('没有拿到转码播放地址', 'stream')
    for (const attempt of attempts) {
      try {
        const data = await this.json('POST', `${attempt.base}/file/v2/play/project?${attempt.query}`, {
          body: attempt.body,
          ua: attempt.ua,
        })
        const list = (data.data && data.data.video_list) || []
        for (const item of list) {
          const url = item && item.video_info && item.video_info.url
          if (url) return url
        }
        lastError = new QuarkError('转码播放地址为空，这个文件可能不支持在线转码', 'stream')
      } catch (e) {
        lastError = e
      }
    }
    throw lastError
  }

  async downloadUrl(fid) {
    let lastError = null
    for (const base of [DRIVE_ALT, DRIVE]) {
      for (const ua of [CLIENT_UA, UA]) {
        try {
          const data = await this.json('POST', `${base}/file/download?pr=ucpro&fr=pc&uc_param_str=`, {
            body: { fids: [fid] },
            ua,
          })
          const first = Array.isArray(data.data) ? data.data[0] : null
          if (first && first.download_url) return first.download_url
          lastError = new QuarkError('下载链接为空，文件可能已被删除', 'download')
        } catch (e) {
          lastError = e
        }
      }
    }
    throw lastError || new QuarkError('没有拿到下载链接', 'download')
  }

  /** 解析可播放地址：优先转码 m3u8，失败回退下载直链。 */
  async playable(fid, mode = 'stream') {
    if (mode === 'download') {
      return { url: await this.downloadUrl(fid), kind: 'file', mode: 'download' }
    }
    try {
      const url = await this.streamUrl(fid)
      return { url, kind: /\.m3u8(\?|$)/i.test(url) ? 'hls' : 'file', mode: 'stream' }
    } catch (e) {
      const url = await this.downloadUrl(fid)
      return { url, kind: 'file', mode: 'download', warning: e.message }
    }
  }

  /* ================= 分享 ================= */

  sharePwdId(url) {
    const m = /pan\.quark\.cn\/s\/([A-Za-z0-9]+)/.exec(String(url || ''))
    return m ? m[1] : null
  }

  passcodeOf(url, passcode) {
    if (passcode && passcode.trim()) return passcode.trim()
    const m = /[?&]pwd=([A-Za-z0-9]+)/.exec(String(url || ''))
    return m ? m[1] : ''
  }

  async shareToken(url, passcode) {
    const pwdId = this.sharePwdId(url)
    if (!pwdId) throw new QuarkError('不是有效的夸克分享链接', 'share')
    const pwd = this.passcodeOf(url, passcode)
    const referer = `https://pan.quark.cn/s/${pwdId}`
    const data = await this.json(
      'POST',
      `${DRIVE_H}/share/sharepage/token?pr=ucpro&fr=pc&uc_param_str=`,
      { body: { pwd_id: pwdId, passcode: pwd }, referer }
    )
    const stoken = data.data && data.data.stoken
    if (!stoken) throw new QuarkError('分享链接已失效或提取码错误', 'share')
    return { pwdId, pwd, stoken, referer }
  }

  async shareDetail(pwdId, stoken, pdirFid = '0', referer) {
    const out = []
    let page = 1
    let total = 0
    for (;;) {
      const url =
        `${DRIVE_H}/share/sharepage/detail?pr=ucpro&fr=pc&uc_param_str=&pwd_id=${pwdId}` +
        `&stoken=${encodeURIComponent(stoken)}&pdir_fid=${encodeURIComponent(pdirFid)}` +
        `&_page=${page}&_size=100&_fetch_total=1&_fetch_banner=1&_fetch_share=1` +
        '&_sort=file_type:asc,file_name:asc'
      const data = await this.json('GET', url, { referer: referer || `https://pan.quark.cn/s/${pwdId}` })
      const body = data.data || {}
      const list = body.list || []
      total = Number(body.metadata && body.metadata._total) || total
      for (const item of list) {
        const f = this.mapFile(item)
        if (!f.fid) continue
        out.push({
          ...f,
          fidToken: item.share_fid_token || item.token || '',
          shareName: item.share_fid_token ? undefined : '',
        })
      }
      if (list.length < 100) break
      page += 1
      if (page > 20) break
    }
    return { list: out, total: total || out.length }
  }

  /** 预览分享目录（不转存），支持逐层下钻。 */
  async sharePreview(url, passcode, pdirFid = '0') {
    const { pwdId, stoken, referer, pwd } = await this.shareToken(url, passcode)
    const detail = await this.shareDetail(pwdId, stoken, pdirFid, referer)
    return { ...detail, pwdId, stoken, passcode: pwd, pdirFid }
  }

  /** 把分享根目录（或指定 fid 列表）转存到自己网盘。 */
  async saveShare(url, passcode, toPdirFid = '0') {
    this.requireCookie()
    const { pwdId, stoken, referer } = await this.shareToken(url, passcode)
    const detail = await this.shareDetail(pwdId, stoken, '0', referer)
    const items = detail.list.filter((x) => x.fidToken)
    if (!items.length) throw new QuarkError('分享里没有可转存的文件', 'share')
    const data = await this.json('POST', `${DRIVE}/share/sharepage/save?pr=ucpro&fr=pc&uc_param_str=`, {
      body: {
        fid_list: items.map((x) => x.fid),
        fid_token_list: items.map((x) => x.fidToken),
        pwd_id: pwdId,
        stoken,
        to_pdir_fid: toPdirFid,
        scene: 'link',
      },
      referer,
    })
    const taskId = data.data && data.data.task_id
    if (!taskId) throw new QuarkError('转存任务创建失败', 'share')
    for (let i = 0; i < 40; i += 1) {
      await new Promise((r) => setTimeout(r, 1000))
      const task = await this.json(
        'GET',
        `${DRIVE}/task?pr=ucpro&fr=pc&uc_param_str=&task_id=${taskId}&retry_index=${i}`,
        { referer }
      )
      const status = Number(task.data && task.data.status)
      if (status === 2) return { count: items.length }
      if (status === 3) throw new QuarkError('转存失败：分享可能已失效', 'share')
    }
    throw new QuarkError('转存超时，稍后到网盘刷新看看', 'share')
  }
}

module.exports = { QuarkApi, QuarkError, CLIENT_UA, UA, REFERER }
