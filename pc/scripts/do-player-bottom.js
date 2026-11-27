// 截图用：进播放器 → 鼠标停在底栏 → 打开弹幕设置面板
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(12000)
  const p = document.querySelector('.player')
  if (!p) return 'player closed'
  p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 700 }))
  await sleep(500)
  const setBtn = [...document.querySelectorAll('.player .bottombar .pbtn')].find((b) => b.title === '弹幕设置')
  setBtn?.click()
  await sleep(600)
  p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 700 }))
  return (
    '滑条=' +
    [...document.querySelectorAll('.ppanel .bsl')]
      .map((x) => x.querySelector('.bsl-label')?.textContent + '=' + x.querySelector('.bsl-val')?.textContent)
      .join(' | ')
  )
})()
