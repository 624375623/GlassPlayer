// 冒烟：进网盘 → 打开一部剧 → 播放器界面截图
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  log.push('folder=' + (folder ? folder.textContent.slice(0, 20) : 'none'))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const cards = [...document.querySelectorAll('.vcard')]
  log.push('videos=' + cards.length + ' | ' + cards.slice(0, 3).map((c) => c.querySelector('b')?.textContent).join(' / '))
  if (cards.length) {
    cards[0].click()
    await sleep(9000)
  }
  log.push('player=' + (document.querySelector('.player') ? 'open' : 'closed'))
  log.push('topbtns=' + document.querySelectorAll('.player .topbar .pbtn').length)
  log.push('title=' + (document.querySelector('.player .ttl b')?.textContent || ''))
  return log.join('\n')
})()
