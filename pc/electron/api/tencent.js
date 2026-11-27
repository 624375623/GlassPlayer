'use strict'
/**
 * 腾讯视频弹幕（对齐手机版 TencentDanmakuSource.kt）
 *
 * 链路：
 * 1) 关键词 → 站点检索（site:v.qq.com）拿到 `v.qq.com/x/cover/{cid}/{vid}.html`；
 * 2) `m.v.qq.com/play.html?cid=&vid=` 页面里的 "video_ids" 即整季所有集的 vid；
 * 3) `vv.video.qq.com/getinfo` 拿每集标题与时长，剔掉预告/花絮并按集号排序；
 * 4) `dm.video.qq.com/barrage/base/{vid}` 给分段索引（每 30 秒一段）；
 * 5) `dm.video.qq.com/barrage/segment/{vid}/{段名}` 给该段弹幕。
 *
 * 与手机版的差异：手机版依赖必应 RSS，国内直连经常被重定向且不遵守 site:，
 * 这里改成 360 站内检索（国内可直连），必应作备用；段索引缺失时用 getinfo 的时长兜底。
 */

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
const { scoreTitle, seasonNumber } = require('./match')
const REFERER = 'https://v.qq.com/'
const TOTAL_LIMIT = 10000
const MIN_PER_SEGMENT = 8
const SEGMENT_PARALLEL = 6
const EPISODE_PARALLEL = 12
const MIN_EPISODE_SECONDS = 300
const SEGMENT_MS = 30000

const COVER_RE = /v\.qq\.com\/x\/cover\/([a-z0-9]+)\/([a-z0-9]+)/gi
const EXTRA_WORDS = ['预告', '花絮', '特辑', '片花', '彩蛋', '宣传', '采访', '幕后', '抢先', '直拍', '彩排', '开机']
const SEARCH_HINTS = ['动漫', '电视剧', '在线观看', '电影', '第1集', '全集', '综艺', '纪录片']

async function httpText(url, referer = REFERER, ms = 15000) {
  const res = await fetch(url, {
    headers: {
      'User-Agent': UA,
      Referer: referer,
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9',
    },
    redirect: 'follow',
    signal: AbortSignal.timeout(ms),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return text
}

async function mapLimit(list, limit, fn) {
  const out = new Array(list.length)
  let cursor = 0
  const workers = Array.from({ length: Math.min(limit, list.length) }, async () => {
    while (cursor < list.length) {
      const i = cursor
      cursor += 1
      try {
        out[i] = await fn(list[i], i)
      } catch (_) {
        out[i] = null
      }
    }
  })
  await Promise.all(workers)
  return out
}

/* ---------------- 搜索 ---------------- */

/** 关键词变体：去掉季数 / 集数 / 清晰度等修饰，再退到首段。 */
function queryVariants(keyword) {
  const raw = String(keyword || '').trim()
  if (!raw) return []
  const cleaned = raw
    .replace(/[《》【】()（）[\]·:：,，.。!！?？/|]/g, ' ')
    .replace(/第[一二三四五六七八九十百0-9]+季/g, ' ')
    .replace(/season\s*[0-9]+/gi, ' ')
    .replace(/第[一二三四五六七八九十百0-9]+[集话]/g, ' ')
    .replace(/(ep|e)\s*[0-9]+/gi, ' ')
    .replace(/(高清|蓝光|1080p|2160p|4k|8k|完整版|未删减|国语|粤语|中字|双语)/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  const out = [raw]
  if (cleaned && cleaned !== raw) out.push(cleaned)
  const head = cleaned.split(/[\s\-_.]+/).find((x) => x.length >= 2)
  if (head && head !== cleaned && head !== raw) out.push(head)
  return [...new Set(out)].slice(0, 3)
}

/** 站点检索：360 优先（国内直连且遵守 site:），必应兜底。 */
const ENGINES = [
  (q) => `https://www.so.com/s?q=${encodeURIComponent(`site:v.qq.com ${q}`)}`,
  (q) => `https://cn.bing.com/search?q=${encodeURIComponent(`site:v.qq.com ${q}`)}`,
]

async function engineSearch(query) {
  for (const build of ENGINES) {
    try {
      const html = await httpText(build(query), 'https://www.so.com/', 12000)
      const hits = []
      const seen = new Set()
      let m
      COVER_RE.lastIndex = 0
      while ((m = COVER_RE.exec(html))) {
        if (seen.has(m[1])) continue
        seen.add(m[1])
        hits.push({ cid: m[1], vid: m[2] })
      }
      if (hits.length) return hits
    } catch (_) {}
  }
  return []
}

/** 用 getinfo 拿真实剧名与时长，顺带过滤掉非正片。 */
async function metaOf(vid) {
  const body = await httpText(
    `https://vv.video.qq.com/getinfo?vids=${vid}&platform=101001&charge=0&otype=json`,
    REFERER,
    12000
  )
  const json = body.slice(body.indexOf('QZOutputJson=') + 'QZOutputJson='.length).trim().replace(/;$/, '')
  const obj = JSON.parse(json)
  const vi = (obj.vl && obj.vl.vi && obj.vl.vi[0]) || null
  if (!vi || !vi.ti) return null
  return { vid, title: String(vi.ti).trim(), duration: Number(vi.td) || 0 }
}

function cleanShowName(title) {
  const plain = String(title || '')
    .replace(/_腾讯视频$/, '')
    .replace(/_\d{1,4}$/, '')
    .replace(/第\s*[0-9一二三四五六七八九十百]+\s*[集话]$/, '')
    .trim()
  return plain || '腾讯视频'
}

function relevance(title, keyword) {
  return scoreTitle(title, keyword)
}

const episodeCache = new Map()

async function videoIds(cid, vid) {
  const html = await httpText(`https://m.v.qq.com/play.html?cid=${cid}&vid=${vid}`, REFERER, 15000)
  const block = /"video_ids":\[([^\]]*)\]/.exec(html)
  const ids = block ? [...block[1].matchAll(/"([a-z0-9]{8,12})"/g)].map((m) => m[1]) : []
  return [...new Set(ids)]
}

const tencent = {
  async search(keyword) {
    const direct = parseLink(keyword)
    if (direct) return [direct]

    const variants = queryVariants(keyword)
    if (!variants.length) throw new Error('请输入要搜索的剧名')
    const base = variants[0]

    const queries = [base, `${base} 动漫`, `${base} 电视剧`, `${base} 在线观看`]
    let batches = await Promise.all(queries.map((q) => engineSearch(q).catch(() => [])))
    let hits = []
    const seenCid = new Set()
    for (const batch of batches) {
      for (const hit of batch) {
        if (seenCid.has(hit.cid)) continue
        seenCid.add(hit.cid)
        hits.push(hit)
      }
    }
    if (hits.length < 3) {
      const more = [...SEARCH_HINTS.slice(3).map((h) => `${base} ${h}`), ...variants.slice(1)]
      batches = await Promise.all(more.map((q) => engineSearch(q).catch(() => [])))
      for (const batch of batches) {
        for (const hit of batch) {
          if (seenCid.has(hit.cid)) continue
          seenCid.add(hit.cid)
          hits.push(hit)
        }
      }
    }
    if (!hits.length) {
      throw new Error('没找到腾讯视频的对应剧集，可以只留剧名，或直接粘贴 v.qq.com 播放页链接')
    }

    // 只查前 8 个候选的元信息，够排序也够快
    const metas = await mapLimit(hits.slice(0, 8), 4, (hit) => metaOf(hit.vid))
    const rows = []
    const seenVid = new Set()
    metas.forEach((meta, i) => {
      const hit = hits[i]
      if (!meta) return
      if (meta.duration > 0 && meta.duration < MIN_EPISODE_SECONDS) return
      if (EXTRA_WORDS.some((w) => meta.title.includes(w))) return
      // 同一部片常被不同 cid 重复收录（电影尤其明显），按 vid 去重
      if (seenVid.has(hit.vid)) return
      seenVid.add(hit.vid)
      rows.push({
        source: 'tencent',
        id: `${hit.cid}|${hit.vid}`,
        title: cleanShowName(meta.title),
        subtitle: '腾讯视频 · 点击加载弹幕',
        isSeason: true,
        score: relevance(cleanShowName(meta.title), keyword),
        season: seasonNumber(cleanShowName(meta.title)),
      })
    })
    if (!rows.length && metas.some(Boolean)) return []
    return (rows.length ? rows : hits.slice(0, 6).map((hit) => ({
      source: 'tencent',
      id: `${hit.cid}|${hit.vid}`,
      title: base,
      subtitle: '腾讯视频 · 点击加载弹幕',
      isSeason: true,
      score: 30,
    })))
      .sort((a, b) => b.score - a.score || a.title.length - b.title.length)
      .slice(0, 12)
  },

  /** 封面 → 整季集数（按集号排序，剔预告/花絮）。 */
  /** 按集号直取一集：video_ids 的顺序就是集号顺序，只查一次 getinfo 校验，秒回。 */
  async episodeAt(cid, vid, index) {
    const ids = await videoIds(cid, vid)
    const target = ids[index - 1]
    if (!target) return null
    const meta = await metaOf(target)
    if (!meta) return null
    // 秒级片长的多是短剧切片/预告，不要拿来配弹幕
    if (meta.duration > 0 && meta.duration < MIN_EPISODE_SECONDS) return null
    // 标题里带集号却没对上（中间混了预告/花絮），交回完整 episodes 处理
    const idx = episodeIndexOf(meta.title)
    if (idx && idx !== index) return null
    return {
      source: 'tencent',
      id: target,
      title: cleanShowName(meta.title),
      subtitle: `第 ${String(index).padStart(2, '0')} 集`,
    }
  },

  async episodes(cid, vid) {
    const cached = episodeCache.get(cid)
    if (cached && Date.now() - cached.at < 10 * 60 * 1000) return cached.list

    const uniq = await videoIds(cid, vid)
    if (!uniq.length) return []

    const metas = (await mapLimit(uniq, EPISODE_PARALLEL, (v) => metaOf(v))).filter(Boolean)
    const usable = metas.filter(
      (m) => !(m.duration > 0 && m.duration < MIN_EPISODE_SECONDS) && !EXTRA_WORDS.some((w) => m.title.includes(w))
    )
    const rows = []
    for (const meta of usable) {
      const index = episodeIndexOf(meta.title)
      if (!index) continue
      rows.push({ index, meta })
    }
    const seen = new Set()
    const sorted = rows
      .sort((a, b) => a.index - b.index)
      .filter((r) => (seen.has(r.meta.vid) ? false : seen.add(r.meta.vid)))

    /**
     * 电影 / 单本剧：video_ids 只有一条，标题里也没有「第 N 集」，
     * 原来这里直接 return []，前端就会弹「这部剧没有可用集数」。
     * 现在当成一个「正片」条目返回，电影也能正常配弹幕。
     */
    if (!sorted.length) {
      const movie = usable[0]
      if (!movie) return []
      const single = [
        {
          source: 'tencent',
          id: movie.vid,
          title: cleanShowName(movie.title),
          subtitle: '正片（电影 / 单本）',
          isSeason: false,
        },
      ]
      episodeCache.set(cid, { at: Date.now(), list: single })
      return single
    }

    const showName = cleanShowName(sorted[0].meta.title)
    const list = sorted.map((r) => ({
      source: 'tencent',
      id: r.meta.vid,
      title: showName,
      subtitle: `第 ${String(r.index).padStart(2, '0')} 集`,
    }))
    episodeCache.set(cid, { at: Date.now(), list })
    return list
  },

  async load(vid, limit) {
    const total = Number(limit) > 0 ? Math.floor(Number(limit)) : TOTAL_LIMIT
    const base = JSON.parse(await httpText(`https://dm.video.qq.com/barrage/base/${vid}`, REFERER, 15000))
    const index = (base && base.segment_index) || {}
    let segments = Object.keys(index)
      .map((k) => index[k] && index[k].segment_name)
      .filter(Boolean)
      .sort((a, b) => segOffset(a) - segOffset(b))

    // 段索引偶发只回 2 段（接口抽风），用 getinfo 的时长补全
    if (segments.length <= 2) {
      const meta = await metaOf(vid).catch(() => null)
      const duration = meta && meta.duration ? meta.duration : 0
      if (duration > 60) {
        const count = Math.ceil((duration * 1000) / SEGMENT_MS)
        segments = Array.from({ length: count }, (_, i) => `t/v1/${i * SEGMENT_MS}/${(i + 1) * SEGMENT_MS}`)
      }
    }
    if (!segments.length) throw new Error('该集没有弹幕数据')

    const perSegment = Math.max(MIN_PER_SEGMENT, Math.ceil(total / segments.length))
    const parts = await mapLimit(segments, SEGMENT_PARALLEL, (seg) => fetchSegment(vid, seg, perSegment))
    const all = []
    for (const list of parts) if (list) all.push(...list)
    if (!all.length) throw new Error('弹幕是空的（可能是花絮、预告或未开放弹幕）')
    const sorted = all.sort((a, b) => a.time - b.time)
    if (sorted.length <= total) return sorted
    // 超上限时沿时间轴等间隔抽样，保证整段覆盖而不是只留前 10 分钟
    const out = []
    const step = sorted.length / total
    for (let i = 0; i < total; i += 1) out.push(sorted[Math.min(sorted.length - 1, Math.floor(i * step))])
    return out
  },
}

function segOffset(name) {
  const m = /t\/v1\/(\d+)\//.exec(String(name))
  return m ? Number(m[1]) : 0
}

async function fetchSegment(vid, segment, limit) {
  const body = await httpText(`https://dm.video.qq.com/barrage/segment/${vid}/${segment}`, REFERER, 15000)
  const json = JSON.parse(body)
  const arr = (json && json.barrage_list) || []
  const scored = []
  for (const item of arr) {
    const text = String(item.content || '').trim()
    if (!text) continue
    const time = Number(item.time_offset)
    if (!Number.isFinite(time)) continue
    scored.push({ score: Number(item.content_score) || 0, item: { time, text: text.slice(0, 200), mode: 'scroll', color: parseColor(item.content_style) } })
  }
  if (scored.length <= limit) return scored.map((x) => x.item)
  return scored
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((x) => x.item)
}

function parseColor(style) {
  if (!style) return 0xffffff
  try {
    const o = JSON.parse(style)
    // 彩色弹幕把颜色放在渐变里，首色足以代表观感
    const gradient = Array.isArray(o.gradient_colors) ? o.gradient_colors[0] : ''
    const hex = String(gradient || o.color || '').replace('#', '')
    if (hex.length === 6) return parseInt(hex, 16)
  } catch (_) {}
  return 0xffffff
}

function episodeIndexOf(title) {
  let m = /_(\d{1,4})$/.exec(title)
  if (m) return Number(m[1])
  m = /第\s*([0-9一二三四五六七八九十百]{1,4})\s*[集话]/.exec(title)
  if (m) return chineseToInt(m[1])
  return null
}

function chineseToInt(raw) {
  if (/^\d+$/.test(raw)) return Number(raw)
  const digits = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 }
  if (raw === '十') return 10
  if (raw.length === 1) return digits[raw[0]] || null
  if (raw.length === 2) {
    if (raw[0] === '十') return 10 + (digits[raw[1]] || 0)
    if (raw[1] === '十') return (digits[raw[0]] || 0) * 10
  }
  if (raw.length === 3 && raw[1] === '十') return (digits[raw[0]] || 0) * 10 + (digits[raw[2]] || 0)
  return null
}

/** 支持直接粘贴 v.qq.com 播放页 / 覆盖页链接。 */
function parseLink(text) {
  const hit = /v\.qq\.com\/x\/cover\/([a-z0-9]+)\/([a-z0-9]+)/i.exec(String(text || ''))
  if (!hit) return null
  return { source: 'tencent', id: `${hit[1]}|${hit[2]}`, title: '腾讯视频', subtitle: '链接导入 · 点开选择集数' }
}

module.exports = { tencent, parseLink }
