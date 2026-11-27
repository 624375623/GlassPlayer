// 冒烟：资源搜索 → 自动校验 → 失效链接不出现在列表里
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const log = []
  const input = () => document.querySelector('.page-head .field input')
  for (let i = 0; i < 40 && !input(); i++) await sleep(300)
  const box = input()
  if (!box) return 'no input'
  const setValue = (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  setValue(box, '沙丘')
  await sleep(200)
  const btn = [...document.querySelectorAll('.page-head .btn')].find((b) => /搜索/.test(b.textContent))
  btn?.click()

  // 等结果 + 校验
  for (let i = 0; i < 60 && !document.querySelector('.res-card'); i++) await sleep(500)
  await sleep(6000)
  log.push('副标题=' + (document.querySelector('.page-head .sub')?.textContent || '').trim())
  const cards = [...document.querySelectorAll('.res-card')]
  log.push('卡片数=' + cards.length)
  const bad = cards.filter((c) => /失效/.test(c.textContent))
  log.push('列表里仍显示「失效」的卡片=' + bad.length)
  const tags = {}
  cards.forEach((c) => {
    const t = [...c.querySelectorAll('.tag')].map((x) => x.textContent.trim())
    for (const x of t) tags[x] = (tags[x] || 0) + 1
  })
  log.push('标签统计=' + JSON.stringify(tags))
  const toggle = [...document.querySelectorAll('.chip')].find((c) => /隐藏失效/.test(c.textContent))
  log.push('隐藏失效 chip=' + (toggle ? toggle.textContent.trim() + ' active=' + toggle.classList.contains('active') : 'missing'))
  return log.join('\n')
})()
