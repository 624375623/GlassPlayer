// 冒烟：底部弹幕按钮 + 弹幕数量上限 + 电影匹配
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []

  // 1) 把上限设成 400，进入播放器后看实际采集条数
  await window.gp.invoke('settings:patch', { danmakuLimit: 400 })
  log.push('danmakuLimit 已设为 400')

  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const cards = [...document.querySelectorAll('.vcard')]
  if (cards.length) {
    cards[0].click()
    await sleep(12000)
  }
  const p = document.querySelector('.player')
  if (!p) return log.join('\n') + '\nplayer=closed'

  log.push('顶部栏按钮=' + [...document.querySelectorAll('.player .topbar .pbtn')].map((b) => b.title).join(' / '))
  log.push('底部栏按钮=' + [...document.querySelectorAll('.player .bottombar .pbtn')].map((b) => (b.title || '').slice(0, 12)).join(' / '))

  // 弹幕加载条数
  const dmText = document.querySelector('.player .ttl span')?.textContent || ''
  log.push('弹幕状态=' + dmText)

  // 打开底部弹幕设置
  p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 700 }))
  await sleep(400)
  const setBtn = [...document.querySelectorAll('.player .bottombar .pbtn')].find((b) => b.title === '弹幕设置')
  log.push('底部弹幕设置按钮=' + (setBtn ? 'found' : 'missing'))
  setBtn?.click()
  await sleep(700)
  log.push('面板=' + (document.querySelector('.ppanel h3')?.textContent || 'none'))
  log.push('滑条=' + [...document.querySelectorAll('.ppanel .pslider')].length + ' 条, 字段=' + [...document.querySelectorAll('.ppanel .dm-slider-label, .ppanel .dm-slabel')].map((x) => x.textContent).join(','))

  // 切回默认上限，避免影响后续
  await window.gp.invoke('settings:patch', { danmakuLimit: 3000 })
  p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 700 }))
  return log.join('\n')
})()
