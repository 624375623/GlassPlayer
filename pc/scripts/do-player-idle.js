// 冒烟：面板 4 秒无鼠标操作自动收起 + 鼠标动会重新计时
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const player = () => document.querySelector('.player')
  const panelOpen = () => !!document.querySelector('.ppanel')
  const move = () => player()?.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))

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
  if (!player()) return 'player=closed'

  move()
  await sleep(300)
  document.querySelectorAll('.topbar .pbtn').forEach((b) => {
    if ((b.getAttribute('title') || '') === '弹幕设置') b.click()
  })
  await sleep(600)
  log.push('T0 面板=' + panelOpen())

  // 3 秒时动一下鼠标：应该重新计时，面板仍在
  await sleep(3000)
  move()
  await sleep(600)
  log.push('T3.6 动鼠标后 面板=' + panelOpen())

  // 再等 3 秒（未到 4s）：还在
  await sleep(3000)
  log.push('T6.6 面板=' + panelOpen())

  // 再等 1.5 秒（累计 4.5s 无操作）：应已关闭并进入沉浸
  await sleep(1500)
  log.push('T8.1 面板=' + panelOpen())
  log.push('沉浸(hide-ui)=' + (document.querySelector('.player')?.className || ''))
  return log.join('\n')
})()
