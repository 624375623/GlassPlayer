import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Hls from 'hls.js'

import { api } from '../lib/api'
import type { DanmakuItem, DanmakuMatch, PlayTarget, Settings, VideoPref } from '../lib/types'
import { formatDuration } from '../lib/format'
import { Icon } from '../components/Icon'
import { useToast } from '../components/ui'
import { DanmakuEngine, type DanmakuConfig } from './Danmaku'
import { activeCue, parseSubtitles, type Cue } from '../lib/subtitles'

type Props = {
  target: PlayTarget
  queue: PlayTarget[]
  settings: Settings
  onPatch: (patch: Partial<Settings>) => void
  onExit: () => void
  onSaveMeta: (meta: any) => void
  onPlayTarget: (t: PlayTarget) => void
}

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3]

/** 片名相关度：完全包含记 1，否则按字符命中率，用来挡掉搜索接口的无关兜底结果。 */
function relevance(title: string | undefined, keyword: string): number {
  const a = String(title || '').replace(/\s/g, '')
  const b = String(keyword || '').replace(/[\s《》()（）·:：]/g, '')
  if (!a || !b) return 0
  if (a.includes(b)) return 1
  let hit = 0
  for (const ch of b) if (a.includes(ch)) hit += 1
  return hit / b.length
}

function danmakuConfig(s: Settings): DanmakuConfig {
  return {
    opacity: s.danmakuOpacity,
    scale: s.danmakuScale,
    speed: s.danmakuSpeed,
    area: s.danmakuArea,
    showScroll: s.danmakuShowScroll,
    showTop: s.danmakuShowTop,
    showBottom: s.danmakuShowBottom,
    bold: s.danmakuFontBold,
    stroke: s.danmakuStroke,
    blockWords: s.danmakuBlockWords,
    blockRegex: s.danmakuBlockRegex,
    offsetMs: s.danmakuOffsetMs,
  }
}

/** 文件名里的季号：S04 / 第四季 / Season 4，认不出返回 null。 */
function seasonIndex(name: string): number | null {
  const text = name || ''
  const CN: Record<string, number> = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 }
  let m = /(?:^|[.\s_-])S(\d{1,2})(?:E\d{1,4})?(?![0-9])/i.exec(text)
  if (m) {
    const n = Number(m[1])
    if (n >= 1 && n <= 30) return n
  }
  m = /第\s*([0-9]{1,2}|[一二三四五六七八九十]{1,3})\s*季/.exec(text)
  if (m) {
    const raw = m[1]
    const n = /^\d+$/.test(raw) ? Number(raw) : raw === '十' ? 10 : CN[raw] || null
    if (n && n >= 1 && n <= 30) return n
  }
  m = /season\s*(\d{1,2})/i.exec(text)
  if (m) {
    const n = Number(m[1])
    if (n >= 1 && n <= 30) return n
  }
  return null
}

/** B 站风格滑条：细轨 + 蓝色进度 + 分段刻度点 + 白点，右侧显示当前值。 */
function BiliSlider({
  label,
  min,
  max,
  step,
  value,
  display,
  onChange,
}: {
  label: string
  min: number
  max: number
  step: number
  value: number
  display: string
  onChange: (v: number) => void
}) {
  const pct = Math.min(100, Math.max(0, ((value - min) / (max - min)) * 100))
  return (
    <div className="bsl">
      <span className="bsl-label">{label}</span>
      <div className="bsl-track">
        <i className="bsl-rail" />
        <i className="bsl-fill" style={{ width: `${pct}%` }} />
        {[25, 50, 75].map((p) => (
          <i key={p} className="bsl-tick" style={{ left: `${p}%` }} />
        ))}
        <i className="bsl-knob" style={{ left: `${pct}%` }} />
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
        />
      </div>
      <span className="bsl-val">{display}</span>
    </div>
  )
}

/** 弹幕速度用文字表达，和 B 站一致。 */
function speedWord(v: number): string {
  if (v <= 0.7) return '很慢'
  if (v <= 0.92) return '慢'
  if (v < 1.08) return '适中'
  if (v < 1.4) return '快'
  return '很快'
}

/** 候选条目上标注相似度，低分提示用户别乱点（典型：正片旁边混着预告片、剧场版）。 */
function matchLabel(m: DanmakuMatch): string {
  const base = m.subtitle || ''
  if (typeof m.score === 'number' && m.score < 60) {
    return `${base}${base ? ' · ' : ''}疑似（相似度 ${m.score}）`
  }
  return base
}

function episodeIndex(name: string): number | null {
  const text = name || ''
  const patterns = [
    /S(\d{1,2})E(\d{1,3})/i,
    /EP?\.?\s*(\d{1,3})/i,
    /第\s*(\d{1,3})\s*[集话話期]/,
    /(?:^|[\s._-])(\d{1,3})(?:[\s._-]|$)/,
  ]
  for (const re of patterns) {
    const m = re.exec(text)
    if (m) {
      const n = Number(m[m.length - 1])
      if (n > 0 && n < 5000) return n
    }
  }
  return null
}

/** 关键词里除了集号 / 标点还有没有真东西（「181-4K.高码率」清洗完只剩数字，不算）。 */
function meaningfulName(s: string): boolean {
  return String(s || '').replace(/[\d\s.第集话話期\-–—_（）()]/g, '').length >= 2
}

/**
 * 目录内视频按集数自然排序：有集号按集号，没集号按名称（中文 + 数字自然序）。
 * 网盘返回的顺序常常是按上传时间，直接拿来当「下一集」会乱跳。
 */
const naturalCollator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
function sortByEpisode(list: PlayTarget[]): PlayTarget[] {
  return [...list].sort((a, b) => {
    const na = episodeIndex(a.title)
    const nb = episodeIndex(b.title)
    if (na != null && nb != null && na !== nb) return na - nb
    if (na != null && nb == null) return -1
    if (na == null && nb != null) return 1
    return naturalCollator.compare(a.title, b.title)
  })
}

export function Player({ target, queue, settings, onPatch, onExit, onSaveMeta, onPlayTarget }: Props) {
  const toast = useToast()
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const subRef = useRef<HTMLDivElement | null>(null)
  const engineRef = useRef<DanmakuEngine | null>(null)
  const cuesRef = useRef<Cue[]>([])
  const hlsRef = useRef<Hls | null>(null)
  const hideTimer = useRef<number | null>(null)
  const clickTimer = useRef<number | null>(null)
  /** 已绑定的弹幕季（跨集复用，换集时直接按集号取，不再重新搜索） */
  const dmSeasonRef = useRef<{ match: DanmakuMatch; eps: DanmakuMatch[] } | null>(null)
  const panelIdle = useRef<number | null>(null)
  const panelRef = useRef<'none' | 'speed' | 'danmaku' | 'subtitle' | 'episodes' | 'more' | 'dmset'>('none')

  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState('')
  const [warning, setWarning] = useState<string | null>(null)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [volume, setVolume] = useState(settings.volume)
  const [muted, setMuted] = useState(settings.muted)
  const [speed, setSpeed] = useState(settings.defaultSpeed || 1)
  const [hideUI, setHideUI] = useState(false)
  const [centerTip, setCenterTip] = useState<string | null>(null)
  const [panel, setPanel] = useState<'none' | 'speed' | 'danmaku' | 'subtitle' | 'episodes' | 'more' | 'dmset'>('none')
  const [dmQuery, setDmQuery] = useState('')
  const [dmBusy, setDmBusy] = useState(false)
  const [dmMatches, setDmMatches] = useState<DanmakuMatch[]>([])
  const [dmEpisodes, setDmEpisodes] = useState<DanmakuMatch[]>([])
  const [dmLoaded, setDmLoaded] = useState<{ title: string; count: number; id: string } | null>(null)
  const [subtitleName, setSubtitleName] = useState<string | null>(null)
  const [resumeAt, setResumeAt] = useState<number | null>(null)
  const [hoverTime, setHoverTime] = useState<{ x: number; t: number } | null>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)

  const reportKey = target.key
  // 目录列表在播放器里重新按集数排过，上一集 / 下一集也照这个顺序走
  const orderedQueue = useMemo(() => sortByEpisode(queue), [queue])
  const queueIndex = orderedQueue.findIndex((q) => q.key === target.key)
  const prevTarget = queueIndex > 0 ? orderedQueue[queueIndex - 1] : null
  const nextTarget = queueIndex >= 0 && queueIndex < orderedQueue.length - 1 ? orderedQueue[queueIndex + 1] : null

  const engineCfg = useMemo(() => danmakuConfig(settings), [settings])
  const settingsRef = useRef(settings)
  settingsRef.current = settings
  const orderedQueueRef = useRef<PlayTarget[]>(orderedQueue)
  orderedQueueRef.current = orderedQueue

  const flashTip = useCallback((text: string) => {
    setCenterTip(text)
    window.setTimeout(() => setCenterTip((cur) => (cur === text ? null : cur)), 700)
  }, [])

  /* ---------------- 解析播放地址 ---------------- */
  const [source, setSource] = useState<{ url: string; kind: 'hls' | 'file' } | null>(null)
  const [resolveTick, setResolveTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setError('')
    setWarning(null)
    setSource(null)
    ;(async () => {
      try {
        if (target.kind === 'local') {
          if (!target.path) throw new Error('文件路径为空')
          const url = await api.mediaLocal(target.path)
          if (!cancelled) setSource({ url, kind: 'file' })
          return
        }
        const res = await api.quarkPlay(target.fid!, target.meta, settings.quarkPlayMode)
        if (cancelled) return
        setSource({ url: res.url, kind: res.kind })
        if (res.warning) setWarning(res.warning)
        if (res.mode && res.mode !== settings.quarkPlayMode) {
          // 转码不可用，自动回退成原画直链
        }
      } catch (e: any) {
        if (!cancelled) {
          setStatus('error')
          setError(e.message || '播放失败')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [target.key, resolveTick])

  /* ---------------- 每部视频自己的设置（倍速 / 字幕 / 弹幕绑定） ---------------- */
  const [pref, setPref] = useState<VideoPref | null>(null)
  const [prefLoaded, setPrefLoaded] = useState(false)
  const prefRef = useRef<VideoPref | null>(null)

  useEffect(() => {
    let cancelled = false
    prefRef.current = null
    setPref(null)
    setPrefLoaded(false)
    api
      .videoPref(target.key)
      .then((p) => {
        if (cancelled) return
        prefRef.current = p
        setPref(p)
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setPrefLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [target.key])

  /* ---------------- 挂载视频 ---------------- */
  useEffect(() => {
    const video = videoRef.current
    if (!video || !source) return
    let disposed = false
    const cleanupFns: (() => void)[] = []

    const syncCanvas = () => {
      const c = canvasRef.current
      const wrap = wrapRef.current
      if (!c || !wrap) return
      const vw = video.videoWidth
      const vh = video.videoHeight
      const W = wrap.clientWidth
      const H = wrap.clientHeight
      if (!vw || !vh) {
        c.style.left = '0px'
        c.style.top = '0px'
        c.style.width = `${W}px`
        c.style.height = `${H}px`
      } else {
        const scale = Math.min(W / vw, H / vh)
        c.style.left = `${(W - vw * scale) / 2}px`
        c.style.top = `${(H - vh * scale) / 2}px`
        c.style.width = `${vw * scale}px`
        c.style.height = `${vh * scale}px`
      }
      engineRef.current?.resize()
    }
    const onLoadedMeta = () => {
      if (disposed) return
      syncCanvas()
      setStatus('ready')
      if (Number.isFinite(video.duration)) setDuration(video.duration * 1000)
      video.playbackRate = prefRef.current?.speed || speed
      if (prefRef.current?.speed) setSpeed(prefRef.current.speed)
      const saved = target.key
      const pos = lastPosRef.current
      if (settings.resume && pos && pos.duration && pos.pos > 8 && pos.pos < pos.duration - 8) {
        video.currentTime = pos.pos / 1000
        setResumeAt(pos.pos)
        window.setTimeout(() => setResumeAt(null), 4200)
      }
      video.play().catch(() => {})
    }
    const onTime = () => setTime(video.currentTime * 1000)
    const onDur = () => {
      if (Number.isFinite(video.duration)) setDuration(video.duration * 1000)
    }
    const onProg = () => {
      try {
        if (video.buffered.length) setBuffered(video.buffered.end(video.buffered.length - 1) * 1000)
      } catch (_) {}
    }
    const onPlay = () => setPlaying(true)
    const onEnded = () => {
      setPlaying(false)
      // 连播按集数顺序，不是网盘返回顺序
      const list = orderedQueueRef.current
      const idx = list.findIndex((q) => q.key === target.key)
      if (idx >= 0 && idx < list.length - 1) {
        onPlayTarget(list[idx + 1])
      }
    }
    const onPause = () => setPlaying(false)
    const onError = () => {
      if (disposed) return
      const code = video.error?.code
      setStatus('error')
      setError(
        code === 4
          ? '这个文件无法解码（可能是 HEVC / 杜比视界）。可以在设置里切换「原画直链 / 转码流播」再试，或换一个版本。'
          : `播放器错误（code ${code ?? '未知'}），可以点重试或切换播放模式。`
      )
    }

    video.addEventListener('loadedmetadata', onLoadedMeta)
    video.addEventListener('resize', syncCanvas)
    window.addEventListener('resize', syncCanvas)
    document.addEventListener('fullscreenchange', syncCanvas)
    video.addEventListener('timeupdate', onTime)
    video.addEventListener('durationchange', onDur)
    video.addEventListener('progress', onProg)
    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('error', onError)
    video.addEventListener('ended', onEnded)

    if (source.kind === 'hls') {
      if (Hls.isSupported()) {
        const hls = new Hls({
          enableWorker: true,
          lowLatencyMode: false,
          backBufferLength: 60,
          // 网盘转码流的码率不低，缓冲给足才不会播两秒停一下
          maxBufferLength: 90,
          maxMaxBufferLength: 180,
          maxBufferSize: 120 * 1000 * 1000,
          maxBufferHole: 0.5,
          manifestLoadingTimeOut: 20000,
          manifestLoadingMaxRetry: 3,
          levelLoadingMaxRetry: 6,
          fragLoadingMaxRetry: 6,
          fragLoadingTimeOut: 30000,
          abrEwmaDefaultEstimate: 3000000,
        })
        hlsRef.current = hls
        hls.loadSource(source.url)
        hls.attachMedia(video)
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (!data.fatal) return
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            hls.startLoad()
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            hls.recoverMediaError()
          } else {
            setStatus('error')
            setError(`HLS 播放失败：${data.details || ''}`)
          }
        })
        cleanupFns.push(() => {
          hls.destroy()
          hlsRef.current = null
        })
      } else {
        video.src = source.url
      }
    } else {
      video.src = source.url
    }

    return () => {
      disposed = true
      video.removeEventListener('loadedmetadata', onLoadedMeta)
      video.removeEventListener('resize', syncCanvas)
      window.removeEventListener('resize', syncCanvas)
      document.removeEventListener('fullscreenchange', syncCanvas)
      video.removeEventListener('timeupdate', onTime)
      video.removeEventListener('durationchange', onDur)
      video.removeEventListener('progress', onProg)
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('error', onError)
      video.removeEventListener('ended', onEnded)
      cleanupFns.forEach((fn) => fn())
      video.removeAttribute('src')
      video.load()
    }
  }, [source, resolveTick])

  /* ---------------- 进度记忆 ---------------- */
  const lastPosRef = useRef<{ pos: number; duration: number } | null>(null)
  useEffect(() => {
    lastPosRef.current = null
    api
      .snapshot()
      .then((snap) => {
        const p = snap.positions[target.key]
        if (p) lastPosRef.current = { pos: p.pos, duration: p.duration }
      })
      .catch(() => {})
  }, [target.key])

  useEffect(() => {
    const save = () => {
      const video = videoRef.current
      if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return
      const pos = video.currentTime * 1000
      if (pos < 3000) return
      const done = video.duration - video.currentTime < 12
      api.savePosition(target.key, done ? 0 : pos, video.duration * 1000).catch(() => {})
      const meta = { ...(target.meta || {}), durationMs: Math.round(video.duration * 1000) }
      if (target.kind === 'cloud' && target.fid) {
        api.saveCloudMeta({ ...meta, fid: target.fid, name: target.meta?.name || target.title }).catch(() => {})
        onSaveMeta({ ...meta, fid: target.fid, name: target.meta?.name || target.title })
      }
    }
    const timer = window.setInterval(save, 5000)
    const video = videoRef.current
    const onPauseSave = () => save()
    video?.addEventListener('pause', onPauseSave)
    window.addEventListener('beforeunload', save)
    return () => {
      window.clearInterval(timer)
      video?.removeEventListener('pause', onPauseSave)
      window.removeEventListener('beforeunload', save)
      save()
    }
  }, [target.key, reportKey])

  /* ---------------- 弹幕引擎 ---------------- */
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const engine = new DanmakuEngine(canvas, danmakuConfig(settings))
    engine.enabled = settings.danmakuEnabled
    engineRef.current = engine
    ;(window as any).__dm = engine
    const onResize = () => engine.resize()
    window.addEventListener('resize', onResize)
    let raf = 0
    const loop = () => {
      const video = videoRef.current
      if (video) {
        engine.tick(video.currentTime * 1000, video.paused)
        const cue = activeCue(cuesRef.current, video.currentTime * 1000 + settingsRef.current.subtitleDelayMs)
        const el = subRef.current
        if (el) {
          el.textContent = cue ? cue.text : ''
          el.style.opacity = cue ? '1' : '0'
        }
      }
      raf = requestAnimationFrame(loop)
    }
    raf = requestAnimationFrame(loop)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      engineRef.current = null
    }
  }, [target.key])

  useEffect(() => {
    if (engineRef.current) {
      engineRef.current.setConfig(engineCfg)
      engineRef.current.enabled = settings.danmakuEnabled
    }
    const sub = subRef.current
    if (sub) {
      sub.style.fontSize = `${Math.round(22 * settings.subtitleScale)}px`
      sub.style.color = settings.subtitleColor
    }
  }, [settings])

  // 换片时清掉上一集的弹幕与匹配结果，避免串集（已绑定的季保留，用于跟集）
  useEffect(() => {
    setDmLoaded(null)
    setDmMatches([])
    setDmEpisodes([])
    engineRef.current?.setItems([])
  }, [target.key])

  // 换了弹幕源，之前绑定的季就作废
  useEffect(() => {
    dmSeasonRef.current = null
  }, [settings.danmakuSource, settings.danmakuServerUrl])

  /* ---------------- 弹幕自动匹配 ---------------- */
  const loadDanmaku = useCallback(
    async (match: DanmakuMatch, silent = false) => {
      setDmBusy(true)
      try {
        const source = match.source || settings.danmakuSource
        const res = await api.danmakuLoad(source, match.id, settings.danmakuServerUrl, settings.danmakuLimit)
        const items: DanmakuItem[] = res.items || []
        engineRef.current?.setItems(items)
        setDmLoaded({ title: `${match.title}${match.subtitle ? ' · ' + match.subtitle : ''}`, count: items.length, id: match.id })
        // 记住这部片用的哪一集弹幕，下次打开直接还原
        api
          .saveVideoPref(target.key, {
            danmaku: { source, id: match.id, title: match.title, subtitle: match.subtitle },
          })
          .catch(() => {})
        if (!silent) {
          toast(`已加载 ${items.length} 条弹幕`)
          setPanel('none')
        }
      } catch (e: any) {
        if (!silent) toast(e.message || '弹幕加载失败', 'error')
      } finally {
        setDmBusy(false)
      }
    },
    [settings.danmakuSource, settings.danmakuServerUrl, settings.danmakuLimit, target.key, toast]
  )

  /**
   * 拿这部片该搜什么名字。
   * 网盘里常见「181-4K.高码率」这种只有集号的文件名，清洗完就剩个数字，
   * 拿它去搜会命中《181 号》之类的无关剧 —— 这时改用所在文件夹名当剧名。
   */
  const resolveKeyword = useCallback(async () => {
    let cleaned = await api.cleanTitle(target.title)
    if (!meaningfulName(cleaned)) {
      const dirRaw = String(target.subtitle || '').split(/[\\/]/).filter(Boolean).pop() || ''
      const dirName = dirRaw ? await api.cleanTitle(dirRaw).catch(() => '') : ''
      if (meaningfulName(dirName)) cleaned = dirName
    }
    return cleaned
  }, [target.title, target.subtitle])

  const autoDanmaku = useCallback(async () => {
    if (!settings.danmakuEnabled) return
    const search = (source: string, kw: string) =>
      api.danmakuSearch(source, kw, settings.danmakuServerUrl).catch(() => [] as DanmakuMatch[])
    try {
      let cleaned = await resolveKeyword()
      setDmQuery(cleaned)
      if (/^\d+$/.test(cleaned)) {
        // 连文件夹名都拿不到剧名，就别瞎配了
        toast('文件名里没有剧名，请在弹幕库里输入剧名搜索')
        return
      }
      const wanted = episodeIndex(target.title)
      const scoreOfTitle = (m: DanmakuMatch) =>
        typeof m.score === 'number' ? m.score : relevance(m.title, cleaned) * 100

      let matches = await search(settings.danmakuSource, cleaned)
      const related = matches.filter((m) => scoreOfTitle(m) >= 25)
      if (related.length) matches = related
      // 主源连一个像样的正片都没有（最高分不足 60）就换另一个源（B 站 ↔ 腾讯）
      if (scoreOfTitle(matches[0] || ({} as DanmakuMatch)) < 60 && (settings.danmakuSource === 'bili' || settings.danmakuSource === 'tencent')) {
        const alt = settings.danmakuSource === 'bili' ? 'tencent' : 'bili'
        const other = await search(alt, cleaned)
        const good = other.filter((m) => scoreOfTitle(m) >= 25)
        if (good.length) matches = good
      }
      setDmMatches(matches)
      if (!matches.length) return

      // 文件带季号时优先选同季（《鬼灭之刃》第一季和第四季同名，光看分数分不出来）
      const seasonWanted = seasonIndex(target.title)
      if (seasonWanted && seasonWanted > 1 && !matches.some((m) => m.season === seasonWanted)) {
        // 关键词带着「第四季」往往搜不到 B 站那种「柱训练篇」式命名，去掉季号补搜一轮
        const base = cleaned
          .replace(/(?:第\s*[0-9一二三四五六七八九十]{1,3}\s*季|[Ss]\d{1,2}(?![0-9])|season\s*\d{1,2})/gi, ' ')
          .replace(/\s{2,}/g, ' ')
          .trim()
        if (base && base !== cleaned) {
          const more = await search(settings.danmakuSource, base)
          const ids = new Set(matches.map((m) => m.id))
          const merged = [...matches]
          for (const m of more) {
            if (ids.has(m.id)) continue
            ids.add(m.id)
            merged.push(m)
          }
          merged.sort((a, b) => scoreOfTitle(b) - scoreOfTitle(a))
          matches = merged
          setDmMatches(merged)
        }
      }
      let best = matches[0]
      if (seasonWanted) {
        const sameSeason = matches.find((m) => m.season === seasonWanted)
        if (sameSeason) best = sameSeason
        else if (seasonWanted > 1) {
          // 宁可留空也不把第一季的弹幕套到第四季上
          toast(`没找到《${cleaned}》第 ${seasonWanted} 季的弹幕，请在弹幕库里手动选`)
          return
        }
      }
      if (scoreOfTitle(best) < 60) {
        // 只有「弹速狂飙」「舞台剧采访篇」这种擦边结果时宁可不配，也别把弹幕配到预告片
        toast(`没找到《${cleaned}》的正片弹幕，可在弹幕库里手动选择`)
        return
      }
      // B 站番剧与腾讯（cid|vid）都是「整季」，要先展开集数
      if (best.isSeason || String(best.id).includes('|')) {
        // 腾讯：video_ids 顺序即集号，按文件名集号直取，省掉整季元信息查询（200 集要十几秒）
        if (String(best.id).includes('|') && wanted) {
          const one = await api.danmakuTencentEpisode(best.id, wanted).catch(() => null)
          if (one) {
            dmSeasonRef.current = { match: best, eps: [] }
            await loadDanmaku(one, true)
            return
          }
        }
        const eps = await api.danmakuEpisodes(best.id, best.source)
        setDmEpisodes(eps)
        if (!eps.length) return
        // 绑定这一季，之后上一集 / 下一集直接按集号取，不再重新搜索
        dmSeasonRef.current = { match: best, eps }
        // 电影/单本剧只有一个「正片」，文件名里的集号是假集号（比如 S01E01）
        if (eps.length === 1) {
          await loadDanmaku(eps[0], true)
          return
        }
        if (!wanted) {
          await loadDanmaku(eps[0], true)
          toast(`已自动匹配弹幕：${best.title}（第 1 集）`)
          return
        }
        const targetEp = eps.find((e) => episodeIndex(e.subtitle || '') === wanted)
        if (targetEp) {
          await loadDanmaku(targetEp, true)
          return
        }
        // 集号对不上（分季、集数超范围）就别硬塞第 1 集，容易和画面完全错位
        toast(`${best.title} 共 ${eps.length} 集，没找到第 ${wanted} 集，请在弹幕库里手动选`)
      } else {
        await loadDanmaku(best, true)
      }
    } catch (_) {
      /* 自动匹配失败不打扰用户 */
    }
  }, [settings.danmakuEnabled, settings.danmakuSource, settings.danmakuServerUrl, target.title, resolveKeyword, loadDanmaku, toast])

  const autoDanmakuRef = useRef(autoDanmaku)
  autoDanmakuRef.current = autoDanmaku

  /** 已经绑定了某一季时，换集直接跟着切换弹幕；返回 false 表示需要重新搜索匹配。 */
  const followDanmaku = useCallback(async () => {
    const season = dmSeasonRef.current
    if (!season) return false
    const wanted = episodeIndex(target.title)
    if (!wanted) return false
    try {
      const cleaned = await resolveKeyword()
      // 连播跨到了别的剧，别把上一部剧的弹幕带过来
      if (relevance(season.match.title, cleaned) < 0.6) {
        dmSeasonRef.current = null
        return false
      }
      if (String(season.match.id).includes('|')) {
        const one = await api.danmakuTencentEpisode(season.match.id, wanted).catch(() => null)
        if (!one) return false
        await loadDanmaku(one, true)
        return true
      }
      const eps = season.eps.length ? season.eps : await api.danmakuEpisodes(season.match.id, season.match.source)
      season.eps = eps
      if (!eps.length) return false
      setDmEpisodes(eps)
      if (eps.length === 1) {
        await loadDanmaku(eps[0], true)
        return true
      }
      const hit = eps.find((e) => episodeIndex(e.subtitle || '') === wanted)
      if (!hit) return false
      await loadDanmaku(hit, true)
      return true
    } catch (_) {
      return false
    }
  }, [resolveKeyword, loadDanmaku])

  const followRef = useRef(followDanmaku)
  followRef.current = followDanmaku
  const loadDanmakuRef = useRef(loadDanmaku)
  loadDanmakuRef.current = loadDanmaku

  useEffect(() => {
    const t = window.setTimeout(async () => {
      // 1) 这部片上次用的弹幕：直接还原，连搜索都省了
      const saved = prefRef.current?.danmaku
      if (saved && saved.id) {
        await loadDanmakuRef.current(saved as DanmakuMatch, true)
        return
      }
      // 2) 同一季的下一集：直接跟着换
      const followed = await followRef.current()
      // 3) 换剧或换季才重新搜索
      if (!followed) await autoDanmakuRef.current()
    }, 800)
    return () => window.clearTimeout(t)
  }, [target.key])

  /* ---------------- 字幕 ---------------- */
  const loadSubtitleFile = useCallback(
    async (path: string) => {
      try {
        const text = await api.readText(path)
        const cues = parseSubtitles(text, path)
        cuesRef.current = cues
        setSubtitleName(path.split(/[\\/]/).pop() || path)
        // 记住这部片用哪份字幕，下次打开自动加载
        api.saveVideoPref(target.key, { subtitlePath: path }).catch(() => {})
        toast(`已载入字幕 ${cues.length} 条`)
      } catch (e: any) {
        toast(e.message || '字幕加载失败', 'error')
      }
    },
    [toast, target.key]
  )

  /** 字幕恢复：上次手动载入的优先，其次找本地同名文件 */
  useEffect(() => {
    if (!prefLoaded) return
    let cancelled = false
    ;(async () => {
      const saved = pref?.subtitlePath
      if (saved && (await api.exists(saved).catch(() => false))) {
        if (!cancelled) await loadSubtitleFile(saved)
        return
      }
      if (target.kind !== 'local' || !target.path) return
      const base = target.path.replace(/\.[^.\\/]+$/, '')
      for (const ext of ['.srt', '.ass', '.vtt', '.ssa']) {
        if (await api.exists(base + ext).catch(() => false)) {
          if (!cancelled) await loadSubtitleFile(base + ext)
          return
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [target.key, prefLoaded, pref, loadSubtitleFile])

  /* ---------------- 控制 ---------------- */
  const video = () => videoRef.current

  const togglePlay = useCallback(() => {
    const v = video()
    if (!v) return
    if (v.paused) v.play().catch(() => {})
    else v.pause()
  }, [])

  const seekBy = useCallback(
    (delta: number) => {
      const v = video()
      if (!v) return
      v.currentTime = Math.min(Math.max(0, v.currentTime + delta), v.duration || 1e9)
      flashTip(`${delta > 0 ? '+' : ''}${Math.round(delta)} 秒`)
    },
    [flashTip]
  )

  const changeVolume = useCallback(
    (delta: number) => {
      const v = video()
      if (!v) return
      const next = Math.min(1, Math.max(0, v.volume + delta))
      v.volume = next
      v.muted = next === 0
      setVolume(next)
      setMuted(v.muted)
      flashTip(`音量 ${Math.round(next * 100)}%`)
      onPatch({ volume: next, muted: v.muted })
    },
    [flashTip, onPatch]
  )

  const applySpeed = useCallback(
    (s: number) => {
      const v = video()
      if (v) v.playbackRate = s
      setSpeed(s)
      onPatch({ defaultSpeed: s })
      api.saveVideoPref(target.key, { speed: s }).catch(() => {})
      flashTip(`${s} 倍速`)
      setPanel('none')
    },
    [flashTip, onPatch, target.key]
  )

  const toggleFullscreen = useCallback(async () => {
    const wrap = wrapRef.current
    if (!wrap) return
    if (document.fullscreenElement) {
      await document.exitFullscreen().catch(() => {})
    } else {
      await wrap.requestFullscreen().catch(() => {})
    }
  }, [])

  useEffect(() => {
    const onFs = () => setIsFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }, [])

  const exit = useCallback(() => {
    const v = video()
    if (v) {
      try {
        v.pause()
      } catch (_) {}
    }
    hlsRef.current?.destroy()
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    onExit()
  }, [onExit])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      const key = e.key.toLowerCase()
      if (key === ' ' || key === 'k') {
        e.preventDefault()
        togglePlay()
      } else if (key === 'arrowright') {
        e.preventDefault()
        seekBy(e.shiftKey ? 30 : 5)
      } else if (key === 'arrowleft') {
        e.preventDefault()
        seekBy(e.shiftKey ? -30 : -5)
      } else if (key === 'arrowup') {
        e.preventDefault()
        changeVolume(0.05)
      } else if (key === 'arrowdown') {
        e.preventDefault()
        changeVolume(-0.05)
      } else if (key === 'f') {
        toggleFullscreen()
      } else if (key === 'm') {
        const v = video()
        if (v) {
          v.muted = !v.muted
          setMuted(v.muted)
          flashTip(v.muted ? '静音' : '取消静音')
        }
      } else if (key === 'escape') {
        if (panel !== 'none') setPanel('none')
        else exit()
      } else if (key === 'd') {
        onPatch({ danmakuEnabled: !settings.danmakuEnabled })
      } else if (key === 'l') {
        setPanel(panel === 'episodes' ? 'none' : 'episodes')
      } else if (key === '[') {
        const idx = SPEEDS.indexOf(speed)
        applySpeed(SPEEDS[Math.max(0, idx - 1)] ?? 1)
      } else if (key === ']') {
        const idx = SPEEDS.indexOf(speed)
        applySpeed(SPEEDS[Math.min(SPEEDS.length - 1, idx + 1)] ?? 1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [togglePlay, seekBy, changeVolume, toggleFullscreen, exit, panel, settings.danmakuEnabled, speed, applySpeed, flashTip, onPatch])

  /**
   * 鼠标在窗口上下边缘（控制栏所在位置）活动时保持控制栏可见。
   * 否则用户往右上角摸按钮的半路上，3.2 秒计时器就到点把界面藏了，
   * 表现为「鼠标一动就进沉浸播放」、按钮也点不到。
   */
  const edgeRef = useRef(false)
  const hideUIRef = useRef(false)
  hideUIRef.current = hideUI

  const showUI = useCallback(() => {
    setHideUI(false)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      // 面板开着时不收上下栏，统一交给面板的 4 秒计时器处理
      if (panelRef.current !== 'none') return
      if (edgeRef.current) return
      if (!videoRef.current?.paused) setHideUI(true)
    }, 3200)
  }, [])

  /**
   * 面板打开时，鼠标 4 秒不动就自动收起并进入沉浸播放。
   * 正在输入关键词时不收（打字不产生 mousemove，会打断搜索）。
   */
  const armPanelIdleRef = useRef<() => void>(() => {})
  const armPanelIdle = useCallback(() => {
    if (panelIdle.current) window.clearTimeout(panelIdle.current)
    panelIdle.current = window.setTimeout(() => {
      panelIdle.current = null
      const ae = document.activeElement
      if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
        armPanelIdleRef.current()
        return
      }
      setPanel('none')
      if (!videoRef.current?.paused) setHideUI(true)
    }, 4000)
  }, [])
  armPanelIdleRef.current = armPanelIdle

  panelRef.current = panel

  useEffect(() => {
    if (panel === 'none') {
      if (panelIdle.current) {
        window.clearTimeout(panelIdle.current)
        panelIdle.current = null
      }
      return
    }
    // 打开面板时把上下栏亮出来，并开始 4 秒无操作倒计时
    setHideUI(false)
    showUI()
    armPanelIdle()
    return () => {
      if (panelIdle.current) {
        window.clearTimeout(panelIdle.current)
        panelIdle.current = null
      }
    }
  }, [panel, armPanelIdle, showUI])

  useEffect(() => {
    showUI()
    return () => {
      if (hideTimer.current) window.clearTimeout(hideTimer.current)
    }
  }, [showUI])

  // 开始播放后重新起计时：否则挂载时视频还没播，3.2 秒的判断条件不成立，控制栏会一直挂着
  useEffect(() => {
    if (playing) showUI()
  }, [playing, showUI])

  const onProgressClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const v = video()
    const rect = e.currentTarget.getBoundingClientRect()
    if (!v || !Number.isFinite(v.duration)) return
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
    v.currentTime = ratio * v.duration
    setTime(v.currentTime * 1000)
  }

  const openDanmakuPanel = async () => {
    setPanel('danmaku')
    if (dmMatches.length) return
    if (!dmQuery) {
      setDmQuery(await resolveKeyword())
    }
  }

  const searchDanmaku = async () => {
    const kw = dmQuery.trim() || (await api.cleanTitle(target.title))
    setDmBusy(true)
    setDmEpisodes([])
    try {
      const matches = await api.danmakuSearch(settings.danmakuSource, kw, settings.danmakuServerUrl)
      setDmMatches(matches)
      if (!matches.length) toast('没有找到匹配的弹幕，换个关键词试试')
      const season = matches.find((m) => m.isSeason)
      if (season) setDmEpisodes(await api.danmakuEpisodes(season.id, season.source))
    } catch (e: any) {
      toast(e.message || '弹幕搜索失败', 'error')
    } finally {
      setDmBusy(false)
    }
  }

  const pickSubtitle = async () => {
    const files = await api.pickSubtitle()
    if (files.length) await loadSubtitleFile(files[0])
    setPanel('none')
  }

  const progressPct = duration ? Math.min(100, (time / duration) * 100) : 0
  const bufPct = duration ? Math.min(100, (buffered / duration) * 100) : 0

  return (
    <div
      ref={wrapRef}
      className={`player ${hideUI && panel === 'none' ? 'hide-ui' : ''} ${hideUI ? 'idle' : ''}`}
      onMouseMove={(e) => {
        // 顶部 / 底部 110px 内视为「正在够控制栏」，不再自动隐藏
        edgeRef.current = e.clientY < 110 || e.clientY > window.innerHeight - 120
        showUI()
        // 面板开着时，鼠标一动就重新计时
        if (panelRef.current !== 'none') armPanelIdleRef.current()
      }}
      onClickCapture={(e) => {
        // 控制栏藏起来时，第一次点在顶栏区域只负责把控制栏叫出来，别误触视频暂停
        if (hideUIRef.current && e.clientY < 90) {
          e.stopPropagation()
          e.preventDefault()
          showUI()
        }
      }}
    >
      <video
        ref={videoRef}
        onClick={() => {
          if (clickTimer.current) {
            window.clearTimeout(clickTimer.current)
            clickTimer.current = null
            toggleFullscreen()
            return
          }
          clickTimer.current = window.setTimeout(() => {
            clickTimer.current = null
            togglePlay()
          }, 200)
        }}
        onDoubleClick={() => {
          if (clickTimer.current) {
            window.clearTimeout(clickTimer.current)
            clickTimer.current = null
          }
          toggleFullscreen()
        }}
        playsInline
      />
      <canvas ref={canvasRef} className="dmlayer" />
      <div
        ref={subRef}
        className="subs"
        style={{
          fontSize: 22 * settings.subtitleScale,
          color: settings.subtitleColor,
          bottom: hideUI ? 48 : 96,
          opacity: 0,
        }}
      />

      {status === 'loading' && (
        <div className="pcenter-toast">
          <span className="spinner" style={{ borderTopColor: '#fff' }} /> 正在解析播放地址…
        </div>
      )}

      {status === 'error' && (
        <div className="errbox">
          <h3>播放失败</h3>
          <p>{error}</p>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
            <button className="btn primary" onClick={() => setResolveTick((t) => t + 1)}>
              <Icon name="refresh" size={16} /> 重试
            </button>
            {target.kind === 'cloud' && (
              <button
                className="btn"
                style={{ background: 'rgba(255,255,255,0.12)', color: '#fff', borderColor: 'rgba(255,255,255,0.16)' }}
                onClick={async () => {
                  const nextMode = (settings.quarkPlayMode === 'stream' ? 'download' : 'stream') as 'stream' | 'download'
                  onPatch({ quarkPlayMode: nextMode })
                  setTimeout(() => setResolveTick((t) => t + 1), 60)
                }}
              >
                切换为{settings.quarkPlayMode === 'stream' ? '原画直链' : '转码流播'}
              </button>
            )}
          </div>
        </div>
      )}

      {status === 'ready' && !playing && panel === 'none' && (
        <button className="bigplay" onClick={togglePlay} style={{ opacity: hideUI ? 0 : 1 }}>
          <Icon name="play" size={30} />
        </button>
      )}

      {centerTip && <div className="pcenter-toast">{centerTip}</div>}
      {resumeAt && <div className="pcenter-toast">已从 {formatDuration(resumeAt)} 继续播放</div>}
      {warning && status === 'ready' && (
        <div
          style={{
            position: 'absolute',
            top: 64,
            left: '50%',
            transform: 'translateX(-50%)',
            zIndex: 5,
            padding: '6px 12px',
            borderRadius: 10,
            background: 'rgba(10,14,26,0.72)',
            color: 'rgba(255,255,255,0.8)',
            fontSize: 11.5,
          }}
        >
          {warning}
        </div>
      )}

      <div className="topbar">
        <button className="pbtn" onClick={exit} title="返回 (Esc)">
          <Icon name="left" />
        </button>
        <div className="ttl">
          <b>{target.title}</b>
          <span>
            {dmLoaded ? `弹幕：${dmLoaded.title} · ${dmLoaded.count} 条` : target.subtitle || (target.kind === 'cloud' ? '夸克网盘' : '本地文件')}
            {subtitleName ? ` · 字幕：${subtitleName}` : ''}
          </span>
        </div>
        <button className="pbtn" onClick={() => setPanel(panel === 'episodes' ? 'none' : 'episodes')} title="目录选集 (L)">
          <Icon name="list" />
        </button>
        <button className="pbtn" onClick={openDanmakuPanel} title="弹幕库">
          <Icon name="compass" />
        </button>
        <button className="pbtn" onClick={() => setPanel(panel === 'subtitle' ? 'none' : 'subtitle')} title="字幕">
          <Icon name="subtitles" />
        </button>
        <button className="pbtn" onClick={() => setPanel(panel === 'more' ? 'none' : 'more')} title="更多">
          <Icon name="settings" />
        </button>
        <button className="pclose" onClick={exit} title="关闭播放器 (Esc)">
          <Icon name="close" size={14} />
          <span>关闭</span>
        </button>
      </div>

      <div className="bottombar">
        <div
          className="progressbar"
          onClick={onProgressClick}
          onMouseMove={(e) => {
            const rect = e.currentTarget.getBoundingClientRect()
            const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
            setHoverTime({ x: e.clientX - rect.left, t: ratio * duration })
          }}
          onMouseLeave={() => setHoverTime(null)}
        >
          <div className="track">
            <div className="buf" style={{ width: `${bufPct}%` }} />
            <div className="played" style={{ width: `${progressPct}%` }} />
          </div>
          <div className="knob" style={{ left: `${progressPct}%` }} />
          {hoverTime && <div className="tip" style={{ left: hoverTime.x }}>{formatDuration(hoverTime.t)}</div>}
        </div>
        <div className="pcontrols">
          <button className="pbtn" onClick={togglePlay} title="播放 / 暂停 (空格)">
            <Icon name={playing ? 'pause' : 'play'} size={20} />
          </button>
          {prevTarget && (
            <button className="pbtn" onClick={() => onPlayTarget(prevTarget)} title="上一个">
              <Icon name="prev" size={18} />
            </button>
          )}
          {nextTarget && (
            <button className="pbtn" onClick={() => onPlayTarget(nextTarget)} title="下一个">
              <Icon name="next" size={18} />
            </button>
          )}
          <div className="time">
            {formatDuration(time)} / {formatDuration(duration)}
          </div>
          <div className="grow" />
          <button
            className={`pbtn ${settings.danmakuEnabled ? 'on' : ''}`}
            onClick={() => onPatch({ danmakuEnabled: !settings.danmakuEnabled })}
            title={`弹幕开关 (D) · 当前${settings.danmakuEnabled ? '开' : '关'}`}
          >
            <Icon name="message" size={19} />
          </button>
          <button
            className="pbtn"
            onClick={() => setPanel(panel === 'dmset' ? 'none' : 'dmset')}
            title="弹幕设置"
          >
            <Icon name="sparkles" size={19} />
          </button>
          <span className="ctrl-sep" />
          <button className="pbtn" onClick={() => setPanel(panel === 'speed' ? 'none' : 'speed')} title="倍速">
            <Icon name="speed" size={19} />
          </button>
          <span style={{ fontSize: 11.5, color: 'rgba(255,255,255,0.7)', minWidth: 30 }}>{speed}x</span>
          <div className="vol-wrap">
            <button
              className="pbtn"
              onClick={() => {
                const v = video()
                if (!v) return
                v.muted = !v.muted
                setMuted(v.muted)
              }}
              title="静音 (M)"
            >
              <Icon name={muted || volume === 0 ? 'mute' : 'volume'} size={19} />
            </button>
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={muted ? 0 : volume}
              onChange={(e) => {
                const v = video()
                const next = Number(e.target.value)
                if (v) {
                  v.volume = next
                  v.muted = next === 0
                }
                setVolume(next)
                setMuted(next === 0)
                onPatch({ volume: next, muted: next === 0 })
              }}
            />
          </div>
          <span className="ctrl-sep" />
          <button
            className="pbtn"
            onClick={() => video()?.requestPictureInPicture?.().catch(() => toast('当前环境不支持画中画', 'error'))}
            title="画中画"
          >
            <Icon name="pip" size={19} />
          </button>
          <button className="pbtn" onClick={toggleFullscreen} title="全屏 (F)">
            <Icon name="maximize" size={19} />
          </button>
        </div>
      </div>

      {panel === 'speed' && (
        <div className="pmenu" style={{ right: 120 }}>
          <div className="mtitle">播放速度</div>
          {SPEEDS.map((s) => (
            <button key={s} className={speed === s ? 'on' : ''} onClick={() => applySpeed(s)}>
              {speed === s ? <Icon name="check" size={14} /> : <span style={{ width: 14 }} />}
              {s}x{s === 1 ? '（正常）' : ''}
            </button>
          ))}
        </div>
      )}

      {panel === 'more' && (
        <div className="pmenu" style={{ right: 18, bottom: 84, minWidth: 200 }}>
          <div className="mtitle">弹幕</div>
          <button onClick={() => setPanel('dmset')}>
            <Icon name="sparkles" size={15} /> 弹幕设置（透明度 / 字号 / 速度）
          </button>
          <button onClick={() => setPanel('danmaku')}>
            <Icon name="compass" size={15} /> 搜索 / 更换弹幕库
          </button>
          <div className="mtitle">字幕</div>
          <button onClick={pickSubtitle}>
            <Icon name="subtitles" size={15} /> 载入外部字幕…
          </button>
          <button
            onClick={() => {
              cuesRef.current = []
              setSubtitleName(null)
              api.saveVideoPref(target.key, { subtitlePath: '' }).catch(() => {})
              setPanel('none')
            }}
          >
            <Icon name="close" size={15} /> 关闭字幕
          </button>
          <div className="mtitle">其他</div>
          <button onClick={() => api.showItem(target.path || '').catch(() => {})}>
            <Icon name="folder" size={15} /> 在文件夹中显示
          </button>
          <button
            onClick={() => {
              if (target.kind === 'cloud' && target.fid) {
                api
                  .toggleFavorite(target.key)
                  .then((on) => toast(on ? '已收藏' : '已取消收藏'))
                  .catch(() => {})
              }
            }}
          >
            <Icon name="heart" size={15} /> 收藏 / 取消收藏
          </button>
        </div>
      )}

      {panel === 'episodes' && (
        <div className="ppanel" style={{ width: 340 }}>
          <div className="ph">
            <h3>目录选集</h3>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.45)' }}>共 {orderedQueue.length} 个</span>
            <button className="pbtn" onClick={() => setPanel('none')}>
              <Icon name="close" size={16} />
            </button>
          </div>
          <div className="pb">
            {orderedQueue.map((t, i) => {
              const current = t.key === target.key
              return (
                <button
                  key={t.key}
                  className={`pitem ${current ? 'on' : ''}`}
                  onClick={() => {
                    setPanel('none')
                    if (!current) onPlayTarget(t)
                  }}
                  title={t.title}
                >
                  <span className="epno">{episodeIndex(t.title) ?? i + 1}</span>
                  <span style={{ minWidth: 0 }}>
                    <div className="t">{t.title}</div>
                    <div className="s">{current ? '正在播放' : t.subtitle || ''}</div>
                  </span>
                </button>
              )
            })}
            {!orderedQueue.length && (
              <div style={{ padding: 20, textAlign: 'center', color: 'rgba(255,255,255,0.45)', fontSize: 12 }}>
                当前目录没有视频
              </div>
            )}
          </div>
        </div>
      )}

      {panel === 'dmset' && (
        <div className="ppanel dm-panel" style={{ width: 320 }}>
          <div className="ph">
            <h3>弹幕设置</h3>
            <button className="pbtn" onClick={() => setPanel('none')}>
              <Icon name="close" size={16} />
            </button>
          </div>
          <div className="pb">
            <BiliSlider
              label="显示区域"
              min={0.3}
              max={1}
              step={0.02}
              value={settings.danmakuArea}
              display={`${Math.round(settings.danmakuArea * 100)}%`}
              onChange={(v) => onPatch({ danmakuArea: v })}
            />
            <BiliSlider
              label="不透明度"
              min={0.1}
              max={1}
              step={0.01}
              value={settings.danmakuOpacity}
              display={`${Math.round(settings.danmakuOpacity * 100)}%`}
              onChange={(v) => onPatch({ danmakuOpacity: v })}
            />
            <BiliSlider
              label="弹幕字号"
              min={0.7}
              max={1.6}
              step={0.05}
              value={settings.danmakuScale}
              display={`${Math.round(settings.danmakuScale * 100)}%`}
              onChange={(v) => onPatch({ danmakuScale: v })}
            />
            <BiliSlider
              label="弹幕速度"
              min={0.5}
              max={2}
              step={0.05}
              value={settings.danmakuSpeed}
              display={speedWord(settings.danmakuSpeed)}
              onChange={(v) => onPatch({ danmakuSpeed: v })}
            />

            <div className="dm-sep" />
            <div className="dm-row">
              <span className="dm-row-label">显示类型</span>
              {([
                ['danmakuShowScroll', '滚动'],
                ['danmakuShowTop', '顶部'],
                ['danmakuShowBottom', '底部'],
              ] as const).map(([key, label]) => (
                <button
                  key={key}
                  className={`dm-chip ${settings[key] ? 'on' : ''}`}
                  onClick={() => onPatch({ [key]: !settings[key] } as any)}
                >
                  {label}
                </button>
              ))}
            </div>
            <BiliSlider
              label="时间偏移"
              min={-5000}
              max={5000}
              step={100}
              value={settings.danmakuOffsetMs}
              display={`${(settings.danmakuOffsetMs / 1000).toFixed(1)}s`}
              onChange={(v) => onPatch({ danmakuOffsetMs: v })}
            />
            <div className="dm-row">
              <span className="dm-row-label">弹幕开关</span>
              <button
                className={`dm-chip ${settings.danmakuEnabled ? 'on' : ''}`}
                onClick={() => onPatch({ danmakuEnabled: !settings.danmakuEnabled })}
              >
                {settings.danmakuEnabled ? '已开启' : '已关闭'}
              </button>
              <span style={{ flex: 1 }} />
            </div>
            <div className="dm-note">面板 4 秒不动自动收起；弹幕会跟着上一集 / 下一集切换。</div>
          </div>
        </div>
      )}

      {panel === 'danmaku' && (
        <div className="ppanel">
          <div className="ph">
            <h3>弹幕库</h3>
            <button className="pbtn" onClick={() => setPanel('none')}>
              <Icon name="close" size={16} />
            </button>
          </div>
          <div style={{ padding: '10px 16px 0', display: 'flex', gap: 8 }}>
            <div className="pfield" style={{ flex: 1 }}>
              <Icon name="search" size={15} />
              <input
                value={dmQuery}
                onChange={(e) => setDmQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && searchDanmaku()}
                placeholder="番剧 / 影视名称，或 B 站、v.qq.com 链接"
              />
            </div>
            <button className="btn" style={{ background: 'rgba(255,255,255,0.12)', color: '#fff', borderColor: 'transparent' }} onClick={searchDanmaku} disabled={dmBusy}>
              {dmBusy ? '搜索中…' : '搜索'}
            </button>
          </div>
          <div style={{ padding: '8px 16px 0', display: 'flex', gap: 6 }}>
            {([
              ['bili', 'B 站'],
              ['tencent', '腾讯'],
              ['server', '自建'],
            ] as const).map(([value, label]) => (
              <button
                key={value}
                className={`pbtn ${settings.danmakuSource === value ? 'on' : ''}`}
                style={{ width: 'auto', padding: '0 12px', fontSize: 12 }}
                onClick={() => {
                  if (settings.danmakuSource === value) return
                  onPatch({ danmakuSource: value })
                  setDmMatches([])
                  setDmEpisodes([])
                }}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="pb" style={{ marginTop: 8 }}>
            {dmEpisodes.length > 0 && (
              <>
                <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.45)', padding: '4px 8px' }}>选集</div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(74px,1fr))', gap: 6, marginBottom: 10 }}>
                  {dmEpisodes.map((ep) => (
                    <button
                      key={ep.id}
                      className={`pitem ${dmLoaded?.id === ep.id ? 'on' : ''}`}
                      style={{ padding: '7px 6px', justifyContent: 'center' }}
                      onClick={() => loadDanmaku(ep)}
                      title={ep.subtitle}
                    >
                      <span className="t" style={{ fontSize: 11.5 }}>
                        {(() => {
                          const hit = /第\s*([0-9]{1,4})\s*[集话期]/.exec(ep.subtitle || '')
                          if (hit) return `${hit[1].padStart(2, '0')} 集`
                          if (/正片|电影|单本/.test(ep.subtitle || '')) return '正片'
                          return (ep.subtitle || '').slice(0, 6) || '剧集'
                        })()}
                      </span>
                    </button>
                  ))}
                </div>
              </>
            )}
            {dmMatches
              .filter((m) => !m.isSeason)
              .map((m) => (
                <button key={m.id} className={`pitem ${dmLoaded?.id === m.id ? 'on' : ''}`} onClick={() => loadDanmaku(m)}>
                  <Icon name="message" size={16} />
                  <span style={{ minWidth: 0 }}>
                    <div className="t">{m.title}</div>
                    <div className="s">{matchLabel(m)}</div>
                  </span>
                </button>
              ))}
            {dmMatches
              .filter((m) => m.isSeason)
              .map((m) => (
                <button
                  key={m.id}
                  className={`pitem ${dmLoaded?.id === m.id ? 'on' : ''}`}
                  onClick={async () => {
                    setDmBusy(true)
                    try {
                      const eps = await api.danmakuEpisodes(m.id, m.source)
                      setDmEpisodes(eps)
                      if (!eps.length) {
                        toast('这部剧没有可用集数', 'error')
                        return
                      }
                      // 绑定这一季，之后上一集 / 下一集自动跟着换
                      dmSeasonRef.current = { match: m, eps }
                      const wanted = episodeIndex(target.title)
                      const hit = (wanted ? eps.find((e) => episodeIndex(e.subtitle || '') === wanted) : null) || eps[0]
                      await loadDanmaku(hit)
                    } catch (e: any) {
                      toast(e.message, 'error')
                    } finally {
                      setDmBusy(false)
                    }
                  }}
                >
                  <Icon name="film" size={16} />
                  <span style={{ minWidth: 0 }}>
                    <div className="t">{m.title}</div>
                    <div className="s">{matchLabel(m)}</div>
                  </span>
                </button>
              ))}
            {!dmMatches.length && !dmBusy && (
              <div style={{ padding: 20, textAlign: 'center', color: 'rgba(255,255,255,0.45)', fontSize: 12 }}>
                输入片名搜索正片弹幕（B 站 / 腾讯），也可以粘贴番剧或 v.qq.com 链接
              </div>
            )}
            {dmLoaded && (
              <div style={{ marginTop: 10, padding: '8px 10px', borderRadius: 10, background: 'rgba(255,255,255,0.06)', fontSize: 11.5, color: 'rgba(255,255,255,0.6)' }}>
                当前：{dmLoaded.title} · {dmLoaded.count} 条
              </div>
            )}
          </div>
          <div className="pf" style={{ gap: 6 }}>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)' }}>
              关键词：{dmQuery || '—'} · 来源：
              {settings.danmakuSource === 'bili' ? 'B 站' : settings.danmakuSource === 'tencent' ? '腾讯视频' : '自建服务器'}
            </span>
          </div>
        </div>
      )}

      {panel === 'subtitle' && (
        <div className="ppanel" style={{ width: 320 }}>
          <div className="ph">
            <h3>字幕</h3>
            <button className="pbtn" onClick={() => setPanel('none')}>
              <Icon name="close" size={16} />
            </button>
          </div>
          <div className="pb">
            <div className="pitem" onClick={pickSubtitle} style={{ cursor: 'pointer' }}>
              <Icon name="plus" size={16} />
              <span>
                <div className="t">载入字幕文件</div>
                <div className="s">支持 .srt / .ass / .vtt</div>
              </span>
            </div>
            {subtitleName && (
              <div style={{ fontSize: 11.5, color: 'rgba(255,255,255,0.55)', padding: '6px 10px' }}>
                当前：{subtitleName} · {cuesRef.current.length} 条
              </div>
            )}
            <div className="pslider">
              字号
              <input
                type="range"
                min={0.7}
                max={2}
                step={0.05}
                value={settings.subtitleScale}
                onChange={(e) => onPatch({ subtitleScale: Number(e.target.value) })}
              />
              <span>{settings.subtitleScale.toFixed(2)}x</span>
            </div>
            <div className="pslider">
              延迟
              <input
                type="range"
                min={-5000}
                max={5000}
                step={100}
                value={settings.subtitleDelayMs}
                onChange={(e) => onPatch({ subtitleDelayMs: Number(e.target.value) })}
              />
              <span>{(settings.subtitleDelayMs / 1000).toFixed(1)}s</span>
            </div>
            <div className="pslider">
              颜色
              <input
                type="color"
                value={settings.subtitleColor}
                onChange={(e) => onPatch({ subtitleColor: e.target.value })}
                style={{ width: 42, height: 26, border: 0, background: 'transparent' }}
              />
              <span style={{ flex: 1 }} />
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
