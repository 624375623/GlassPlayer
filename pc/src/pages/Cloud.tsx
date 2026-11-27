import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../lib/api'
import type { PlayTarget, QuarkFile } from '../lib/types'
import { useApp } from '../lib/ctx'
import { formatDate, formatSize, stripExt } from '../lib/format'
import { Icon, Empty } from '../components/Icon'
import { Modal, SpinnerBox } from '../components/ui'
import { ViewSortMenu } from '../components/ViewSort'
import { VideoCard, VideoRow } from '../components/VideoCard'

const VIDEO_EXT = /\.(mp4|mkv|webm|mov|m4v|avi|ts|flv|wmv|mpg|mpeg|rmvb|m2ts)$/i

const SORTS = [
  { value: 0, label: '最近更新' },
  { value: 1, label: '名称' },
  { value: 2, label: '大小' },
]

type Crumb = { fid: string; name: string }

export function CloudPage() {
  const { snap, patch, openPlayer, refresh, toast } = useApp()
  const loggedIn = !!snap.settings.quarkCookie

  const [crumbs, setCrumbs] = useState<Crumb[]>([{ fid: '0', name: '根目录' }])
  const [files, setFiles] = useState<QuarkFile[]>([])
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [qr, setQr] = useState<{ token: string; dataUrl: string } | null>(null)
  const [qrState, setQrState] = useState<'loading' | 'wait' | 'expired' | 'ok'>('loading')
  const [loggingIn, setLoggingIn] = useState(false)
  const [hiddenOpen, setHiddenOpen] = useState(false)
  const [menu, setMenu] = useState<{ x: number; y: number; file: QuarkFile } | null>(null)
  const pollRef = useRef<number | null>(null)

  const currentFid = crumbs[crumbs.length - 1].fid

  /* ---------------- 登录 ---------------- */
  const fetchQr = useCallback(async () => {
    setQrState('loading')
    try {
      const res = await api.quarkQr()
      setQr(res)
      setQrState('wait')
    } catch (e: any) {
      setQrState('expired')
      toast(e.message || '获取二维码失败', 'error')
    }
  }, [toast])

  useEffect(() => {
    if (loggedIn) return
    fetchQr()
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current)
    }
  }, [loggedIn, fetchQr])

  useEffect(() => {
    if (!qr || qrState !== 'wait') return
    if (pollRef.current) window.clearInterval(pollRef.current)
    pollRef.current = window.setInterval(async () => {
      try {
        const res = await api.quarkPoll(qr.token)
        if (res.state === 'ok') {
          if (pollRef.current) window.clearInterval(pollRef.current)
          setQrState('ok')
          setLoggingIn(true)
          const st = await api.quarkStatus().catch(() => null)
          await refresh()
          toast(`登录成功${st?.nickname ? '：' + st.nickname : ''}`)
          setTimeout(() => setLoggingIn(false), 600)
        } else if (res.state === 'expired') {
          if (pollRef.current) window.clearInterval(pollRef.current)
          setQrState('expired')
        }
      } catch (_) {}
    }, 2200)
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current)
    }
  }, [qr, qrState, refresh, toast])

  /* ---------------- 目录 ---------------- */
  const load = useCallback(
    async (fid: string, nextPage = 1, append = false) => {
      setLoading(true)
      setError('')
      try {
        const res = await api.quarkList(fid, nextPage, 100)
        setFiles((prev) => (append ? [...prev, ...res.list] : res.list))
        setHasMore(res.hasMore)
        setPage(nextPage)
      } catch (e: any) {
        setError(e.message || '读取目录失败')
        if (!append) setFiles([])
        if (e.code === 'expired' || /过期|登录/.test(e.message || '')) {
          await api.quarkLogout().catch(() => {})
          await refresh()
        }
      } finally {
        setLoading(false)
      }
    },
    [refresh]
  )

  useEffect(() => {
    if (!loggedIn) return
    load(currentFid, 1)
  }, [loggedIn, currentFid, load])

  const visible = useMemo(() => {
    const hidden = new Set(snap.hiddenCloud)
    let list = files.filter((f) => (f.isDir ? !hidden.has(f.fid) : VIDEO_EXT.test(f.name)))
    if (query.trim()) {
      const q = query.trim().toLowerCase()
      list = list.filter((f) => f.name.toLowerCase().includes(q))
    }
    const asc = snap.settings.cloudSortAscending
    const mode = snap.settings.cloudSortMode
    const dirs = list.filter((f) => f.isDir)
    const vids = list.filter((f) => !f.isDir)
    const cmp = (a: QuarkFile, b: QuarkFile) => {
      let v = 0
      if (mode === 1) v = a.name.localeCompare(b.name, 'zh-CN')
      else if (mode === 2) v = a.size - b.size
      else v = a.updatedAt - b.updatedAt
      return asc ? v : -v
    }
    return [...dirs.sort(cmp), ...vids.sort(cmp)]
  }, [files, query, snap.hiddenCloud, snap.settings.cloudSortAscending, snap.settings.cloudSortMode])

  const queue: PlayTarget[] = visible
    .filter((f) => !f.isDir)
    .map((f) => ({
      key: `quark://${f.fid}`,
      title: stripExt(f.name),
      subtitle: crumbs[crumbs.length - 1].name,
      kind: 'cloud' as const,
      fid: f.fid,
      meta: {
        name: f.name,
        size: f.size,
        updatedAt: f.updatedAt,
        parentFid: currentFid,
        parentName: crumbs[crumbs.length - 1].name,
      },
    }))

  const openVideo = (f: QuarkFile) => {
    api
      .saveCloudMeta({
        fid: f.fid,
        name: f.name,
        size: f.size,
        updatedAt: f.updatedAt,
        parentFid: currentFid,
        parentName: crumbs[crumbs.length - 1].name,
        isDir: false,
      })
      .catch(() => {})
    openPlayer(
      {
        key: `quark://${f.fid}`,
        title: stripExt(f.name),
        subtitle: crumbs[crumbs.length - 1].name,
        kind: 'cloud',
        fid: f.fid,
        meta: { name: f.name, size: f.size, updatedAt: f.updatedAt, parentFid: currentFid },
      },
      queue
    )
  }

  const enterFolder = (f: QuarkFile) => {
    setCrumbs((prev) => [...prev, { fid: f.fid, name: f.name }])
    setQuery('')
  }

  const logout = async () => {
    await api.quarkLogout()
    await refresh()
    setCrumbs([{ fid: '0', name: '根目录' }])
    setFiles([])
    toast('已退出夸克网盘')
  }

  /* ---------------- 未登录 ---------------- */
  if (!loggedIn) {
    return (
      <>
        <div className="page-head">
          <div className="titles">
            <h1>夸克网盘</h1>
            <div className="sub">扫码登录后即可浏览、播放网盘里的视频</div>
          </div>
        </div>
        <div className="login-wrap">
          <div className="login-card fade-in">
            <div className="qr-box">
              {qr ? <img src={qr.dataUrl} alt="登录二维码" /> : null}
              {qrState === 'loading' && (
                <div className="mask">
                  <span className="spinner" />
                  正在获取二维码…
                </div>
              )}
              {qrState === 'expired' && (
                <div className="mask">
                  <span>二维码已过期</span>
                  <button className="btn primary" onClick={fetchQr}>
                    重新获取
                  </button>
                </div>
              )}
              {qrState === 'ok' && (
                <div className="mask">
                  <span style={{ color: '#12a06a', fontWeight: 700 }}>登录成功</span>
                  <span>正在读取账号信息…</span>
                </div>
              )}
            </div>
            <div className="login-side">
              <h2>手机夸克 App 扫码登录</h2>
              <p>登录信息只保存在本机，与手机版 GlassPlayer 一致：扫码拿 ticket、换网页端 Cookie，不上传任何账号数据。</p>
              <ul className="login-steps">
                <li>
                  <b>1</b>
                  <span>打开手机夸克 App，点右上角「扫一扫」</span>
                </li>
                <li>
                  <b>2</b>
                  <span>扫描左侧二维码，在手机上确认登录</span>
                </li>
                <li>
                  <b>3</b>
                  <span>电脑端自动完成登录，目录立即加载</span>
                </li>
              </ul>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <button className="btn" onClick={fetchQr} disabled={qrState === 'loading'}>
                  <Icon name="refresh" size={15} /> 刷新二维码
                </button>
                {loggingIn ? <SpinnerBox text="登录中…" /> : null}
              </div>
              <div style={{ marginTop: 14, fontSize: 11.5, color: 'var(--ink-3)', lineHeight: 1.8 }}>
                提示：扫码后手机端需要点「确认登录」。二维码约 2 分钟有效。
              </div>
            </div>
          </div>
        </div>
      </>
    )
  }

  /* ---------------- 已登录 ---------------- */
  return (
    <>
      <div className="page-head">
        <div className="titles" style={{ minWidth: 0 }}>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            夸克网盘
            <span className="tag">{snap.settings.quarkUserName || '已登录'}</span>
          </h1>
          <div className="sub">
            <div className="crumbs">
              {crumbs.map((c, i) => (
                <span key={c.fid + i} style={{ display: 'inline-flex', alignItems: 'center' }}>
                  {i > 0 && <span className="sep">/</span>}
                  <button onClick={() => setCrumbs(crumbs.slice(0, i + 1))}>{c.name}</button>
                </span>
              ))}
            </div>
          </div>
        </div>
        <div className="grow" />
        <div className="field" style={{ width: 200 }}>
          <Icon name="search" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="在当前目录筛选" />
        </div>
        <button className="icon-btn" title="刷新" onClick={() => load(currentFid, 1)}>
          <Icon name="refresh" />
        </button>
        {snap.hiddenCloud.length > 0 && (
          <button className="btn" onClick={() => setHiddenOpen(true)}>
            <Icon name="folder" size={15} /> 已屏蔽 {snap.hiddenCloud.length}
          </button>
        )}
        <ViewSortMenu
          grid={snap.settings.cloudGridLayout}
          onGrid={(v) => patch({ cloudGridLayout: v })}
          sort={snap.settings.cloudSortMode}
          onSort={(v) => patch({ cloudSortMode: v })}
          asc={snap.settings.cloudSortAscending}
          onAsc={(v) => patch({ cloudSortAscending: v })}
          options={SORTS}
          extra={
            <div className="form-row" style={{ padding: '10px 0 0', borderBottom: 0 }}>
              <div className="label">
                <b>播放方式</b>
                <span>{snap.settings.quarkPlayMode === 'stream' ? '转码流播（省流量、起播快）' : '原画直链（画质最高）'}</span>
              </div>
              <button
                className="chip"
                onClick={() => patch({ quarkPlayMode: snap.settings.quarkPlayMode === 'stream' ? 'download' : 'stream' })}
              >
                切换
              </button>
            </div>
          }
        />
        <button className="btn danger" onClick={logout}>
          <Icon name="logout" size={15} /> 退出登录
        </button>
      </div>

      <div className="page-body">
        {loading && !files.length ? (
          <SpinnerBox text="正在读取目录…" />
        ) : error ? (
          <Empty icon="cloud" title="读取失败" desc={error}>
            <button className="btn primary" onClick={() => load(currentFid, 1)}>
              重试
            </button>
          </Empty>
        ) : !visible.length ? (
          <Empty
            icon="folder"
            title={query ? '没有匹配的文件' : '这个目录里没有视频'}
            desc={
              query
                ? '换个关键词试试，或者清空筛选。'
                : 'GlassPlayer 只显示视频文件和文件夹。换个目录看看，或者到「资源」页搜一部剧转存进来。'
            }
          />
        ) : snap.settings.cloudGridLayout ? (
          <div className="grid fade-in">
            {visible.map((f) =>
              f.isDir ? (
                <div
                  key={f.fid}
                  className="folder-card"
                  onClick={() => enterFolder(f)}
                  onContextMenu={(e) => {
                    e.preventDefault()
                    setMenu({ x: e.clientX, y: e.clientY, file: f })
                  }}
                >
                  <div className="fc-ic">
                    <Icon name="folder" size={19} />
                  </div>
                  <div className="fc-t">
                    <b title={f.name}>{f.name}</b>
                    <span>文件夹 · {formatDate(f.updatedAt)}</span>
                  </div>
                  <Icon name="right" size={15} />
                </div>
              ) : (
                <VideoCard
                  key={f.fid}
                  title={stripExt(f.name)}
                  sub={`${formatSize(f.size)} · ${formatDate(f.updatedAt)}`}
                  durationMs={snap.cloudMeta[f.fid]?.durationMs || snap.positions[`quark://${f.fid}`]?.duration}
                  progress={
                    snap.positions[`quark://${f.fid}`]?.duration
                      ? snap.positions[`quark://${f.fid}`].pos / snap.positions[`quark://${f.fid}`].duration
                      : 0
                  }
                  favorite={snap.favorites.includes(`quark://${f.fid}`)}
                  badge="夸克"
                  onOpen={() => openVideo(f)}
                  onContext={(e) => {
                    e.preventDefault()
                    setMenu({ x: e.clientX, y: e.clientY, file: f })
                  }}
                  onFavorite={async () => {
                    await api.toggleFavorite(`quark://${f.fid}`)
                    await refresh()
                  }}
                />
              )
            )}
          </div>
        ) : (
          <div className="rows fade-in">
            {visible.map((f) =>
              f.isDir ? (
                <VideoRow
                  key={f.fid}
                  title={f.name}
                  sub={`文件夹 · ${formatDate(f.updatedAt)}`}
                  icon="folder"
                  folder
                  onOpen={() => enterFolder(f)}
                />
              ) : (
                <VideoRow
                  key={f.fid}
                  title={stripExt(f.name)}
                  sub={`${formatSize(f.size)} · ${formatDate(f.updatedAt)}`}
                  favorite={snap.favorites.includes(`quark://${f.fid}`)}
                  onOpen={() => openVideo(f)}
                  onContext={(e) => {
                    e.preventDefault()
                    setMenu({ x: e.clientX, y: e.clientY, file: f })
                  }}
                  onFavorite={async () => {
                    await api.toggleFavorite(`quark://${f.fid}`)
                    await refresh()
                  }}
                />
              )
            )}
          </div>
        )}

        {hasMore && !query ? (
          <div style={{ display: 'flex', justifyContent: 'center', marginTop: 16 }}>
            <button className="btn" disabled={loading} onClick={() => load(currentFid, page + 1, true)}>
              {loading ? '加载中…' : '加载更多'}
            </button>
          </div>
        ) : null}
      </div>

      {menu && (
        <>
          <div
            style={{ position: 'fixed', inset: 0, zIndex: 58 }}
            onMouseDown={() => setMenu(null)}
            onContextMenu={(e) => {
              e.preventDefault()
              setMenu(null)
            }}
          />
          <div
            style={{
              position: 'fixed',
              left: Math.min(menu.x, window.innerWidth - 190),
              top: Math.min(menu.y, window.innerHeight - 210),
              zIndex: 59,
              width: 176,
              padding: 6,
              borderRadius: 14,
              background: 'var(--glass-strong)',
              border: '1px solid var(--line-strong)',
              boxShadow: 'var(--shadow-lg)',
              backdropFilter: 'blur(28px) saturate(180%)',
            }}
          >
            <div style={{ padding: '6px 10px', fontSize: 11, color: 'var(--ink-3)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {menu.file.name}
            </div>
            {menu.file.isDir ? (
              <button className="pmenu-item" onClick={() => { enterFolder(menu.file); setMenu(null) }}>
                进入文件夹
              </button>
            ) : (
              <button className="pmenu-item" onClick={() => { openVideo(menu.file); setMenu(null) }}>
                播放
              </button>
            )}
            <button
              className="pmenu-item"
              onClick={async () => {
                await api.toggleFavorite(`quark://${menu.file.fid}`)
                await refresh()
                setMenu(null)
              }}
            >
              {snap.favorites.includes(`quark://${menu.file.fid}`) ? '取消收藏' : '收藏'}
            </button>
            {menu.file.isDir && (
              <button
                className="pmenu-item"
                onClick={async () => {
                  await api.toggleHiddenCloud(menu.file.fid)
                  await api.saveCloudMeta({ fid: menu.file.fid, name: menu.file.name, isDir: true, updatedAt: menu.file.updatedAt })
                  await refresh()
                  setMenu(null)
                  toast('已屏蔽，列表不再显示')
                }}
              >
                屏蔽此文件夹
              </button>
            )}
            <button
              className="pmenu-item"
              onClick={async () => {
                await api.copy(menu.file.name)
                setMenu(null)
                toast('名称已复制')
              }}
            >
              复制名称
            </button>
          </div>
        </>
      )}

      {hiddenOpen && (
        <Modal title="已屏蔽的文件夹" subtitle="屏蔽只影响显示，不会删除任何文件" onClose={() => setHiddenOpen(false)}>
          {snap.hiddenCloud.length === 0 ? (
            <div style={{ padding: 20, color: 'var(--ink-3)', fontSize: 12.5 }}>没有屏蔽任何文件夹</div>
          ) : (
            snap.hiddenCloud.map((fid) => (
              <div className="folder-row" key={fid}>
                <Icon name="folder" size={16} />
                <span className="p">{snap.cloudMeta[fid]?.name || fid}</span>
                <button
                  className="btn ghost"
                  onClick={async () => {
                    await api.toggleHiddenCloud(fid)
                    await refresh()
                  }}
                >
                  恢复
                </button>
              </div>
            ))
          )}
        </Modal>
      )}
    </>
  )
}
