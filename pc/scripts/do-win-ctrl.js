// 验证播放页窗口控制：拖动区、最小化/最大化按钮、最大化↔还原状态同步
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const out = []
  const keep = () => {
    const p = document.querySelector('.player')
    if (p) p.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientY: 400 }))
  }

  for (let i = 0; i < 40 && !document.querySelector('.vcard'); i++) await sleep(300)
  const card = document.querySelector('.vcard')
  if (!card) return '没有视频卡片'
  card.click()
  await sleep(7000)
  const bar = document.querySelector('.player .topbar')
  if (!bar) return 'player 没打开'
  setInterval(keep, 800)
  keep()
  await sleep(800)

  const drag = getComputedStyle(bar).webkitAppRegion
  out.push('顶栏 drag 属性: ' + drag + (drag === 'drag' ? ' ✓' : ' ✗'))

  const btns = [...bar.querySelectorAll('button')]
  const mini = btns.find((b) => b.title === '最小化')
  const maxBtn = btns.find((b) => /最大化|还原/.test(b.title || ''))
  out.push('最小化按钮: ' + (mini ? '有' : '无') + ' / 最大化按钮: ' + (maxBtn ? '有(' + maxBtn.title + ')' : '无'))
  if (mini) out.push('最小化按钮 no-drag: ' + getComputedStyle(mini).webkitAppRegion)

  const isMax = () => window.gp.invoke('window:isMax')
  out.push('初始 isMax: ' + (await isMax()))

  // 点最大化
  if (maxBtn) {
    maxBtn.click()
    await sleep(1500)
    out.push('点最大化后 isMax: ' + (await isMax()) + ' / 按钮文案: ' + maxBtn.title)
    // 再点还原
    maxBtn.click()
    await sleep(1500)
    out.push('点还原后 isMax: ' + (await isMax()) + ' / 按钮文案: ' + maxBtn.title)
  }

  const pass = drag === 'drag' && mini && maxBtn
  out.push('结论: ' + (pass ? 'PASS' : 'FAIL'))
  return out.join('\n')
})()
