import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../lib/api'
import type { ResourceLink, SourceMeta } from '../lib/types'
import { useApp } from '../lib/ctx'
import { formatDate, panLabel, sourceLabel } from '../lib/format'
import { Icon, Empty } from '../components/Icon'
import { Sheet, SpinnerBox, useToast } from '../components/ui'

const PAGE = 40

export function ResourcePage() {
  const { snap, patch, refresh, toast, setTab } = useApp()
  const [sources, setSources] = useState<SourceMeta[]>([])
  const [source, setSource] = useState(snap.settings.resourceSource || 'mipan')
  const [keyword, setKeyword] = useState(snap.settings.resourceLastQuery || '')
  const [loading, setLoading] = useState(false)
  const [progress, setProgress] = useState<Record<string, number>>({})
  const [results, setResults] = useState<ResourceLink[]>([])
  const [errors, setErrors] = useState<{ label: string; message: string }[]>([])
  const [checked, setChecked] = useState<Record<string, string>>({})
  const [page, setPage] = useState(1)
  const [onlyQuark, setOnlyQuark] = useState(true)
  const [hideInvalid, setHideInvalid] = useState(true)
  const [preview, setPreview] = useState<{ link: ResourceLink } | null>(null)
  const [searchId, setSearchId] = useState(0)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const attempted = useRef<Set<string>>(new Set())

  useEffect(() => {
    api.sources().then(setSources).catch(() => {})
  }, [])

  useEffect(() => {
    return api.onEvent((msg) => {
      if (msg.type === 'res:progress') {
        setProgress((p) => ({ ...p, [msg.payload.source]: msg.payload.count }))
      }
    })
  }, [])

  /** 去重后的全部结果（未过滤失效） */
  const deduped = useMemo(() => {
    const list = onlyQuark ? results.filter((r) => r.type === 'quark') : results
    const seen = new Set<string>()
    return list
      .filter((r) => {
        const key = r.url.split('#')[0].split('?pwd=')[0].replace(/\/+$/, '')
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
      .sort((a, b) => String(b.datetime).localeCompare(String(a.datetime)))
  }, [results, onlyQuark])

  const invalidCount = deduped.filter((r) => checked[r.url] === 'invalid').length

  const filtered = hideInvalid ? deduped.filter((r) => checked[r.url] !== 'invalid') : deduped

  const visible = filtered.slice(0, page * PAGE)

  /** 逐条校验链接：失效的直接从列表里剔掉，不再让人白点 */
  useEffect(() => {
    const slice = filtered.slice(0, page * PAGE)
    const need = slice.filter((r) => r.url && !attempted.current.has(r.url))
    if (!need.length) return
    need.forEach((r) => attempted.current.add(r.url))
    api
      .checkLinks(need)
      .then((map) => {
        setChecked((prev) => {
          const next = { ...prev }
          for (const link of need) {
            const state =
              map[link.url] || map[link.url.split('#')[0].split('?pwd=')[0].replace(/\/+$/, '')]
            if (state) next[link.url] = state
            else next[link.url] = 'unknown'
          }
          return next
        })
      })
      .catch(() => {
        need.forEach((r) => attempted.current.delete(r.url))
      })
  }, [filtered, page])

  const doSearch = useCallback(async () => {
    const kw = keyword.trim()
    if (!kw) {
      toast('请输入要搜索的片名', 'error')
      return
    }
    setLoading(true)
    setResults([])
    setErrors([])
    setChecked({})
    setPage(1)
    setProgress({})
    setSearchId((n) => n + 1)
    attempted.current = new Set()
    patch({ resourceLastQuery: kw, resourceSource: source })
    try {
      const res = await api.search([source], kw)
      setResults(res.list)
      setErrors(res.errors)
      if (!res.list.length && !res.errors.length) toast('没有搜到结果，换个关键词试试')
    } catch (e: any) {
      toast(e.message || '搜索失败', 'error')
    } finally {
      setLoading(false)
    }
  }, [keyword, source, patch, toast])

  const loadMore = () => {
    // 新一页由下面的校验 effect 自动核对，失效的会立即被剔掉
    setPage((p) => p + 1)
  }

  const copy = async (link: ResourceLink) => {
    const text = link.password ? `${link.url} 提取码：${link.password}` : link.url
    await api.copy(text)
    toast('链接已复制')
  }

  const save = async (link: ResourceLink) => {
    if (!snap.settings.quarkCookie) {
      toast('请先到网盘页扫码登录，再转存', 'error')
      setTab('cloud')
      return
    }
    try {
      const res = await api.saveShare(link.url, link.password)
      toast(`已转存 ${res.count} 个文件到网盘根目录`)
      await refresh()
    } catch (e: any) {
      toast(e.message || '转存失败', 'error')
    }
  }

  const validOf = (link: ResourceLink) => {
    const state = checked[link.url]
    if (state === 'valid') return <span className="tag good">有效</span>
    if (state === 'invalid') return <span className="tag bad">失效</span>
    if (state === 'unknown') return <span className="tag warn">未验证</span>
    return null
  }

  return (
    <>
      <div className="page-head">
        <div className="titles">
          <h1>资源搜索</h1>
          <div className="sub">
            {loading
              ? `正在搜索${Object.keys(progress).length ? `：${sources.find((s) => s.id === source)?.label || ''} ${Object.values(progress)[0] || 0} 条` : '…'}`
              : filtered.length
                ? `${filtered.length} 条夸克资源${invalidCount ? ` · 已过滤 ${invalidCount} 条失效` : ''} · 点卡片预览目录，一键转存`
                : invalidCount
                  ? `搜到的 ${invalidCount} 条链接都失效了，换个关键词或来源试试`
                  : '五个公开分享站，搜夸克网盘资源'}
          </div>
        </div>
        <div className="grow" />
        <div className="field" style={{ width: 300 }}>
          <Icon name="search" />
          <input
            ref={inputRef}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && doSearch()}
            placeholder="输入剧名 / 电影名，回车搜索"
          />
        </div>
        <button className="btn primary" onClick={doSearch} disabled={loading}>
          {loading ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <Icon name="search" size={15} />}
          搜索
        </button>
      </div>

      <div className="page-body">
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
          {sources.map((s) => (
            <button
              key={s.id}
              className={`chip ${source === s.id ? 'active' : ''}`}
              onClick={() => {
                setSource(s.id)
                setProgress({})
              }}
            >
              {s.label}
              {progress[s.id] ? ` · ${progress[s.id]}` : ''}
            </button>
          ))}
          <span style={{ flex: 1 }} />
          <button className={`chip ${hideInvalid ? 'active' : ''}`} onClick={() => setHideInvalid((v) => !v)}>
            隐藏失效{invalidCount ? `（${invalidCount}）` : ''}
          </button>
          <button className={`chip ${onlyQuark ? 'active' : ''}`} onClick={() => setOnlyQuark((v) => !v)}>
            只看夸克
          </button>
        </div>

        {errors.length > 0 && (
          <div className="batch-bar" style={{ background: 'rgba(223,71,89,0.1)', color: 'var(--danger)' }}>
            <Icon name="info" size={15} />
            {errors.map((e) => `${e.label}：${e.message}`).join('；')}
          </div>
        )}

        {loading && !filtered.length ? (
          <SpinnerBox text="正在并行请求各个资源站…" />
        ) : !filtered.length ? (
          <Empty
            icon="compass"
            title={snap.settings.resourceLastQuery ? '换个关键词试试' : '搜一部想看的剧'}
            desc="支持觅盘、谷哥搜、KK搜、盘盘搜、Pansou.de 五个来源。搜到结果后可以预览分享目录，一键转存到自己的夸克网盘，然后直接播放。"
          >
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'center' }}>
              {['凡人修仙传', '三体', '沙丘', '狂飙'].map((k) => (
                <button
                  key={k}
                  className="chip"
                  onClick={() => {
                    setKeyword(k)
                    setTimeout(doSearch, 0)
                  }}
                >
                  {k}
                </button>
              ))}
            </div>
          </Empty>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }} className="fade-in">
            {visible.map((link) => (
              <div className="res-card" key={link.url} onClick={() => setPreview({ link })}>
                <div className="rl">
                  <div className="rt" title={link.note}>
                    {link.note}
                  </div>
                  <div className="rm">
                    <span className="tag">{panLabel(link.type)}</span>
                    <span className="tag gray">{sourceLabel(link)}</span>
                    {link.extra ? <span className="tag gray">{link.extra}</span> : null}
                    {validOf(link)}
                    {link.password ? <span>提取码 {link.password}</span> : null}
                    {link.datetime ? <span>{formatDate(link.datetime)}</span> : null}
                  </div>
                </div>
                <div className="racts" onClick={(e) => e.stopPropagation()}>
                  <button className="icon-btn" title="复制链接" onClick={() => copy(link)}>
                    <Icon name="copy" />
                  </button>
                  <button className="icon-btn" title="浏览器打开" onClick={() => api.openExternal(link.url)}>
                    <Icon name="external" />
                  </button>
                  <button className="btn primary" style={{ height: 30 }} onClick={() => save(link)}>
                    <Icon name="download" size={14} /> 转存
                  </button>
                </div>
              </div>
            ))}
            {filtered.length > visible.length ? (
              <div style={{ display: 'flex', justifyContent: 'center', marginTop: 10 }}>
                <button className="btn" onClick={loadMore}>
                  加载更多（还有 {filtered.length - visible.length} 条）
                </button>
              </div>
            ) : null}
          </div>
        )}
      </div>

      {preview && (
        <SharePreview
          link={preview.link}
          onClose={() => setPreview(null)}
          onSaved={async (count) => {
            toast(`已转存 ${count} 个文件到网盘根目录`)
            await refresh()
          }}
          onToast={toast}
        />
      )}
    </>
  )
}

function SharePreview({
  link,
  onClose,
  onSaved,
  onToast,
}: {
  link: ResourceLink
  onClose: () => void
  onSaved: (count: number) => void
  onToast: (msg: string, kind?: 'info' | 'error') => void
}) {
  const { snap } = useApp()
  const toast = useToast()
  const [stack, setStack] = useState<{ fid: string; name: string }[]>([{ fid: '0', name: '分享根目录' }])
  const [items, setItems] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const current = stack[stack.length - 1]

  const load = useCallback(
    async (fid: string) => {
      setLoading(true)
      setError('')
      try {
        const res = await api.sharePreview(link.url, link.password, fid)
        setItems(res.list || [])
      } catch (e: any) {
        setError(e.message || '读取分享失败')
      } finally {
        setLoading(false)
      }
    },
    [link.url, link.password]
  )

  useEffect(() => {
    load(current.fid)
  }, [current.fid, load])

  const doSave = async () => {
    if (!snap.settings.quarkCookie) {
      toast('请先到网盘页扫码登录', 'error')
      return
    }
    setSaving(true)
    try {
      const res = await api.saveShare(link.url, link.password)
      onSaved(res.count)
      onClose()
    } catch (e: any) {
      toast(e.message || '转存失败', 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Sheet
      title={link.note}
      subtitle={`${panLabel(link.type)} · ${sourceLabel(link)}${link.password ? ` · 提取码 ${link.password}` : ''}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={() => api.openExternal(link.url)}>
            <Icon name="external" size={15} /> 浏览器打开
          </button>
          <button
            className="btn"
            onClick={async () => {
              await api.copy(link.password ? `${link.url} 提取码：${link.password}` : link.url)
              toast('链接已复制')
            }}
          >
            <Icon name="copy" size={15} /> 复制链接
          </button>
          <span style={{ flex: 1 }} />
          <button className="btn primary" onClick={doSave} disabled={saving}>
            {saving ? <span className="spinner" style={{ width: 14, height: 14 }} /> : <Icon name="download" size={15} />}
            保存整个分享
          </button>
        </>
      }
    >
      <div className="crumbs" style={{ marginBottom: 10 }}>
        {stack.map((c, i) => (
          <span key={c.fid + i} style={{ display: 'inline-flex', alignItems: 'center' }}>
            {i > 0 && <span className="sep">/</span>}
            <button onClick={() => setStack(stack.slice(0, i + 1))}>{c.name}</button>
          </span>
        ))}
      </div>
      {loading ? (
        <SpinnerBox text="正在读取分享目录…" />
      ) : error ? (
        <Empty icon="cloud" title="读取失败" desc={error}>
          <button className="btn" onClick={() => load(current.fid)}>
            重试
          </button>
        </Empty>
      ) : !items.length ? (
        <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink-3)', fontSize: 12.5 }}>
          这个目录是空的，或者分享已失效
        </div>
      ) : (
        <div className="share-tree">
          {items.map((it) => (
            <div
              key={it.fid}
              className="node"
              onClick={() => {
                if (it.isDir) setStack([...stack, { fid: it.fid, name: it.name }])
              }}
            >
              <Icon name={it.isDir ? 'folder' : 'film'} size={16} />
              <span className="n" title={it.name}>
                {it.name}
              </span>
              <span style={{ color: 'var(--ink-3)', fontSize: 11 }}>
                {it.isDir ? '文件夹' : `${Math.round((it.size || 0) / 1048576)} MB`}
              </span>
              {it.isDir ? <Icon name="right" size={14} /> : null}
            </div>
          ))}
        </div>
      )}
      <div style={{ fontSize: 11.5, color: 'var(--ink-3)', lineHeight: 1.8 }}>
        预览不会占用网盘空间；「保存整个分享」会把分享根目录的文件与文件夹全部转存到你的夸克网盘根目录，转存完成后再到「网盘」页播放即可。
      </div>
    </Sheet>
  )
}
