// 验证播放记录页：点第一条记录是否从断点继续
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const keep = () => {
    const p = document.querySelector('.player')
    if (p) p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 620 }))
  }
  for (let i = 0; i < 40 && !document.querySelector('.hcard'); i++) await sleep(300)
  const first = document.querySelector('.hcard')
  if (!first) return '记录页没有卡片'
  const title = first.querySelector('.name')?.textContent || ''
  const lines = [...first.querySelectorAll('.hline span')].map((x) => x.textContent).join(' / ')
  const tip = first.getAttribute('title') || ''
  first.click()
  await sleep(11000)
  const p = document.querySelector('.player')
  if (!p) return `点了「${title}」(${lines}) 但播放器没打开 | tip=${tip}`
  setInterval(keep, 1000)
  keep()
  await sleep(600)
  const v = document.querySelector('.player video')
  const shown = document.querySelector('.player .time')?.textContent || ''
  return `记录=${title} | 卡片=${lines} | 播放器时间=${shown} | video.currentTime=${v ? Math.round(v.currentTime) : 'n/a'}s | tip=${tip}`
})()
