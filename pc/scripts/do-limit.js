// 冒烟：在设置页把弹幕上限改成 400，再进播放器看实际条数
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const setValue = (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, String(v))
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }

  // 设置页：找到「弹幕数量上限」的输入框
  for (let i = 0; i < 40 && !document.querySelector('.settings-grid'); i++) await sleep(300)
  const rows = [...document.querySelectorAll('.form-row')]
  const row = rows.find((r) => /弹幕数量上限/.test(r.textContent))
  const input = row?.querySelector('input')
  log.push('找到输入框=' + !!input + ' 原值=' + (input ? input.value : '-'))
  if (input) {
    setValue(input, 400)
    await sleep(600)
    const snap = await window.gp.invoke('store:snapshot')
    log.push('落盘 danmakuLimit=' + snap.settings.danmakuLimit)
  }

  // 回网盘页进播放器
  const nav = [...document.querySelectorAll(".rail .nav-item")].find((b) => /网盘/.test(b.textContent))
  nav?.click()
  await sleep(1500)
  for (let i = 0; i < 40 && !document.querySelector('.folder-card, .vcard'); i++) await sleep(300)
  const folder = [...document.querySelectorAll('.folder-card')].find((el) => /修仙传/.test(el.textContent))
  if (folder) {
    folder.click()
    await sleep(4000)
  }
  const card = document.querySelector('.vcard')
  if (!card) return log.join('\n') + '\n没有视频卡片'
  card.click()
  await sleep(12000)
  log.push('播放页弹幕=' + (document.querySelector('.player .ttl span')?.textContent || ''))
  return log.join('\n')
})()
