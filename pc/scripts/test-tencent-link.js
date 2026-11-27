// 验证：腾讯链接导入 → 整季标记；复合 id → 自动展开；坏 id → 友好报错
const { tencent } = require('../electron/api/tencent')

const URL_ = 'https://v.qq.com/x/cover/vehyv3t0ith3r55/m0016pq3xum.html'

;(async () => {
  let pass = 0
  let fail = 0
  const ok = (name, cond, extra = '') => {
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`)
    cond ? pass++ : fail++
  }

  // 1) 链接导入行必须标记 isSeason
  const rows = await tencent.search(URL_)
  ok('search(链接) 返回 1 条', rows.length === 1, `got ${rows.length}`)
  ok('链接行 isSeason=true', rows[0] && rows[0].isSeason === true, JSON.stringify(rows[0]))
  ok('链接行 id 是 cid|vid', rows[0] && rows[0].id === 'vehyv3t0ith3r55|m0016pq3xum')

  // 2) 复合 id 直接 load 不再 404
  const t0 = Date.now()
  const items = await tencent.load('vehyv3t0ith3r55|m0016pq3xum', 400).catch((e) => e)
  ok(
    'load(复合 id) 返回弹幕',
    Array.isArray(items) && items.length > 0,
    Array.isArray(items) ? `${items.length} 条, ${Date.now() - t0}ms, 首条「${items[0].text.slice(0, 20)}」` : String(items.message)
  )

  // 3) 坏 id 报友好文案，不再裸奔 HTTP 404
  const bad = await tencent.load('zzzznotexist', 100).catch((e) => e)
  ok('坏 id 抛错', bad instanceof Error)
  ok('坏 id 文案不含 HTTP 404', !/HTTP 404/.test(bad.message || ''), bad.message)

  console.log(`\n${pass} passed, ${fail} failed`)
  process.exit(fail ? 1 : 0)
})()
