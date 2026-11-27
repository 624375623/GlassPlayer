export type Cue = { start: number; end: number; text: string }

function toMs(stamp: string): number {
  const clean = stamp.trim().replace(',', '.')
  const m = /^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$/.exec(clean)
  if (!m) return 0
  const h = Number(m[1] || 0)
  const min = Number(m[2])
  const sec = Number(m[3])
  const frac = Number((m[4] || '0').padEnd(3, '0'))
  return ((h * 60 + min) * 60 + sec) * 1000 + frac
}

/** 解析 SRT / VTT。 */
function parseSrtLike(text: string): Cue[] {
  const body = text.replace(/\r/g, '').replace(/^WEBVTT.*$/m, '')
  const blocks = body.split(/\n{2,}/)
  const cues: Cue[] = []
  for (const block of blocks) {
    const lines = block.split('\n').filter((l) => l.trim() !== '')
    if (!lines.length) continue
    const timeIdx = lines.findIndex((l) => l.includes('-->'))
    if (timeIdx < 0) continue
    const [startRaw, endRaw] = lines[timeIdx].split('-->')
    const start = toMs(startRaw.replace(/\s+[A-Za-z].*$/, ''))
    const end = toMs(String(endRaw || '').trim().split(/\s+/)[0])
    const content = lines
      .slice(timeIdx + 1)
      .join('\n')
      .replace(/<[^>]+>/g, '')
      .trim()
    if (content && end > start) cues.push({ start, end, text: content })
  }
  return cues.sort((a, b) => a.start - b.start)
}

/** 解析 ASS / SSA（只取对白，忽略特效标签）。 */
function parseAss(text: string): Cue[] {
  const lines = text.replace(/\r/g, '').split('\n')
  let inEvents = false
  let fields: string[] = []
  const cues: Cue[] = []
  for (const line of lines) {
    const trimmed = line.trim()
    if (/^\[events\]/i.test(trimmed)) {
      inEvents = true
      continue
    }
    if (/^\[/.test(trimmed)) {
      inEvents = false
      continue
    }
    if (!inEvents) continue
    if (/^format\s*:/i.test(trimmed)) {
      fields = trimmed
        .slice(trimmed.indexOf(':') + 1)
        .split(',')
        .map((f) => f.trim().toLowerCase())
      continue
    }
    if (!/^dialogue\s*:/i.test(trimmed)) continue
    const payload = line.slice(line.indexOf(':') + 1)
    const parts: string[] = []
    let rest = payload
    for (let i = 0; i < fields.length - 1; i += 1) {
      const idx = rest.indexOf(',')
      if (idx < 0) break
      parts.push(rest.slice(0, idx))
      rest = rest.slice(idx + 1)
    }
    parts.push(rest)
    const map: Record<string, string> = {}
    fields.forEach((f, i) => (map[f] = (parts[i] ?? '').trim()))
    const start = toMs(map['start'] || '0:00:00.00')
    const end = toMs(map['end'] || '0:00:00.00')
    const content = (map['text'] || '')
      .replace(/\{[^}]*\}/g, '')
      .replace(/\\N/gi, '\n')
      .replace(/\\h/gi, ' ')
      .trim()
    if (content && end > start) cues.push({ start, end, text: content })
  }
  return cues.sort((a, b) => a.start - b.start)
}

export function parseSubtitles(text: string, filename = ''): Cue[] {
  const lower = filename.toLowerCase()
  if (lower.endsWith('.ass') || lower.endsWith('.ssa') || /^\[script info\]/i.test(text.trim())) {
    return parseAss(text)
  }
  return parseSrtLike(text)
}

/** 二分查找当前时间对应的字幕。 */
export function activeCue(cues: Cue[], timeMs: number): Cue | null {
  let lo = 0
  let hi = cues.length - 1
  let found: Cue | null = null
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const cue = cues[mid]
    if (timeMs < cue.start) hi = mid - 1
    else if (timeMs > cue.end) lo = mid + 1
    else {
      found = cue
      break
    }
  }
  return found
}
