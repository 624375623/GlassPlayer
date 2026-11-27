// 截图用：进播放器 → 保持底栏常显 → 输出底部按钮顺序
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const keep = () => {
    const p = document.querySelector('.player')
    if (p) p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 620 }))
  }
  for (let i = 0; i < 40 && !document.querySelector('.vcard'); i++) await sleep(300)
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(9000)
  const p = document.querySelector('.player')
  if (!p) return 'player closed'
  setInterval(keep, 1000)
  keep()
  await sleep(1400)
  const titles = [...document.querySelectorAll('.player .bottombar .pbtn')].map((b) => b.title || '?')
  const speed = document.querySelector('.player .bottombar .pcontrols > span')?.textContent || ''
  return '底栏顺序: ' + titles.join(' → ') + ' | 倍速文本: ' + speed
})()
