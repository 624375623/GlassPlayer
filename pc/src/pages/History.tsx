import { useMemo, useRef, useState } from 'react'
import { api } from '../lib/api'
import type { PlayTarget } from '../lib/types'
import { useApp } from '../lib/ctx'
import { formatDate, formatDuration, stripExt } from '../lib/format'
import { Empty, Icon } from '../components/Icon'

type Rec = {
  key: string
  title: string
  kind: 'local' | 'cloud'
  fid?: string
  path?: string
  subtitle: string
  pos: number
  duration: number
  at: number
  progress: number
  finished: boolean
}

const DAY = 86_400_000

function startOfDay(ts: number) {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function baseName(p: string) {
  return String(p || '').split(/[\\/]/).filter(Boolean).pop() || ''
}

export function HistoryPage() {
  const { snap, openPlayer, refresh, toast, setTab } = useApp()
  const [query, setQuery] = useState('')
  const [confirmClear, setConfirmClear] = useState(false)
  const clearTimer = useRef<number | null>(null)

  const records = useMemo<Rec[]>(() => {
    const out: Rec[] = []
    for (const key of snap.recent) {
      const pos = snap.positions[key]
      if (!pos) continue
      const isCloud = key.startsWith('quark://')
      const fid = isCloud ? key.slice('quark://'.length) : undefined
      const meta = fid ? snap.cloudMeta[fid] : undefined
      const path = isCloud ? undefined : key
      const rawName = meta?.name || (path ? baseName(path) : '')
      const title = rawName ? stripExt(rawName) : isCloud ? '网盘视频（信息已过期）' : '本地视频（文件可能已移走）'
      const duration = pos.duration || meta?.durationMs || 0
      const p = duration ? Math.min(1, pos.pos / duration) : 0
      out.push({
        key,
        title,
        kind: isCloud ? 'cloud' : 'local',
        fid,
        path,
        subtitle: isCloud ? '夸克网盘' : meta?.parentName || (path ? baseName(path.replace(/[\\/][^\\/]*$/, '')) : '本地文件'),
        pos: pos.pos,
        duration,
        at: pos.at || 0,
        progress: p,
        finished: p >= 0.97,
      })
    }
    return out.sort((a, b) => b.at - a.at)
  }, [snap.recent, snap.positions, snap.cloudMeta])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return records
    return records.filter((r) => r.title.toLowerCase().includes(q) || r.subtitle.toLowerCase().includes(q))
  }, [records, query])

  /** 按「今天 / 昨天 / 本周 / 更早」分组 */
  const groups = useMemo(() => {
    const today = startOfDay(Date.now())
    const buckets: { label: string; items: Rec[] }[] = [
      { label: '今天', items: [] },
      { label: '昨天', items: [] },
      { label: '本周', items: [] },
      { label: '更早', items: [] },
    ]
    for (const r of filtered) {
      const d = startOfDay(r.at || Date.now())
      const diff = Math.floor((today - d) / DAY)
      const idx = diff <= 0 ? 0 : diff === 1 ? 1 : diff < 7 ? 2 : 3
      buckets[idx].items.push(r)
    }
    return buckets.filter((b) => b.items.length)
  }, [filtered])

  const totalWatched = useMemo(() => records.reduce((n, r) => n + r.pos, 0), [records])

  const play = (r: Rec) => {
    if (r.kind === 'cloud' && !snap.settings.quarkCookie) {
      toast('请先在网盘页扫码登录夸克', 'error')
      setTab('cloud')
      return
    }
    const target: PlayTarget = {
      key: r.key,
      title: r.title,
      subtitle: r.subtitle,
      kind: r.kind,
      path: r.path,
      fid: r.fid,
      meta: r.fid ? { name: r.title, size: snap.cloudMeta[r.fid]?.size } : undefined,
    }
    openPlayer(target, [target])
  }

  const remove = async (r: Rec, e: React.MouseEvent) => {
    e.stopPropagation()
    await api.clearPosition(r.key)
    await refresh()
    toast('已删除这条记录')
  }

  const clearAll = async () => {
    if (!confirmClear) {
      setConfirmClear(true)
      if (clearTimer.current) window.clearTimeout(clearTimer.current)
      clearTimer.current = window.setTimeout(() => setConfirmClear(false), 4000)
      return
    }
    if (clearTimer.current) window.clearTimeout(clearTimer.current)
    setConfirmClear(false)
    await api.resetPositions()
    await refresh()
    toast('播放记录已清空')
  }

  return (
    <>
      <div className="page-head">
        <div className="titles">
          <h1>播放记录</h1>
          <div className="sub">
            {records.length
              ? `${records.length} 条记录 · 累计观看 ${formatDuration(totalWatched)} · 点击卡片从上次位置继续`
              : '播放过的视频会自动记录在这里'}
          </div>
        </div>
        <div className="grow" />
        <div className="field" style={{ width: 220 }}>
          <Icon name="search" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索播放记录" />
        </div>
        <button
          className={`btn ${confirmClear ? 'danger' : 'ghost'}`}
          onClick={clearAll}
          disabled={!records.length}
          title="清空所有播放记录与进度"
        >
          <Icon name="trash" size={15} /> {confirmClear ? '再点一次确认清空' : '清空记录'}
        </button>
      </div>

      <div className="page-body">
        {!records.length ? (
          <Empty
            icon="clock"
            title="还没有播放记录"
            desc="播放任意本地或网盘视频后，这里会保留进度，下次点开直接从断点继续，倍速、字幕、弹幕设置也会一并恢复。"
          >
            <button className="btn primary" onClick={() => setTab('library')}>
              <Icon name="play" size={15} /> 去挑一部片子
            </button>
          </Empty>
        ) : !filtered.length ? (
          <Empty icon="search" title="没有匹配的记录" desc="换个关键词试试，支持按片名和来源搜索。" />
        ) : (
          groups.map((g) => (
            <section key={g.label} className="hgroup fade-in">
              <div className="hgroup-head">
                <span className="hgroup-title">{g.label}</span>
                <span className="hgroup-count">{g.items.length}</span>
                <span className="hgroup-line" />
              </div>
              <div className="hgrid">
                {g.items.map((r) => {
                  const pct = Math.round(r.progress * 100)
                  return (
                    <div
                      key={r.key}
                      className="vcard hcard"
                      role="button"
                      tabIndex={0}
                      onClick={() => play(r)}
                      onKeyDown={(e) => e.key === 'Enter' && play(r)}
                      title={`${r.title}\n${r.kind === 'cloud' ? '夸克网盘' : r.subtitle}\n上次看到 ${formatDuration(r.pos)}${
                        r.duration ? ` / ${formatDuration(r.duration)}` : ''
                      }`}
                    >
                      <div className="thumb">
                        <div className="glyph">
                          <Icon name="play" size={20} />
                        </div>
                        <div className="playmask">
                          <Icon name="play" size={30} />
                        </div>
                        {r.kind === 'cloud' ? (
                          <div className="corner">
                            <span className="tag">夸克</span>
                          </div>
                        ) : null}
                        {r.duration ? <span className="dur">{formatDuration(r.duration)}</span> : null}
                        <button className="hdel" title="删除这条记录" onClick={(e) => remove(r, e)}>
                          <Icon name="close" size={13} />
                        </button>
                        {r.progress > 0.005 ? (
                          <div className="progress">
                            <i
                              style={{
                                width: `${Math.min(100, r.progress * 100)}%`,
                                background: r.finished
                                  ? 'linear-gradient(90deg, #12a06a, #2fd39a)'
                                  : 'linear-gradient(90deg, var(--accent), var(--accent-2))',
                              }}
                            />
                          </div>
                        ) : null}
                      </div>
                      <div className="meta">
                        <div className="name" title={r.title}>
                          {r.title}
                        </div>
                        <div className="sub">
                          <span className="htag">{r.kind === 'cloud' ? '网盘' : '本地'}</span>
                          <span>{formatDate(r.at)}</span>
                        </div>
                        <div className="hline">
                          <span className={r.finished ? 'done' : ''}>
                            {r.finished ? '已看完' : `已看 ${pct}%`}
                          </span>
                          <span>{r.duration ? `剩 ${formatDuration(Math.max(0, r.duration - r.pos))}` : ''}</span>
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </section>
          ))
        )}
      </div>
    </>
  )
}
