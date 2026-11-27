import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './lib/api'
import type { PlayTarget, Snapshot } from './lib/types'
import { AppCtx, type Tab } from './lib/ctx'
import { ToastProvider, useToast } from './components/ui'
import { Icon } from './components/Icon'
import { LibraryPage } from './pages/Library'
import { CloudPage } from './pages/Cloud'
import { ResourcePage } from './pages/Resource'
import { HistoryPage } from './pages/History'
import { SettingsPage } from './pages/Settings'
import { Player } from './player/Player'

const NAV: { id: Tab; label: string; icon: string }[] = [
  { id: 'library', label: '全部', icon: 'layers' },
  { id: 'cloud', label: '网盘', icon: 'cloud' },
  { id: 'resource', label: '资源', icon: 'compass' },
  { id: 'history', label: '记录', icon: 'clock' },
  { id: 'settings', label: '设置', icon: 'settings' },
]

function Shell() {
  const toast = useToast()
  const [snap, setSnap] = useState<Snapshot | null>(null)
  const [tab, setTab] = useState<Tab>(() => {
    const h = window.location.hash.replace('#', '')
    return ['library', 'cloud', 'resource', 'history', 'settings'].includes(h) ? (h as Tab) : 'library'
  })
  const [player, setPlayer] = useState<{ target: PlayTarget; queue: PlayTarget[] } | null>(null)
  const [version, setVersion] = useState('')
  const [isMax, setIsMax] = useState(false)

  const refresh = useCallback(async () => {
    const next = await api.snapshot()
    setSnap(next)
  }, [])

  useEffect(() => {
    ;(async () => {
      const [s, info] = await Promise.all([api.snapshot(), api.info()])
      setSnap(s)
      setVersion(`${info.version} · Electron ${info.electron}`)
    })().catch((e) => toast(e.message || '初始化失败', 'error'))
  }, [toast])

  useEffect(() => {
    if (!snap) return
    document.documentElement.dataset.theme = snap.settings.theme
  }, [snap?.settings.theme])

  const patch = useCallback(
    async (p: Partial<Snapshot['settings']>) => {
      const next = await api.patchSettings(p)
      setSnap((prev) => (prev ? { ...prev, settings: next } : prev))
    },
    []
  )

  const openPlayer = useCallback((target: PlayTarget, queue?: PlayTarget[]) => {
    setPlayer({ target, queue: queue && queue.length ? queue : [target] })
  }, [])

  useEffect(() => {
    if (window.location.hash.replace('#', '') !== tab) window.location.hash = tab
  }, [tab])

  const ctxValue = useMemo(
    () => (snap ? { snap, patch, refresh, openPlayer, setTab, toast } : null),
    [snap, patch, refresh, openPlayer, toast]
  )

  if (!snap || !ctxValue) {
    return (
      <div className="shell" style={{ display: 'grid', placeItems: 'center' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 14 }}>
          <div className="rail-logo" style={{ width: 56, height: 56, borderRadius: 18, background: 'linear-gradient(140deg,#5566f5,#9b6bff)', display: 'grid', placeItems: 'center', color: '#fff' }}>
            <Icon name="play" size={26} />
          </div>
          <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>正在启动 GlassPlayer…</span>
        </div>
      </div>
    )
  }

  return (
    <AppCtx.Provider value={ctxValue}>
      <div className="shell">
        <div className="titlebar">
          <div className="brand">
            <span className="dot">
              <Icon name="play" size={11} strokeWidth={2.4} />
            </span>
            GlassPlayer
          </div>
          <span className="hint">{version}</span>
          <div className="spacer" />
          <div className="winbts">
            <button onClick={() => api.minimize()} title="最小化">
              <Icon name="minimize" size={14} />
            </button>
            <button
              onClick={async () => setIsMax(await api.toggleMax())}
              title={isMax ? '还原' : '最大化'}
            >
              <Icon name={isMax ? 'copy' : 'expand'} size={13} />
            </button>
            <button className="close" onClick={() => api.close()} title="关闭">
              <Icon name="close" size={14} />
            </button>
          </div>
        </div>

        <div className="body-row">
          <nav className="rail">
            <div className="logo">
              <Icon name="play" size={20} strokeWidth={2.2} />
            </div>
            {NAV.map((n) => (
              <button
                key={n.id}
                className={`nav-item ${tab === n.id ? 'active' : ''}`}
                onClick={() => setTab(n.id)}
              >
                <Icon name={n.icon} size={21} />
                {n.label}
              </button>
            ))}
            <div className="rail-spacer" />
            <button
              className="rail-user"
              title={snap.settings.quarkUserName || '未登录夸克网盘'}
              onClick={() => setTab(snap.settings.quarkCookie ? 'settings' : 'cloud')}
            >
              {snap.settings.quarkUserName ? snap.settings.quarkUserName.slice(0, 2) : <Icon name="user" size={18} />}
            </button>
          </nav>

          <main className="content">
            {tab === 'library' && <LibraryPage />}
            {tab === 'cloud' && <CloudPage />}
            {tab === 'resource' && <ResourcePage />}
            {tab === 'history' && <HistoryPage />}
            {tab === 'settings' && <SettingsPage />}
          </main>
        </div>

        {player && (
          <Player
            target={player.target}
            queue={player.queue}
            settings={snap.settings}
            onPatch={patch}
            onExit={() => {
              setPlayer(null)
              refresh()
            }}
            onSaveMeta={() => {}}
            onPlayTarget={(t) => setPlayer({ target: t, queue: player.queue })}
          />
        )}
      </div>
    </AppCtx.Provider>
  )
}

export default function App() {
  return (
    <ToastProvider>
      <Shell />
    </ToastProvider>
  )
}
