// 验证顶栏关闭按钮：去掉中文、与最小化/最大化同尺寸同风格
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
  await sleep(600)

  const close = bar.querySelector('.pclose')
  const mini = [...bar.querySelectorAll('button')].find((b) => b.title === '最小化')
  if (!close || !mini) return '按钮缺失 close=' + !!close + ' mini=' + !!mini

  const txt = (close.textContent || '').trim()
  out.push('关闭按钮文字: ' + (txt === '' ? '(空) ✓' : JSON.stringify(txt) + ' ✗'))

  const cs = getComputedStyle(close)
  const cm = getComputedStyle(mini)
  const cr = close.getBoundingClientRect()
  const mr = mini.getBoundingClientRect()
  out.push('尺寸 关闭: ' + Math.round(cr.width) + 'x' + Math.round(cr.height) + ' / 最小化: ' + Math.round(mr.width) + 'x' + Math.round(mr.height))
  out.push('圆角 关闭: ' + cs.borderRadius + ' / 最小化: ' + cm.borderRadius)
  out.push('文字色 关闭: ' + cs.color + ' / 最小化: ' + cm.color)
  out.push('背景 关闭: ' + cs.backgroundColor + ' / 最小化: ' + cm.backgroundColor)
  out.push('底对齐差: ' + Math.abs(Math.round(cr.bottom) - Math.round(mr.bottom)) + 'px / 右间距: ' + Math.round(window.innerWidth - cr.right))

  // :hover 无法用脚本触发，改为读取样式表里真实生效的规则
  let hoverBg = ''
  for (const sh of document.styleSheets) {
    let rules = []
    try { rules = [...sh.cssRules] } catch { continue }
    for (const r of rules) {
      if (r.selectorText && r.selectorText.includes('.pclose:hover')) hoverBg = r.style.background || r.style.backgroundColor || hoverBg
    }
  }
  out.push('悬停规则: ' + (hoverBg ? hoverBg + ' ✓' : '未找到 ✗'))

  const pass = txt === '' && Math.abs(cr.width - mr.width) < 1 && Math.abs(cr.height - mr.height) < 1 && cs.borderRadius === cm.borderRadius && cs.backgroundColor === 'rgba(0, 0, 0, 0)' && !!hoverBg
  out.push('结论: ' + (pass ? 'PASS' : 'FAIL'))
  return out.join('\n')
})()
