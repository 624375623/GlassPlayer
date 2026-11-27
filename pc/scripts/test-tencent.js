const { tencent } = require('../electron/api/tencent')

async function main() {
  const kw = process.argv[2] || '庆余年'
  const t0 = Date.now()
  const list = await tencent.search(kw)
  console.log(`SEARCH ${list.length} 条 ${Date.now() - t0}ms`)
  list.slice(0, 5).forEach((m) => console.log('  -', m.title, '|', m.id, '|', m.subtitle))
  if (!list.length) return
  const first = list[0]
  const t1 = Date.now()
  const eps = await tencent.episodes(first.id.split('|')[0], first.id.split('|')[1])
  console.log(`EPISODES ${eps.length} 集 ${Date.now() - t1}ms · ${eps[0] && eps[0].title} / ${eps[0] && eps[0].subtitle} ... ${eps[eps.length - 1] && eps[eps.length - 1].subtitle}`)
  if (!eps.length) return
  const t2 = Date.now()
  const items = await tencent.load(eps[0].id)
  console.log(`LOAD ${items.length} 条 ${Date.now() - t2}ms`)
  console.log('  首条', JSON.stringify(items[0]), '末条 t=', items[items.length - 1].time)
  const colors = new Set(items.map((x) => x.color))
  console.log('  颜色数', colors.size)
}

main().catch((e) => { console.log('FAIL', e.message) ; process.exit(1) })
