// 验证弹幕设置里「时间偏移」的量程已扩到 ±20s
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const out = []
  const keep = () => {
    const p = document.querySelector('.player')
    if (p) p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 520 }))
  }

  for (let i = 0; i < 40 && !document.querySelector('.vcard'); i++) await sleep(300)
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(8000)
  if (!document.querySelector('.player')) return 'player 没打开'
  setInterval(keep, 700)
  keep()
  await sleep(600)

  // 打开弹幕设置面板（底栏「弹幕设置」按钮）
  const setBtn = [...document.querySelectorAll('.player button')].find((b) => b.title === '弹幕设置')
  if (!setBtn) return '找不到弹幕设置按钮'
  setBtn.click()
  await sleep(700)

  const ranges = [...document.querySelectorAll('.bsl input[type=range]')]
  out.push('滑条数量: ' + ranges.length)
  const offset = ranges.find((r) => Number(r.max) === 20000)
  if (!offset) return '没找到 20000 量程的滑条：' + ranges.map((r) => r.min + '~' + r.max).join(', ')
  out.push('量程: ' + offset.min + ' ~ ' + offset.max + ' / step=' + offset.step)

  const row = offset.closest('.bsl')
  const val = () => row.querySelector('.bsl-val').textContent

  const setVal = (v) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(offset, String(v))
    offset.dispatchEvent(new Event('input', { bubbles: true }))
  }

  setVal(-20000)
  await sleep(300)
  out.push('拉到最左: ' + val())
  setVal(20000)
  await sleep(300)
  out.push('拉到最右: ' + val())
  setVal(-3500)
  await sleep(300)
  out.push('设为 -3500ms: ' + val())

  const pass = offset.min === '-20000' && offset.max === '20000' && /20\.0s/.test(out.join(' '))
  out.push('结论: ' + (pass ? 'PASS' : 'FAIL'))
  return out.join('\n')
})()
