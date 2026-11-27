// 冒烟：验证上一集 / 下一集时弹幕跟着切换
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const dmText = () => document.querySelector('.player .ttl span')?.textContent || ''
  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const cards = [...document.querySelectorAll('.vcard')]
  if (cards.length) {
    cards[0].click()
    await sleep(10000)
  }
  const player = document.querySelector('.player')
  if (!player) return 'player=closed'
  log.push('第1集 文件=' + (document.querySelector('.player .ttl b')?.textContent || ''))
  log.push('第1集 弹幕=' + dmText())

  for (let round = 1; round <= 2; round++) {
    player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
    await sleep(400)
    const next = [...document.querySelectorAll('.bottombar .pbtn')].find((b) => (b.getAttribute('title') || '') === '下一个')
    if (!next) {
      log.push('找不到下一个按钮')
      break
    }
    next.click()
    await sleep(9000)
    log.push('第' + (round + 1) + '集 文件=' + (document.querySelector('.player .ttl b')?.textContent || ''))
    log.push('第' + (round + 1) + '集 弹幕=' + dmText())
  }
  player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  return log.join('\n')
})()
