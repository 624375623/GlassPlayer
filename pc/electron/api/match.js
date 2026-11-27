'use strict'
/**
 * 片名匹配打分（B 站 / 腾讯共用）。
 *
 * 为什么需要：搜索接口返回的第一条经常不是正片 ——
 * 「狂飙」命中「弹速狂飙」、「火影忍者」全是舞台剧和采访、
 * 「鬼灭之刃」第一条是第四季「柱训练篇」、还有专门的「XX 预告片」条目。
 * 只按「包含关键词」判分，这些全都会被当成 100% 匹配，于是弹幕配到了预告片。
 *
 * 打分 0~100，>= 60 才允许自动匹配，>= 25 才进候选列表。
 */

/** 季/版号后缀：从标题里剥掉后如果和关键词一致，说明只是同一部剧的不同季。 */
const SEASON_SUFFIX =
  /(第\s*[0-9一二三四五六七八九十]{1,3}\s*(?:季|期|部|篇|章|话)|(?:19|20)\d{2}\s*年?\s*版?|剧场版|电影版|真人版|动画版|中配版|粤配版|国语版|日语版|原声版|重制版|重置版|完整版|未删减版|导演剪辑版|特别版|收藏版|高清版)/g

/** 额外内容标记：命中说明这条大概率不是正片。 */
const EXTRA =
  /(预告|先导|pv|花絮|特辑|片花|彩蛋|宣传|采访|幕后|抢先|直拍|彩排|开机|杀青|发布会|首映|见面会|混剪|解说|盘点|片段|cut|舞台剧|演唱会|生放送|广播剧|名场面)/i

const CN_NUM = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 }

function parseCn(raw) {
  const s = String(raw || '')
  if (/^\d+$/.test(s)) return Number(s)
  if (s === '十') return 10
  const m = /^([一二三四五六七八九])?十([一二三四五六七八九])?$/.exec(s)
  if (m) return (m[1] ? CN_NUM[m[1]] : 1) * 10 + (m[2] ? CN_NUM[m[2]] : 0)
  return CN_NUM[s] || null
}

function norm(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/[\s\u3000《》<>【】\[\]()（）「」『』·・:：,，.。!！?？'"“”‘’`~\-–—_/\\|*#@%&]+/g, '')
}

/** 去掉季/版号后的「核心片名」。 */
function core(s) {
  return norm(s).replace(SEASON_SUFFIX, '')
}

function isExtraName(s) {
  return EXTRA.test(String(s || ''))
}

/** 季号：第 2 季 / S02 / Season 2 / 续集，认不出返回 null。 */
function seasonNumber(s) {
  const t = String(s || '')
  let m = /第\s*([0-9]{1,2}|[一二三四五六七八九十]{1,3})\s*(?:季|期|部|篇|章)/.exec(t)
  if (m) {
    const n = parseCn(m[1])
    if (n && n > 0) return n
  }
  m = /(?:^|[^a-z])s(\d{1,2})(?:e\d{1,3})?(?![0-9])/i.exec(t)
  if (m) {
    const n = Number(m[1])
    if (n >= 1 && n <= 30) return n
  }
  m = /season\s*(\d{1,2})/i.exec(t)
  if (m) {
    const n = Number(m[1])
    if (n >= 1 && n <= 30) return n
  }
  if (/续集|第二部|第二季|2nd\s*season/i.test(t)) return 2
  return null
}

/** 最长公共子串长度，用来衡量「像不像」，比字符命中率靠谱得多。 */
function lcsLength(a, b) {
  if (!a || !b) return 0
  let best = 0
  let prev = new Array(b.length + 1).fill(0)
  for (let i = 1; i <= a.length; i += 1) {
    const cur = new Array(b.length + 1).fill(0)
    for (let j = 1; j <= b.length; j += 1) {
      if (a[i - 1] === b[j - 1]) {
        cur[j] = prev[j - 1] + 1
        if (cur[j] > best) best = cur[j]
      }
    }
    prev = cur
  }
  return best
}

/**
 * 片名相似度 0~100。
 * @param {string} title 接口返回的剧名
 * @param {string} keyword 用户关键词（已清洗）
 */
function scoreTitle(title, keyword) {
  const t = norm(title)
  const k = norm(keyword)
  if (!t || !k) return 0
  const tc = core(title)
  const kc = core(keyword)

  let base
  if (t === k) base = 100
  else if (tc && tc === kc) base = 95
  else if (t.startsWith(k)) {
    // 多出的部分越长，越可能是衍生篇（柱训练篇 / 舞台剧版），稍微压一点
    base = 90 - Math.min(14, (t.length - k.length) * 2)
  } else if (tc.startsWith(kc)) {
    base = 86 - Math.min(14, (tc.length - kc.length) * 2)
  } else if (k.startsWith(t) || (kc && kc.startsWith(tc))) base = 68
  else if (t.includes(k)) base = 42 + 18 * (k.length / t.length)
  else if (tc.includes(kc)) base = 38 + 18 * (kc.length / tc.length)
  else base = (lcsLength(t, k) / Math.max(t.length, k.length)) * 60

  // 标题带「预告 / 舞台剧 / 采访」这类词，而关键词本身没有 → 直接判非正片
  if (isExtraName(title) && !isExtraName(keyword)) base -= 55

  // 剧场版/真人版是另一部作品，集号对不上，压到正片之后
  if (/(剧场版|电影版|真人版|音乐剧)/.test(String(title)) && !/(剧场版|电影版|真人版)/.test(String(keyword))) {
    base -= 26
  }

  // 季号对不上：搜第一季却命中第四季，弹幕内容完全不对
  const st = seasonNumber(title)
  const sk = seasonNumber(keyword)
  if (st != null && sk != null) {
    if (st !== sk) base -= 45
  } else if (st != null && st > 1) {
    base -= Math.min(st, 6) * 6
  } else if (st == null && sk != null && sk > 1) {
    // 要的是第二季，但候选标题不带季号 —— 多半是第一季，弹幕对不上
    base -= 30
  }

  return Math.max(0, Math.min(100, Math.round(base)))
}

/** 打分排序，附带 score 与 season（季号，供前端优先选同季）。 */
function rank(list, keyword) {
  return (list || [])
    .map((item) => ({ item, score: scoreTitle(item.title, keyword) }))
    .sort((a, b) => b.score - a.score)
    .map(({ item, score }) => ({ ...item, score, season: seasonNumber(item.title) }))
}

module.exports = { norm, core, scoreTitle, rank, seasonNumber, isExtraName, lcsLength, parseCn }
