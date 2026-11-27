// 验证「删除单条记录」：点卡片右上角 × 应只删记录，不触发播放
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  for (let i = 0; i < 40 && !document.querySelector('.hcard'); i++) await sleep(300)
  const before = document.querySelectorAll('.hcard').length
  const first = document.querySelector('.hcard')
  const title = first.querySelector('.name')?.textContent || ''
  const del = first.querySelector('.hdel')
  if (!del) return '卡片上没有删除按钮'
  del.click()
  await sleep(1600)
  const after = document.querySelectorAll('.hcard').length
  const playing = !!document.querySelector('.player')
  const toast = document.querySelector('.toast')?.textContent || ''
  return `删除前=${before} 条 | 删的是「${title}」 | 删除后=${after} 条 | 是否误开播放器=${playing} | 提示="${toast}"`
})()
