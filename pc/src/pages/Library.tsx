import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../lib/api'
import type { LocalItem, PlayTarget } from '../lib/types'
import { useApp } from '../lib/ctx'
import { formatDate, formatDuration, formatSize, stripExt } from '../lib/format'
import { Icon } from '../components/Icon'
import { Empty } from '../components/Icon'
import { Segmented, SpinnerBox } from '../components/ui'
import { ViewSortMenu } from '../components/ViewSort'
import { VideoCard, VideoRow } from '../components/VideoCard'

type Entry = {
  key: string
  title: string
  kind: 'local' | 'cloud'
  path?: string
  fid?: string
  size: number
  time: number
  durationMs: number
  progress: number
  favorite: boolean
  dir?: string
}

const SORTS = [
  { value: 0, label: '最近更新' },
  { value: 1, label: '名称' },
  { value: 2, label: '大小' },
  { value: 3, label: '时长' },
]

export function LibraryPage() {
  const { snap, patch, openPlayer, refresh, toast, setTab } = useApp()
  const [mode, setMode] = useState<'all' | 'fav' | 'recent'>('all')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<LocalItem[]>([])
  const [scanning, setScanning] = useState(false)
  const [scanText, setScanText] = useState('')
  const folders = snap.settings.libraryFolders

  const scan = useCallback(async () => {
    if (!folders.length) {
      setItems([])
      return
    }
    setScanning(true)
    setScanText('正在扫描本地视频…')
    try {
      const list = await api.scan(folders)
      setItems(list)
      setScanText(`已找到 ${list.length} 个视频`)
    } catch (e: any) {
      toast(e.message || '扫描失败', 'error')
    } finally {
      setScanning(false)
    }
  }, [folders.join('|'), toast])

  useEffect(() => {
    scan()
  }, [scan])

  useEffect(() => {
    return api.onEvent((msg) => {
      if (msg.type !== 'lib:progress') return
      const p = msg.payload
      if (p.found != null) setScanText(`正在扫描：${p.folder}（已找到 ${p.found}）`)
      else setScanText(`正在扫描：${p.folder}`)
    })
  }, [])

  const cloudEntries = useMemo<Entry[]>(() => {
    const out: Entry[] = []
    for (const meta of Object.values(snap.cloudMeta)) {
      if (meta.isDir) continue
      const key = `quark://${meta.fid}`
      const pos = snap.positions[key]
      out.push({
        key,
        title: stripExt(meta.name),
        kind: 'cloud',
        fid: meta.fid,
        size: meta.size,
        time: meta.updatedAt || meta.updatedAt,
        durationMs: meta.durationMs || pos?.duration || 0,
        progress: pos && pos.duration ? pos.pos / pos.duration : 0,
        favorite: snap.favorites.includes(key),
      })
    }
    return out
  }, [snap.cloudMeta, snap.positions, snap.favorites])

  const localEntries = useMemo<Entry[]>(() => {
    const out: Entry[] = []
    for (const it of items) {
      const pos = snap.positions[it.key]
      out.push({
        key: it.key,
        title: stripExt(it.name),
        kind: 'local',
        path: it.path,
        size: it.size,
        time: it.mtime,
        durationMs: pos?.duration || 0,
        progress: pos && pos.duration ? pos.pos / pos.duration : 0,
        favorite: snap.favorites.includes(it.key),
        dir: it.dir,
      })
    }
    return out
  }, [items, snap.positions, snap.favorites])

  const allEntries = useMemo(() => {
    const map = new Map<string, Entry>()
    for (const e of [...cloudEntries, ...localEntries]) map.set(e.key, e)
    return [...map.values()]
  }, [cloudEntries, localEntries])

  const entries = useMemo(() => {
    let list = allEntries
    if (mode === 'fav') list = list.filter((e) => snap.favorites.includes(e.key))
    if (mode === 'recent') {
      const order = new Map(snap.recent.map((k, i) => [k, i]))
      list = list.filter((e) => order.has(e.key)).sort((a, b) => (order.get(a.key)! - order.get(b.key)!))
    }
    if (query.trim()) {
      const q = query.trim().toLowerCase()
      list = list.filter((e) => e.title.toLowerCase().includes(q))
    }
    if (mode !== 'recent') {
      const asc = snap.settings.sortAscending
      const modeSort = snap.settings.sortMode
      list = [...list].sort((a, b) => {
        let v = 0
        if (modeSort === 1) v = a.title.localeCompare(b.title, 'zh-CN')
        else if (modeSort === 2) v = a.size - b.size
        else if (modeSort === 3) v = a.durationMs - b.durationMs
        else v = a.time - b.time
        return asc ? v : -v
      })
    }
    return list
  }, [allEntries, mode, query, snap.favorites, snap.recent, snap.settings.sortAscending, snap.settings.sortMode])

  // 连播队列只放同类条目：本地视频不会自动跳到网盘文件上
  const queueFor = (e: Entry): PlayTarget[] =>
    entries
      .filter((x) => x.kind === e.kind)
      .map((x) => ({
        key: x.key,
        title: x.title,
        subtitle: x.kind === 'cloud' ? '夸克网盘' : x.dir,
        kind: x.kind,
        path: x.path,
        fid: x.fid,
        meta: x.fid ? { name: x.title, size: x.size } : undefined,
      }))

  const addFolder = async () => {
    const picked = await api.pickFolders()
    if (!picked.length) return
    const next = [...new Set([...folders, ...picked])]
    await patch({ libraryFolders: next })
    toast(`已添加 ${picked.length} 个文件夹`)
  }

  const play = (e: Entry) => {
    if (e.kind === 'cloud' && !snap.settings.quarkCookie) {
      toast('请先在网盘页扫码登录', 'error')
      setTab('cloud')
      return
    }
    openPlayer(
      {
        key: e.key,
        title: e.title,
        subtitle: e.kind === 'cloud' ? '夸克网盘' : e.dir,
        kind: e.kind,
        path: e.path,
        fid: e.fid,
        meta: e.fid ? { name: e.title, size: e.size } : undefined,
      },
      queueFor(e)
    )
  }

  const toggleFav = async (e: Entry) => {
    await api.toggleFavorite(e.key)
    await refresh()
  }

  const subtitle =
    mode === 'fav'
      ? `${entries.length} 个收藏`
      : mode === 'recent'
        ? `${entries.length} 条播放记录`
        : folders.length
          ? `${entries.length} 个视频 · ${folders.length} 个文件夹`
          : '还没有添加本地文件夹'

  return (
    <>
      <div className="page-head">
        <div className="titles">
          <h1>全部视频</h1>
          <div className="sub">{scanning ? scanText : subtitle}</div>
        </div>
        <div className="grow" />
        <div className="field" style={{ width: 220 }}>
          <Icon name="search" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索本地与网盘视频" />
        </div>
        <button className="icon-btn" title="重新扫描" onClick={scan} disabled={!folders.length}>
          <Icon name="refresh" />
        </button>
        <button className="btn" onClick={addFolder}>
          <Icon name="folderPlus" size={16} /> 添加文件夹
        </button>
        <ViewSortMenu
          grid={snap.settings.gridLayout}
          onGrid={(v) => patch({ gridLayout: v })}
          sort={snap.settings.sortMode}
          onSort={(v) => patch({ sortMode: v })}
          asc={snap.settings.sortAscending}
          onAsc={(v) => patch({ sortAscending: v })}
          options={SORTS}
        />
      </div>

      <div className="page-body">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
          <Segmented
            value={mode}
            options={[
              { value: 'all', label: '全部' },
              { value: 'fav', label: '收藏' },
              { value: 'recent', label: '记录' },
            ]}
            onChange={(v) => setMode(v as any)}
          />
          {snap.settings.quarkUserName ? (
            <span className="tag gray">网盘：{snap.settings.quarkUserName}</span>
          ) : (
            <span className="tag gray">网盘未登录</span>
          )}
          {scanning ? <SpinnerBox text={scanText} /> : null}
        </div>

        {!folders.length && !allEntries.length ? (
          <Empty
            icon="folderPlus"
            title="先添加一个视频文件夹"
            desc="选择电影、剧集所在的文件夹，GlassPlayer 会扫描其中的视频。也可以直接在「网盘」页扫码登录夸克，播放云端文件。"
          >
            <button className="btn primary" onClick={addFolder}>
              <Icon name="folderPlus" size={16} /> 选择文件夹
            </button>
          </Empty>
        ) : !entries.length ? (
          <Empty
            icon={mode === 'fav' ? 'heart' : mode === 'recent' ? 'clock' : 'film'}
            title={mode === 'fav' ? '还没有收藏' : mode === 'recent' ? '还没有播放记录' : '没有匹配的视频'}
            desc={
              mode === 'fav'
                ? '在视频卡片上点心形图标即可收藏，网盘视频也支持。'
                : mode === 'recent'
                  ? '播放任意视频后，这里会保留进度，下次直接从断点继续。'
                  : '换个关键词，或者再添加一个文件夹试试。'
            }
          />
        ) : snap.settings.gridLayout ? (
          <div className="grid fade-in">
            {entries.map((e) => (
              <VideoCard
                key={e.key}
                title={e.title}
                sub={`${e.kind === 'cloud' ? '夸克网盘' : '本地'} · ${formatDate(e.time)}`}
                durationMs={e.durationMs}
                progress={e.progress}
                favorite={e.favorite}
                badge={e.kind === 'cloud' ? '夸克' : undefined}
                onOpen={() => play(e)}
                onFavorite={() => toggleFav(e)}
              />
            ))}
          </div>
        ) : (
          <div className="rows fade-in">
            {entries.map((e) => (
              <VideoRow
                key={e.key}
                title={e.title}
                sub={`${e.kind === 'cloud' ? '夸克网盘' : e.dir || '本地'} · ${formatSize(e.size)}`}
                durationMs={e.durationMs ? formatDuration(e.durationMs) : undefined}
                favorite={e.favorite}
                onOpen={() => play(e)}
                onFavorite={() => toggleFav(e)}
              />
            ))}
          </div>
        )}
      </div>
    </>
  )
}
