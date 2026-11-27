// 验证「清空记录」二次确认：点一次应变成红色确认态，但不清空数据
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  for (let i = 0; i < 40 && !document.querySelector('.hcard'); i++) await sleep(300)
  const cards = document.querySelectorAll('.hcard').length
  const btn = [...document.querySelectorAll('.page-head .btn')].find((b) => /清空/.test(b.textContent))
  if (!btn) return '没找到清空按钮'
  const before = btn.textContent.trim()
  const cls0 = btn.className
  btn.click()
  await sleep(500)
  const after = btn.textContent.trim()
  const cls1 = btn.className
  const stillCards = document.querySelectorAll('.hcard').length
  return `卡片=${cards} | 点击前="${before}" (${cls0}) | 点击后="${after}" (${cls1}) | 卡片仍=${stillCards}`
})()
