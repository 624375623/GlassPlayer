// 端到端：播放页 → 弹幕库 → 粘贴腾讯链接 → 搜索 → 点结果 → 应出「选集·正片」且无红色报错
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const keep = () => {
    const p = document.querySelector('.player')
    if (p) p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 400 }))
  }

  const setVal = (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }

  for (let i = 0; i < 40 && !document.querySelector('.vcard'); i++) await sleep(300)
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(8000)
  if (!document.querySelector('.player')) return 'player 没打开'
  setInterval(keep, 800)
  keep()
  await sleep(600)

  const dmBtn = [...document.querySelectorAll('.player button')].find((b) => b.title === '弹幕库')
  if (!dmBtn) return '找不到弹幕库按钮'
  dmBtn.click()
  await sleep(900)

  const input = document.querySelector('.ppanel input')
  if (!input) return '找不到搜索框'
  setVal(input, 'https://v.qq.com/x/cover/vehyv3t0ith3r55/m0016pq3xum.html')
  await sleep(300)

  const searchBtn = [...document.querySelectorAll('.ppanel button')].find((b) => b.textContent.trim() === '搜索')
  if (!searchBtn) return '找不到搜索按钮'
  searchBtn.click()

  // 等搜索结果
  let row = null
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    row = document.querySelector('.ppanel .pitem')
    if (row) break
  }
  if (!row) return '搜索结果为空：' + (document.querySelector('.ppanel')?.innerText || '').slice(0, 120)
  log.push('结果行: ' + row.innerText.replace(/\n/g, ' / '))

  row.click()
  await sleep(7000)

  const toasts = [...document.querySelectorAll('.toast')].map((t) => t.textContent)
  log.push('提示数量: ' + toasts.length + (toasts.length ? ' → ' + toasts.join(' | ') : ''))

  // 面板在加载成功后会自动关掉，重新打开看「选集」与「当前」
  keep()
  const dmBtn2 = [...document.querySelectorAll('.player button')].find((b) => b.title === '弹幕库')
  if (dmBtn2) {
    dmBtn2.click()
    await sleep(900)
  }
  const panelText = document.querySelector('.ppanel')?.innerText || ''
  const hasEps = /选集/.test(panelText)
  const hasMovie = /正片/.test(panelText)
  const cur = (panelText.match(/当前：[^\n]+/) || [''])[0]
  log.push('选集区: ' + (hasEps ? '有' : '无') + ' / 正片按钮: ' + (hasMovie ? '有' : '无'))
  log.push('当前行: ' + (cur || '无'))
  log.push('结论: ' + (!toasts.length && hasEps ? 'PASS' : 'FAIL'))
  return log.join('\n')
})()
