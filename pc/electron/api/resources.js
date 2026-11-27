'use strict'
/**
 * 公开分享资源搜索，与手机版 PublicResourceApis.kt / MipanApi.kt 一致：
 * - 觅盘 mipan.so：/api/token → POST /api/search（按网盘类型分桶）
 * - 谷哥搜 / KK网盘搜 / 盘盘搜：本地搜 HTML 页 + 全网搜 SSE，加密路径走谷哥搜 decrypt_urls 还原
 * - Pansou.de：POST /api/v1/search/stream（NDJSON）
 */
const { netFetch, readLines } = require('../netFetch')

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

const SOURCES = [
  { id: 'mipan', label: '觅盘', host: 'https://www.mipan.so' },
  { id: 'gugeso', label: '谷哥搜', host: 'https://gugeso.com' },
  { id: 'kkso', label: 'KK搜', host: 'https://kkso.net' },
  { id: 'panpansou', label: '盘盘搜', host: 'https://panpansou.com' },
  { id: 'pansoude', label: 'Pansou.de' },
]

const TYPE_LABEL = {
  quark: '夸克',
  baidu: '百度',
  aliyun: '阿里',
  '115': '115',
  uc: 'UC',
  xunlei: '迅雷',
  tianyi: '天翼',
  '123': '123',
  pikpak: 'PikPak',
  mobile: '移动',
}

function typeFromUrl(url) {
  if (/pan\.quark\.cn/.test(url)) return 'quark'
  if (/pan\.baidu\.com/.test(url)) return 'baidu'
  if (/aliyundrive\.com|alipan\.com/.test(url)) return 'aliyun'
  if (/cloud\.189\.cn/.test(url)) return 'tianyi'
  if (/xunlei\.com/.test(url)) return 'xunlei'
  if (/123pan\.com/.test(url)) return '123'
  if (/115\.com/.test(url)) return '115'
  return 'quark'
}

function dedupeKey(url) {
  return String(url || '')
    .split('#')[0]
    .split('?pwd=')[0]
    .replace(/\/+$/, '')
}

function unescapeHtml(s) {
  return String(s || '')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)))
    .trim()
}

function fetchWithTimeout(url, options = {}, ms = 25000) {
  return netFetch(url, { ...options, timeout: ms })
}

/* ================= 觅盘 ================= */

const mipan = {
  token: null,
  expireAt: 0,
  async getToken() {
    if (this.token && Date.now() < this.expireAt) return this.token
    const res = await fetchWithTimeout('https://www.mipan.so/api/token', {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
    })
    if (!res.ok) throw new Error(`觅盘 token 获取失败（HTTP ${res.status}）`)
    const json = await res.json()
    if (!json.token) throw new Error('觅盘 token 为空')
    const ttl = Number(json.ttl) || 7200
    this.token = json.token
    this.expireAt = Date.now() + Math.max(60, ttl - 300) * 1000
    return this.token
  },

  async search(keyword) {
    const token = await this.getToken()
    const res = await fetchWithTimeout('https://www.mipan.so/api/search', {
      method: 'POST',
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        'Content-Type': 'application/json; charset=utf-8',
        'X-Mipan-Token': token,
      },
      body: JSON.stringify({ kw: keyword }),
    })
    const json = await res.json().catch(() => null)
    if (!res.ok || !json) throw new Error(`觅盘搜索失败（HTTP ${res.status}）`)
    if (Number(json.code ?? 0) !== 0) throw new Error(json.message || '觅盘搜索失败')
    const data = json.data || {}
    const buckets = data.merged_by_type || {}
    const out = []
    for (const [type, arr] of Object.entries(buckets)) {
      if (!Array.isArray(arr)) continue
      for (const item of arr) {
        const url = String(item.url || '').trim()
        if (!url) continue
        out.push({
          type: TYPE_LABEL[type] ? type : typeFromUrl(url),
          url,
          password: String(item.password || '').trim(),
          note: String(item.note || '').trim() || '未命名资源',
          datetime: item.datetime || '',
          site: 'mipan',
          siteLabel: '觅盘',
          extra: item.source || '',
        })
      }
    }
    return out
  },

  async checkLinks(links) {
    if (!links.length) return {}
    const token = await this.getToken()
    const out = {}
    for (let i = 0; i < links.length; i += 50) {
      const batch = links.slice(i, i + 50)
      try {
        const res = await fetchWithTimeout(
          'https://www.mipan.so/api/check/links',
          {
            method: 'POST',
            headers: {
              'User-Agent': UA,
              Accept: 'application/json',
              'Content-Type': 'application/json; charset=utf-8',
              'X-Mipan-Token': token,
            },
            body: JSON.stringify({
              items: batch.map((l) => ({ url: l.url, disk_type: l.type || 'quark' })),
            }),
          },
          30000
        )
        const json = await res.json().catch(() => null)
        const results = (json && json.results) || []
        for (const item of results) {
          const state = String(item.state || '').toLowerCase()
          const mapped = /valid|locked|ok|alive/.test(state)
            ? 'valid'
            : /invalid|bad|dead|expired|deleted|not_found|failed/.test(state)
              ? 'invalid'
              : 'unknown'
          if (item.url) out[item.url] = mapped
          if (item.normalized_url) out[dedupeKey(item.normalized_url)] = mapped
        }
      } catch (_) {}
      batch.forEach((l) => {
        if (!(l.url in out)) out[l.url] = 'unknown'
      })
    }
    return out
  },
}

/* ================= 链接有效性 ================= */

/**
 * 夸克分享是否还活着：直接问夸克的 sharepage/token 接口（不需要登录态）。
 * 返回码含义：
 *   0     正常
 *   41007 提取码错误  → 给的码不对，等同于打不开，算失效
 *   41008 需要提取码  → 链接还活着，只是资源站没给码，保留但标「未验证」
 *   41006 分享不存在 / 41005 已取消 / 41010 违规 / 41031 分享者封禁 → 失效
 */
const LINK_STATE_TTL = 12 * 60 * 1000
const linkCache = new Map()
const INVALID_CODES = new Set([41004, 41005, 41006, 41009, 41010, 41011, 41031])

function quarkPwdId(url) {
  const m = /pan\.quark\.cn\/s\/([A-Za-z0-9]+)/.exec(String(url || ''))
  return m ? m[1] : ''
}

function passcodeOf(url, passcode) {
  if (passcode && String(passcode).trim()) return String(passcode).trim()
  const m = /[?&]pwd=([A-Za-z0-9]+)/.exec(String(url || ''))
  return m ? m[1] : ''
}

async function quarkState(url, password) {
  const pwdId = quarkPwdId(url)
  // 标成夸克但不是 pan.quark.cn/s/ 的（第三方镜像站导流页），我们既转存不了也播不了
  if (!pwdId) return 'invalid'
  const cacheKey = `${pwdId}:${password || ''}`
  const hit = linkCache.get(cacheKey)
  if (hit && Date.now() - hit.at < LINK_STATE_TTL) return hit.state
  let state = 'unknown'
  try {
    // 走 Node 的 fetch：夸克接口在国内可直连，
    // 用 Electron 网络栈会被代理规则拦成 ERR_BLOCKED_BY_CLIENT
    const res = await fetch(
      'https://drive-h.quark.cn/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc&uc_param_str=',
      {
        method: 'POST',
        headers: {
          'User-Agent': UA,
          'Content-Type': 'application/json; charset=utf-8',
          Referer: `https://pan.quark.cn/s/${pwdId}`,
        },
        body: JSON.stringify({ pwd_id: pwdId, passcode: password || '' }),
        signal: AbortSignal.timeout(12000),
      }
    )
    const json = await res.json().catch(() => null)
    const code = json ? Number(json.code) : NaN
    if (process.env.GP_DEBUG_LINKS) {
      console.log('[link]', url.slice(0, 56), 'http', res.status, 'code', code, json ? json.message : 'no-json')
    }
    if (code === 0 && json.data && json.data.stoken) state = 'valid'
    else if (code === 41008) state = 'unknown'
    else if (INVALID_CODES.has(code) || code === 41007) state = 'invalid'
    else state = 'unknown'
  } catch (_) {
    if (process.env.GP_DEBUG_LINKS) console.log('[link] err', url.slice(0, 56), _.message)
    return 'unknown'
  }
  linkCache.set(cacheKey, { at: Date.now(), state })
  return state
}

/** 批量校验（并发 8，带 12 分钟缓存）。返回 { [url]: 'valid' | 'invalid' | 'unknown' } */
async function checkLinks(links) {
  const list = (links || []).filter((l) => l && l.url)
  if (!list.length) return {}
  const out = {}
  let cursor = 0
  const workers = Array.from({ length: Math.min(8, list.length) }, async () => {
    while (cursor < list.length) {
      const item = list[cursor]
      cursor += 1
      const type = item.type || typeFromUrl(item.url)
      const state = type === 'quark' ? await quarkState(item.url, passcodeOf(item.url, item.password)) : 'unknown'
      out[item.url] = state
      out[dedupeKey(item.url)] = state
    }
  })
  await Promise.all(workers)
  return out
}

/* ================= 谷哥搜 / KK / 盘盘搜 ================= */

async function searchLocalHtml(host, siteLabel, keyword) {
  const url = `${host}/s/${encodeURIComponent(keyword)}.html`
  const res = await fetchWithTimeout(url, {
    headers: {
      'User-Agent': UA,
      Accept: 'text/html,application/xhtml+xml',
      Referer: `${host}/`,
      'Accept-Language': 'zh-CN,zh;q=0.9',
    },
  })
  if (!res.ok) return []
  const html = await res.text()
  if (!html) return []
  const out = []
  const regex = /copyText\([^,]*,'([\s\S]*?)','(https?:\/\/[^']+)','([^']*)'\)/g
  let m
  while ((m = regex.exec(html))) {
    const title = unescapeHtml(m[1].replace(/\\\//g, '/'))
    const rawUrl = unescapeHtml(m[2].replace(/\\\//g, '/'))
    if (!/^http/.test(rawUrl)) continue
    const type = typeFromUrl(rawUrl)
    if (type !== 'quark') continue
    const start = m.index
    const blockStart = Math.max(
      html.lastIndexOf('<div class="g-result-item"', start),
      html.lastIndexOf('<div class="item"', start),
      start - 2000
    )
    const block = html.slice(Math.max(blockStart, 0), start)
    const dateMatch =
      [...block.matchAll(/data-date=["']([^"']+)["']/g)].pop() ||
      [...block.matchAll(/class=["']type time["']>([^<]+)/g)].pop()
    const date = dateMatch ? dateMatch[1] : ''
    let password = unescapeHtml(m[3])
    if (!password) {
      const pm = /[?&]pwd=([A-Za-z0-9]+)/.exec(rawUrl)
      if (pm) password = pm[1]
    }
    out.push({
      type,
      url: rawUrl,
      password,
      note: title || '未命名资源',
      datetime: date,
      site: siteLabel,
      siteLabel,
      extra: '本地搜',
    })
  }
  const seen = new Set()
  return out.filter((x) => (seen.has(x.url) ? false : seen.add(x.url)))
}

async function decryptUrls(encrypted) {
  const out = []
  for (let i = 0; i < encrypted.length; i += 50) {
    const batch = encrypted.slice(i, i + 50)
    const body = batch.map((u) => `urls%5B%5D=${encodeURIComponent(u)}`).join('&')
    try {
      const res = await fetchWithTimeout('https://gugeso.com/api/other/decrypt_urls', {
        method: 'POST',
        headers: {
          'User-Agent': UA,
          'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8',
          Referer: 'https://gugeso.com/',
        },
        body,
      })
      const json = await res.json()
      const urls = (json.data && json.data.urls) || []
      for (let k = 0; k < batch.length; k += 1) out.push(urls[k] || null)
    } catch (_) {
      batch.forEach(() => out.push(null))
    }
  }
  return out
}

async function searchSse(host, siteLabel, keyword, onProgress) {
  const url =
    `${host}/api/other/web_search?title=${encodeURIComponent(keyword)}` + '&is_type=0&is_show=0&batch=1'
  const res = await fetchWithTimeout(
    url,
    {
      headers: {
        'User-Agent': UA,
        Accept: 'text/event-stream',
        Referer: `${host}/`,
        'Accept-Language': 'zh-CN,zh;q=0.9',
      },
    },
    30000
  )
  if (!res.ok) throw new Error(`${siteLabel} 搜索失败（HTTP ${res.status}）`)
  const raw = []
  await readLines(res, (line) => {
    if (!line.startsWith('data:')) return true
    const payload = line.slice(5).trim()
    if (payload.includes('[DONE]')) return false
    let json
    try {
      json = JSON.parse(payload)
    } catch (_) {
      return true
    }
    if (json.type === 'progress' || json.type === 'cache_start') return true
    const title = json.title || json.name || ''
    const link = json.url || ''
    if (!title || !link) return true
    if (json.check_valid === false) return true
    raw.push({
      title,
      url: link,
      password: json.code || json.password || '',
      datetime: json.datetime || new Date().toISOString().slice(0, 19),
      isType: Number(json.is_type) || 0,
    })
    if (raw.length % 20 === 0 && onProgress) onProgress(raw.length)
    return true
  })

  if (!raw.length) return []
  const encryptedIdx = raw.map((x, i) => (/^http/.test(x.url) ? -1 : i)).filter((i) => i >= 0)
  if (encryptedIdx.length) {
    const decrypted = await decryptUrls(encryptedIdx.map((i) => raw[i].url))
    encryptedIdx.forEach((idx, k) => {
      raw[idx].url = decrypted[k] || ''
    })
  }
  const panType = (t) => (t === 1 ? 'baidu' : t === 2 ? 'xunlei' : t === 3 ? 'uc' : 'quark')
  return raw
    .filter((x) => /^http/.test(x.url))
    .map((x) => ({
      type: panType(x.isType),
      url: x.url,
      password: x.password || '',
      note: x.title,
      datetime: x.datetime,
      site: siteLabel,
      siteLabel,
      extra: '全网搜',
    }))
}

/* ================= Pansou.de ================= */

async function searchPansouDe(keyword, onProgress) {
  const res = await fetchWithTimeout(
    'https://pansou.de/api/v1/search/stream',
    {
      method: 'POST',
      headers: { 'User-Agent': UA, 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json' },
      body: JSON.stringify({ keyword, platform: 'quark', maxResults: 100 }),
    },
    40000
  )
  if (!res.ok) throw new Error(`Pansou.de 搜索失败（HTTP ${res.status}）`)
  const out = []
  await readLines(res, (line) => {
    if (!line.trim()) return true
    let json
    try {
      json = JSON.parse(line)
    } catch (_) {
      return true
    }
    if (json.event === 'done') return false
    if (json.event !== 'candidate') return true
    const results = json.results || []
    for (const item of results) {
      if (out.length >= 100) return false
      const url = item.url || ''
      if (!/^http/.test(url)) continue
      out.push({
        type: typeFromUrl(url),
        url,
        password: item.password || '',
        note: item.title || '未命名资源',
        datetime: item.datetime || '',
        site: 'pansoude',
        siteLabel: 'Pansou.de',
        extra: item.source || '',
      })
    }
    if (onProgress) onProgress(out.length)
    return true
  })
  return out
}

/* ================= 聚合 ================= */

async function searchOne(sourceId, keyword, onProgress) {
  switch (sourceId) {
    case 'mipan':
      return mipan.search(keyword)
    case 'gugeso':
      return searchSse('https://gugeso.com', '谷哥搜', keyword, onProgress).then(async (remote) => {
        const local = await searchLocalHtml('https://gugeso.com', '谷哥搜', keyword).catch(() => [])
        return dedupe([...local, ...remote])
      })
    case 'kkso':
      return searchSse('https://kkso.net', 'KK搜', keyword, onProgress).then(async (remote) => {
        const local = await searchLocalHtml('https://kkso.net', 'KK搜', keyword).catch(() => [])
        return dedupe([...local, ...remote])
      })
    case 'panpansou':
      return searchSse('https://panpansou.com', '盘盘搜', keyword, onProgress).then(async (remote) => {
        const local = await searchLocalHtml('https://panpansou.com', '盘盘搜', keyword).catch(() => [])
        return dedupe([...local, ...remote])
      })
    case 'pansoude':
      return searchPansouDe(keyword, onProgress)
    default:
      return []
  }
}

function dedupe(list) {
  const seen = new Set()
  const out = []
  for (const item of list) {
    const key = dedupeKey(item.url)
    if (!key || seen.has(key)) continue
    seen.add(key)
    out.push(item)
  }
  return out
}

/** 并行搜索多个来源，一边完成一边回调。 */
async function searchAll(sourceIds, keyword, { onProgress, onBatch } = {}) {
  const results = []
  const errors = []
  await Promise.all(
    sourceIds.map(async (id) => {
      const meta = SOURCES.find((s) => s.id === id)
      try {
        const list = await searchOne(id, keyword, (n) => onProgress && onProgress(id, n))
        results.push(...list)
        if (onBatch) onBatch({ source: id, label: meta ? meta.label : id, list, total: results.length })
      } catch (e) {
        errors.push({ source: id, label: meta ? meta.label : id, message: e.message || String(e) })
      }
    })
  )
  return { list: results, errors }
}

module.exports = { SOURCES, TYPE_LABEL, searchAll, searchOne, mipan, dedupeKey, checkLinks }
