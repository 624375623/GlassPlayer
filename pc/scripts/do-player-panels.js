// 冒烟：进播放器 → 打开指定面板（选集 / 弹幕设置）
// 用环境变量 PANEL=episodes|dmset 控制，默认 episodes
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
    await sleep(9000)
  }
  const player = document.querySelector('.player')
  log.push('player=' + (player ? 'open' : 'closed'))
  if (!player) return log.join('\n')

  player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  await sleep(400)

  const want = '目录选集'
  const btn = [...document.querySelectorAll('.topbar .pbtn')].find((b) => (b.getAttribute('title') || '').includes(want))
  log.push('btn=' + (btn ? btn.getAttribute('title') : 'missing'))
  if (btn) {
    btn.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
    btn.click()
    await sleep(1200)
  }
  const panel = document.querySelector('.ppanel')
  log.push('panel=' + (panel ? panel.querySelector('h3')?.textContent : 'none'))
  const items = [...document.querySelectorAll('.ppanel .pitem')].slice(0, 8)
  log.push('items=' + items.length + ' | ' + items.map((i) => i.querySelector('.epno')?.textContent + ':' + i.querySelector('.t')?.textContent).join(' / '))
  log.push('total=' + document.querySelectorAll('.ppanel .pitem').length)
  // 截图前再动一下鼠标，别让 4 秒自动收起把面板关了
  player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  return log.join('\n')
})()
