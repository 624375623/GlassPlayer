'use strict'
/**
 * 弹幕匹配回归测试：模拟播放器 autoDanmaku 的决策链
 *   文件名 → cleanTitle → 搜索打分 → 展开集数 → 按集号取弹幕
 * 关注两点：别配到预告片，别跨季错配。
 *
 * 运行：node scripts/test-match.js
 */
const { bili, cleanTitle } = require('../electron/api/danmaku')
const { scoreTitle, seasonNumber } = require('../electron/api/match')

const FILES = [
  '凡人修仙传.S01E154.2160p.WEB-DL.mkv',
  '凡人修仙传 第7集.mp4',
  '三体.S01E02.1080p.mp4',
  '鬼灭之刃.S04E02.1080p.mkv',
  '亮剑.第5集.HDTV.mp4',
  '海贼王.第1085集.mp4',
  '狂飙.S01E10.2160p.mp4',
  '航海王：红发歌姬.2022.2160p.mkv',
]

function episodeIndex(name) {
  const patterns = [
    /S(\d{1,2})E(\d{1,3})/i,
    /EP?\.?\s*(\d{1,3})/i,
    /第\s*(\d{1,3})\s*[集话話期]/,
    /(?:^|[\s._-])(\d{1,3})(?:[\s._-]|$)/,
  ]
  for (const re of patterns) {
    const m = re.exec(name || '')
    if (m) {
      const n = Number(m[m.length - 1])
      if (n > 0 && n < 5000) return n
    }
  }
  return null
}

async function run(file) {
  const keyword = cleanTitle(file)
  const wanted = episodeIndex(file)
  const raw = await bili.search(keyword)
  const best = raw[0]
  const line = [`\n📄 ${file}`, `   关键词=${keyword}  集号=${wanted}  候选=${raw.length}`]
  if (!best) return line.concat('   ⚠️ 无候选（会降级到腾讯）').join('\n')
  line.push(`   最佳=${best.title} (${best.score}分) ${best.score < 60 ? '→ 不自动匹配' : ''}`)
  if (best.score < 60) return line.join('\n')
  const eps = await bili.episodes(String(best.id).replace('season:', ''))
  if (!eps.length) return line.concat('   ⚠️ 无可用集数').join('\n')
  const hit = wanted ? eps.find((e) => episodeIndex(e.subtitle || '') === wanted) : eps[0]
  line.push(`   集数=${eps.length} 首=${eps[0].subtitle} 尾=${eps[eps.length - 1].subtitle}`)
  line.push(hit ? `   ✅ 命中 ${hit.subtitle} → ${hit.id}` : `   ⚠️ 没有第 ${wanted} 集，不硬配`)
  const leaked = eps.filter((e) => /预告|花絮|特辑|片花|PV/i.test(e.subtitle || ''))
  line.push(`   混入的预告条目：${leaked.length}`)
  return line.join('\n')
}

;(async () => {
  for (const f of FILES) {
    try {
      console.log(await run(f))
    } catch (e) {
      console.log(`\n📄 ${f}\n   ❌ ${e.message}`)
    }
    await new Promise((r) => setTimeout(r, 350))
  }
  console.log('\n--- 打分自检 ---')
  const cases = [
    ['弹速狂飙', '狂飙'],
    ['火影忍者舞台剧 虹桥艺术中心采访篇', '火影忍者'],
    ['某剧 预告片', '某剧'],
    ['鬼灭之刃 柱训练篇', '鬼灭之刃'],
    ['鬼灭之刃', '鬼灭之刃'],
    ['凡人修仙传', '凡人修仙传'],
  ]
  for (const [t, k] of cases) console.log(`   ${String(scoreTitle(t, k)).padStart(3)}  ${t} <= ${k}`)
  console.log(`   季号：S04=${seasonNumber('S04E02')} 第二季=${seasonNumber('第二季')} 续集=${seasonNumber('续集')}`)
})()
