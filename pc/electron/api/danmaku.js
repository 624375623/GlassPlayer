'use strict'
/**
 * 弹幕源（对齐手机版 DanmakuSources.kt）：
 * 1) B 站公开接口（番剧 / 影视正片 + 分集 cid → 弹幕 XML）
 * 2) 自建弹幕服务器（弹弹play v2 兼容协议）
 */
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

const { rank } = require('./match')

const QUALITY_TOKEN =
  /(2160p|1080p|720p|480p|4k|8k|hdr10|hdr|web[-._ ]?dl|web[-._ ]?rip|blu[-._ ]?ray|bdrip|dvdrip|remux|hevc|h265|x265|av1|hdtv|hdtc|hdrip|uhd|aac|ac3|ddp|dd\+|dts|truehd|atmos|flac|10bit|国语|粤语|国配|台配|中字|双语|简体|繁体|内封|外挂|完整版|未删减|高清|蓝光)/i

/** 从文件名里洗出可用于搜索的剧名：去掉扩展名、集数标记与清晰度等技术词。 */
function cleanTitle(name) {
  let base = String(name || '')
  // 防和谐用的竖线分隔（凡｜人修仙传）先拼回去，否则搜不到
  base = base.replace(/[｜|丨〡]/g, '')
  base = base.replace(/\.[A-Za-z0-9]{2,5}$/, '')
  const m = QUALITY_TOKEN.exec(base)
  if (m) base = base.slice(0, m.index)
  // S01E02 / EP02 / 第2集 / 结尾的 .01 —— 这些留着会让搜索命中不到正片
  base = base
    .replace(/[.\s_-]*[Ss]\d{1,2}[Ee]\d{1,4}/g, ' ')
    .replace(/[.\s_-]*(?:[Ee][Pp]?|[Ee])\s?\d{1,4}(?![0-9])/g, ' ')
    .replace(/第\s*[0-9一二三四五六七八九十百]+\s*[集话期]/g, ' ')
    .replace(/[.\s_-]+\d{1,3}$/g, ' ')
    // 年份：.2022. 这类分段不是片名，带着它搜索反而搜不到
    .replace(/[.\s_-]+(?:19|20)\d{2}(?=[.\s_-]|$)/g, ' ')
  return base
    .replace(/[._]+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .replace(/[（(【\[\s\-–—]+$/, '')
    .replace(/^(?:[\s\-–—、,，]+)/, '')
    .trim() || String(name || '')
}

/**
 * 弹幕数量上限：超过上限时按时间轴等间隔抽样，
 * 这样前半段密集的番不会把后半段全挤掉（等比保留整段覆盖）。
 */
function capItems(items, limit) {
  const max = Number(limit) > 0 ? Math.floor(Number(limit)) : 0
  if (!max || items.length <= max) return items
  const out = []
  const step = items.length / max
  for (let i = 0; i < max; i += 1) out.push(items[Math.min(items.length - 1, Math.floor(i * step))])
  return out
}

async function timeoutFetch(url, options = {}, ms = 20000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

const bili = {
  cookieCache: null,
  async biliCookie() {
    if (this.cookieCache) return this.cookieCache
    try {
      const res = await timeoutFetch('https://api.bilibili.com/x/frontend/finger/spi', {
        headers: { 'User-Agent': UA },
      })
      const json = await res.json()
      const d = json.data || {}
      const parts = []
      if (d.b_3) parts.push(`buvid3=${d.b_3}`)
      if (d.b_4) parts.push(`buvid4=${d.b_4}`)
      this.cookieCache = parts.join('; ') || null
      return this.cookieCache
    } catch (_) {
      return null
    }
  },

  async get(url, referer) {
    const cookie = await this.biliCookie()
    const headers = {
      'User-Agent': UA,
      Accept: 'application/json, text/plain, */*',
      Referer: referer || 'https://www.bilibili.com/',
      'Accept-Language': 'zh-CN,zh;q=0.9',
    }
    if (cookie) headers.Cookie = cookie
    const res = await timeoutFetch(url, { headers })
    const text = await res.text()
    return text
  },

  async getJson(url, referer) {
    let lastError = null
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const text = await this.get(url, referer)
      let json
      try {
        json = JSON.parse(text)
      } catch (_) {
        lastError = new Error('B 站返回了非 JSON 内容（多半被风控了），稍后再试')
        this.cookieCache = null
        continue
      }
      if (json.code === 0) return json
      lastError = new Error(`B 站接口失败：${json.message || 'code ' + json.code}`)
      if ([-412, -352, -799].includes(json.code) && attempt === 0) {
        this.cookieCache = null
        continue
      }
      break
    }
    throw lastError || new Error('B 站接口失败')
  },

  /** 只搜正片（番剧 / 国创 / 影视），不混入二创。 */
  async search(keyword) {
    const direct = parseBangumiLink(keyword)
    if (direct) return [direct]
    const out = []
    const errors = []
    let okCount = 0
    for (const type of ['media_bangumi', 'media_ft']) {
      try {
        const json = await this.getJson(
          `https://api.bilibili.com/x/web-interface/search/type?search_type=${type}&keyword=${encodeURIComponent(
            keyword
          )}&page=1`,
          'https://search.bilibili.com'
        )
        okCount += 1
        const arr = (json.data && json.data.result) || []
        for (const o of arr) {
          const seasonId = Number(o.season_id) || 0
          if (!seasonId) continue
          const typeName = o.season_type_name || (type === 'media_ft' ? '影视' : '番剧')
          const eps = Number(o.ep_size) || 0
          out.push({
            source: 'bili',
            id: `season:${seasonId}`,
            title: stripTags(o.title),
            subtitle: `${typeName}${eps ? ` · 共 ${eps} 集` : ''}${o.index_show ? ' · ' + stripTags(o.index_show) : ''}`,
            isSeason: true,
          })
        }
      } catch (e) {
        errors.push(e.message)
      }
    }
    const seen = new Set()
    const distinct = out.filter((x) => (seen.has(x.id) ? false : seen.add(x.id)))
    if (!distinct.length && !okCount && errors.length) throw new Error(`B 站正片检索失败：${errors[0]}`)
    // 按片名相似度排序：搜索接口的第一条经常是「弹速狂飙」「舞台剧采访篇」这类擦边结果
    const ranked = rank(distinct, keyword).filter((x) => x.score >= 25)
    if (ranked.length) return ranked
    // 名字后面挂的「动漫 / 电视剧」这类词会让 B 站搜不到，去掉再试一次
    const fallback = String(keyword || '')
      .replace(/\s*(动漫|动画|电视剧|电影|剧场版|剧集|全集|合集|高清|蓝光|4k|4k版)$/i, '')
      .trim()
    if (fallback && fallback !== keyword) return this.search(fallback)
    return ranked
  },

  async episodes(seasonId) {
    const json = await this.getJson(
      `https://api.bilibili.com/pgc/view/web/season?season_id=${seasonId}`,
      `https://www.bilibili.com/bangumi/play/ss${seasonId}`
    )
    const season = json.result || {}
    const seasonTitle = season.title || '番剧'
    const arr = season.episodes || []
    const extraWords = ['预告', 'PV', 'pv', '花絮', '特辑', '片花', '彩蛋', '宣传', '采访', '幕后', '抢先', '直拍', '开机', '看点']
    const EXTRA_BADGE = /(预告|花絮|特辑|片花|彩蛋|看点|片段|pv|先导|速看)/i
    const rows = []
    for (const ep of arr) {
      const cid = Number(ep.cid) || 0
      if (!cid) continue
      const longTitle = String(ep.long_title || '')
      const indexText = String(ep.title || '').trim()
      const badge = String(ep.badge || '')
      const num = Number(indexText)
      const seq = rows.length
      const isNumbered = Number.isFinite(num) && num > 0
      /**
       * B 站把预告片也塞进 episodes，且和正片共用同一个集号 title
       * （《凡人修仙传》154~201 集各有一条 badge="预告" 的重复条目，
       * 《三体》14 条正片后面跟着 14 条预告，long_title 里根本没有「预告」二字），
       * 只靠 long_title 判断必然漏掉，必须以 badge 为准。
       */
      const isExtra = EXTRA_BADGE.test(badge) || extraWords.some((w) => longTitle.includes(w) || indexText.includes(w))
      rows.push({
        index: isNumbered ? num : 9000 + seq,
        seq,
        isExtra,
        item: {
          source: 'bili',
          id: `cid:${cid}`,
          title: seasonTitle,
          subtitle: isNumbered
            ? `第 ${indexText} 集${longTitle ? ' · ' + longTitle : ''}`
            : `${indexText || '正片'}${longTitle ? ' · ' + longTitle : ''}`,
        },
      })
    }
    const cleaned = rows.filter((r) => !r.isExtra)
    const seen = new Set()
    return cleaned
      .sort((a, b) => a.index - b.index || a.seq - b.seq)
      .filter((r) => (seen.has(r.index) ? false : seen.add(r.index)))
      .map((r) => r.item)
  },

  async loadByCid(cid, limit) {
    const xml = await this.get(`https://comment.bilibili.com/${cid}.xml`, 'https://www.bilibili.com/')
    if (!xml.includes('<d ') && !xml.includes('<i>')) {
      throw new Error('B 站返回的内容不是弹幕 XML（可能被限流，稍后重试）')
    }
    const items = parse(xml)
    if (!items.length) throw new Error('这一集还没有弹幕')
    return capItems(items, limit)
  },

  async loadByBvid(bvid, limit) {
    const json = await this.getJson(
      `https://api.bilibili.com/x/player/pagelist?bvid=${bvid}`,
      `https://www.bilibili.com/video/${bvid}`
    )
    const list = json.data || []
    const first = list[0]
    if (!first) throw new Error('该视频没有可用分P')
    const xml = await this.get(`https://comment.bilibili.com/${first.cid}.xml`, `https://www.bilibili.com/video/${bvid}`)
    const items = parse(xml)
    if (!items.length) throw new Error('这个视频还没有弹幕')
    return { part: first.part || '', items: capItems(items, limit) }
  },
}

const server = {
  async search(baseUrl, keyword) {
    const base = String(baseUrl || '').trim().replace(/\/+$/, '')
    if (!base) throw new Error('还没有配置弹幕服务器地址')
    const res = await timeoutFetch(`${base}/api/v2/search/episodes?anime=${encodeURIComponent(keyword)}`, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
    })
    const json = await res.json()
    if (Number(json.errorCode || 0) !== 0) throw new Error(`服务器返回：${json.errorMessage || '未知错误'}`)
    const animes = json.animes || json.anime || []
    const out = []
    for (const anime of animes) {
      const episodes = anime.episodes || []
      for (const ep of episodes) {
        const id = Number(ep.episodeId) || 0
        if (!id) continue
        out.push({
          source: 'server',
          id: String(id),
          title: anime.animeTitle || '未命名',
          subtitle: ep.episodeTitle || '',
          isSeason: false,
        })
      }
    }
    return out
  },

  async load(baseUrl, episodeId, limit) {
    const base = String(baseUrl || '').trim().replace(/\/+$/, '')
    const sep = base.includes('?') ? '&' : '?'
    const res = await timeoutFetch(`${base}/api/v2/comment/${episodeId}${sep}withRelated=true`, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
    })
    const text = await res.text()
    const items = parse(text)
    if (!items.length) throw new Error('这个弹幕库是空的')
    return capItems(items, limit)
  },
}

/* ================= 解析 ================= */

function unescapeXml(raw) {
  return String(raw)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
    .trim()
}

function parse(text) {
  const body = String(text || '').trim()
  if (!body) return []
  return body.startsWith('<') ? parseXml(body) : parseJson(body)
}

function parseXml(xml) {
  const out = []
  const re = /<d\s+p="([^"]*)"[^>]*>([\s\S]*?)<\/d>/g
  let m
  while ((m = re.exec(xml))) {
    const params = m[1].split(',')
    const content = unescapeXml(m[2])
    const item = build(params, content)
    if (item) out.push(item)
  }
  return out.sort((a, b) => a.time - b.time)
}

function parseJson(text) {
  const out = []
  try {
    const root = JSON.parse(text)
    const arr = root.comments || root.data || []
    for (const o of arr) {
      const item = build(String(o.p || '').split(','), unescapeXml(o.m || ''))
      if (item) out.push(item)
    }
  } catch (_) {}
  return out.sort((a, b) => a.time - b.time)
}

/**
 * B 站弹幕 XML 的 p 属性顺序：时间, 模式, 字号, 颜色, 时间戳, 弹幕池, 用户, 行号。
 * 注意颜色是第 4 位（index 3），字号是第 3 位（index 2）——手机版这里取错了，
 * 导致所有弹幕都用字号 25 当颜色，渲染出来几乎是黑的。
 */
function build(params, content) {
  if (!content) return null
  const seconds = parseFloat(params[0])
  if (!Number.isFinite(seconds)) return null
  const modeNum = Number(params[1])
  const mode = modeNum === 4 ? 'bottom' : modeNum === 5 ? 'top' : 'scroll'
  const sizeNum = Number(params[2])
  const colorNum = Number(params[3])
  const color = Number.isInteger(colorNum) && colorNum >= 0 && colorNum <= 0xffffff ? colorNum : 0xffffff
  const scale = Number.isFinite(sizeNum) && sizeNum >= 12 && sizeNum <= 64 ? sizeNum / 25 : 1
  return { time: Math.round(seconds * 1000), text: content.slice(0, 200), mode, color, scale }
}

function stripTags(raw) {
  return String(raw || '')
    .replace(/<em class="keyword">/g, '')
    .replace(/<\/em>/g, '')
    .replace(/<[^>]+>/g, '')
    .trim()
}

function parseBangumiLink(raw) {
  const text = String(raw || '').trim()
  if (!/(ss|season_id=|ep|ep_id=|bilibili\.com)/i.test(text)) return null
  const season = /(?:ss|season_id=)(\d{2,9})/i.exec(text)
  if (season && /ss|season/i.test(text)) {
    return { source: 'bili', id: `season:${season[1]}`, title: `番剧 ${season[1]}`, subtitle: '链接导入', isSeason: true }
  }
  const ep = /(?:ep|ep_id=)(\d{4,12})/i.exec(text)
  if (!ep) return null
  return { source: 'bili', id: `cid:${ep[1]}`, title: '链接导入', subtitle: `EP ${ep[1]}`, isSeason: false }
}

module.exports = { bili, server, parse, cleanTitle, capItems }
