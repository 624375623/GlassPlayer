(async () => {
  const out = []
  const s = await window.gp.invoke('danmaku:search', { source: 'bili', keyword: '凡人修仙传' })
  const top = (s || [])[0]
  out.push(`search=${(s||[]).length} top=${top && top.title} score=${top && top.score} season=${top && top.season}`)
  if (top) {
    const eps = await window.gp.invoke('danmaku:episodes', { seasonId: top.id, source: 'bili' })
    const bad = (eps || []).filter(e => /预告|花絮|特辑|片花|PV/i.test(e.subtitle || '')).length
    out.push(`episodes=${(eps||[]).length} first=${eps[0] && eps[0].subtitle} last=${eps[eps.length-1] && eps[eps.length-1].subtitle} 预告泄漏=${bad}`)
    const ep154 = (eps || []).find(e => /第 154 集/.test(e.subtitle || ''))
    if (ep154) {
      const dm = await window.gp.invoke('danmaku:load', { source: 'bili', id: ep154.id, serverUrl: '' })
      out.push(`154集=${ep154.subtitle} 弹幕=${(dm.items||[]).length} 首条=${dm.items && dm.items[0] && dm.items[0].text}`)
    }
  }
  return out.join('\n')
})()
