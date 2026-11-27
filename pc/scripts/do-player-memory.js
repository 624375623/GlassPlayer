// 冒烟：视频内设置（倍速 / 弹幕）记忆 —— 设置后退出再进，是否还原
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const move = () => document.querySelector('.player')?.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }))
  const rate = () => document.querySelector('.player video')?.playbackRate
  const dmText = () => document.querySelector('.player .ttl span')?.textContent || ''

  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }

  const openFirst = async () => {
    const card = document.querySelector('.vcard')
    if (!card) return false
    card.click()
    await sleep(11000)
    return true
  }

  if (!(await openFirst())) return 'no video'
  log.push('第一次 倍速=' + rate() + ' 弹幕=' + dmText())

  // 设为 1.5x
  move()
  await sleep(400)
  const speedBtn = [...document.querySelectorAll('.bottombar .pbtn')].find((b) => b.getAttribute('title') === '倍速')
  speedBtn?.click()
  await sleep(400)
  const opt = [...document.querySelectorAll('.pmenu button')].find((b) => b.textContent.includes('1.5x'))
  log.push('找到1.5x=' + (opt ? 'yes' : 'no'))
  opt?.click()
  await sleep(800)
  log.push('设置后 倍速=' + rate())

  // 退出播放器再重进同一集
  move()
  await sleep(300)
  document.querySelector('.pclose')?.click()
  await sleep(2500)
  log.push('回到列表=' + (document.querySelector('.vcard') ? 'yes' : 'no'))

  if (!(await openFirst())) return log.join('\n') + '\n重进失败'
  log.push('第二次 倍速=' + rate() + ' 弹幕=' + dmText())
  move()
  return log.join('\n')
})()
