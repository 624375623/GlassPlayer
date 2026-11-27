// 冒烟：进播放器 → 检查弹幕匹配到的剧名是否就是当前文件夹的剧 → 打开弹幕设置面板
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
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
  log.push('标题=' + (document.querySelector('.player .ttl b')?.textContent || ''))
  log.push('弹幕=' + (document.querySelector('.player .ttl span')?.textContent || ''))

  player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  await sleep(300)
  const btn = [...document.querySelectorAll('.topbar .pbtn')].find((b) => (b.getAttribute('title') || '').includes('弹幕设置'))
  log.push('设置按钮=' + (btn ? btn.getAttribute('title') : 'missing'))
  if (btn) {
    btn.click()
    await sleep(900)
  }
  const panel = document.querySelector('.ppanel')
  log.push('面板=' + (panel?.querySelector('h3')?.textContent || 'none'))
  log.push('滑条=' + [...document.querySelectorAll('.ppanel .pslider')].map((s) => s.textContent.replace(/\s+/g, ' ').trim()).join(' | '))
  const op = document.querySelector('.ppanel .pslider input[type=range]')
  log.push('透明度滑条值=' + (op ? op.value : 'none') + ' min=' + (op ? op.min : '') + ' max=' + (op ? op.max : ''))
  player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  return log.join('\n')
})()
