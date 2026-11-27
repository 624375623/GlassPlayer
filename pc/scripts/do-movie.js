// 冒烟：弹幕库里搜电影（变形金刚2），腾讯源应能直接出「正片」并加载弹幕
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const setValue = (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement : window.HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(proto.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  await window.gp.invoke('settings:patch', { danmakuEnabled: true, danmakuLimit: 3000 })

  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(10000)
  const p = document.querySelector('.player')
  if (!p) return 'player closed'

  // 打开弹幕库
  p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 40 }))
  await sleep(400)
  ;[...document.querySelectorAll('.player .topbar .pbtn')].find((b) => b.title === '弹幕库')?.click()
  await sleep(700)
  log.push('面板=' + (document.querySelector('.ppanel h3')?.textContent || 'none'))

  // 切到腾讯
  const tencent = [...document.querySelectorAll('.ppanel .pbtn')].find((b) => b.textContent.trim() === '腾讯')
  tencent?.click()
  await sleep(400)

  // 输入变形金刚2 并搜索
  const input = document.querySelector('.ppanel .pfield input')
  setValue(input, '变形金刚2')
  await sleep(200)
  ;[...document.querySelectorAll('.ppanel .btn')].find((b) => /搜索/.test(b.textContent))?.click()
  log.push('搜索中…')

  for (let i = 0; i < 60; i++) {
    await sleep(1000)
    if (document.querySelectorAll('.ppanel .pitem').length) break
  }
  const items = [...document.querySelectorAll('.ppanel .pitem')]
  log.push('结果数=' + items.length)
  log.push('第一条=' + (items[0]?.textContent || 'none'))

  items[0]?.click()
  for (let i = 0; i < 30; i++) {
    await sleep(1000)
    const s = document.querySelector('.player .ttl span')?.textContent || ''
    if (/条$/.test(s) && !/0 条$/.test(s)) break
  }
  await sleep(1500)
  log.push('播放页弹幕=' + (document.querySelector('.player .ttl span')?.textContent || ''))
  log.push('面板状态=' + (document.querySelector('.ppanel h3')?.textContent || '已关闭'))
  log.push('toast=' + [...document.querySelectorAll('.toast')].map((t) => t.textContent).join(' | '))
  return log.join('\n')
})()
