import type { DanmakuItem } from '../lib/types'

export type DanmakuConfig = {
  opacity: number
  scale: number
  speed: number
  area: number
  showScroll: boolean
  showTop: boolean
  showBottom: boolean
  bold: boolean
  stroke: boolean
  blockWords: string
  blockRegex: string
  offsetMs: number
}

type Active = {
  text: string
  size?: number
  color: number
  mode: 'scroll' | 'top' | 'bottom'
  born: number
  duration: number
  y: number
  width: number
  x: number
}

const BASE_DURATION = 9000

export class DanmakuEngine {
  private canvas: HTMLCanvasElement
  private ctx: CanvasRenderingContext2D
  private items: DanmakuItem[] = []
  private active: Active[] = []
  private cursor = 0
  private lastTime = 0
  private cfg: DanmakuConfig
  private dpr = 1
  private topLanes: number[] = []
  private bottomLanes: number[] = []
  private scrollLanes: number[] = []
  private fontFamily = '"Microsoft YaHei UI","PingFang SC",system-ui,sans-serif'
  enabled = true

  constructor(canvas: HTMLCanvasElement, cfg: DanmakuConfig) {
    this.canvas = canvas
    this.ctx = canvas.getContext('2d')!
    this.cfg = cfg
    this.resize()
  }

  setConfig(cfg: DanmakuConfig) {
    this.cfg = cfg
  }

  setItems(items: DanmakuItem[]) {
    const words = this.cfg.blockWords
      .split(/[,，、\s]+/)
      .map((w) => w.trim())
      .filter(Boolean)
    let regexes: RegExp[] = []
    try {
      regexes = this.cfg.blockRegex
        .split('\n')
        .map((r) => r.trim())
        .filter(Boolean)
        .map((r) => new RegExp(r, 'i'))
    } catch (_) {
      regexes = []
    }
    const filtered = items.filter((it) => {
      if (!it.text) return false
      if (it.mode === 'scroll' && !this.cfg.showScroll) return false
      if (it.mode === 'top' && !this.cfg.showTop) return false
      if (it.mode === 'bottom' && !this.cfg.showBottom) return false
      if (words.some((w) => it.text.includes(w))) return false
      if (regexes.some((r) => r.test(it.text))) return false
      return true
    })
    this.items = filtered.sort((a, b) => a.time - b.time)
    this.reset()
  }

  reset() {
    this.active = []
    this.cursor = 0
    this.lastTime = 0
    this.clearLanes()
  }

  private clearLanes() {
    this.topLanes = []
    this.bottomLanes = []
    this.scrollLanes = []
  }

  resize() {
    const rect = this.canvas.getBoundingClientRect()
    this.dpr = Math.min(window.devicePixelRatio || 1, 2)
    this.canvas.width = Math.max(1, Math.floor(rect.width * this.dpr))
    this.canvas.height = Math.max(1, Math.floor(rect.height * this.dpr))
    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0)
    this.active = []
    this.clearLanes()
  }

  private fontSize() {
    return Math.round(22 * this.cfg.scale)
  }

  private lineHeight() {
    return Math.round(this.fontSize() * 1.55)
  }

  private laneCount() {
    const h = this.canvas.height / this.dpr
    const usable = h * Math.min(1, Math.max(0.2, this.cfg.area))
    return Math.max(1, Math.floor(usable / this.lineHeight()))
  }

  private pickLane(kind: 'scroll' | 'top' | 'bottom', now: number, width: number, duration: number): number {
    const lanes = this.laneCount()
    const store = kind === 'scroll' ? this.scrollLanes : kind === 'top' ? this.topLanes : this.bottomLanes
    // 滚动弹幕：等这条弹幕的尾巴完全进入画面后再放下一条，避免同轨重叠
    const hold =
      kind === 'scroll' ? (width / this.pxPerSec(duration)) * 1000 + 320 : duration
    for (let i = 0; i < lanes; i += 1) {
      if (now >= (store[i] || 0)) {
        store[i] = now + hold
        return i
      }
    }
    let best = 0
    let bestFree = Infinity
    for (let i = 0; i < lanes; i += 1) {
      const t = store[i] || 0
      if (t < bestFree) {
        bestFree = t
        best = i
      }
    }
    store[best] = Math.max(bestFree, now) + hold
    return best
  }

  private pxPerSec(duration: number) {
    return (this.canvas.width / this.dpr + 200) / (duration / 1000)
  }

  tick(rawTime: number, paused = false) {
    const ctx = this.ctx
    const w = this.canvas.width / this.dpr
    const h = this.canvas.height / this.dpr
    ctx.clearRect(0, 0, w, h)
    if (!this.enabled) return

    const time = rawTime + (this.cfg.offsetMs || 0)
    if (time < this.lastTime - 400) {
      this.active = []
      this.clearLanes()
      this.cursor = this.seekIndex(time)
    }
    this.lastTime = time

    // 生成新弹幕
    const horizon = time + 60
    while (this.cursor < this.items.length && this.items[this.cursor].time <= horizon) {
      const item = this.items[this.cursor]
      this.cursor += 1
      if (item.time < time - 1200) continue
      this.spawn(item, time, w)
    }

    const size = this.fontSize()
    ctx.font = `${this.cfg.bold ? '700' : '500'} ${size}px ${this.fontFamily}`
    ctx.textBaseline = 'top'
    ctx.globalAlpha = this.cfg.opacity

    const alive: Active[] = []
    for (const a of this.active) {
      const elapsed = time - a.born
      if (elapsed < 0) {
        alive.push(a)
        continue
      }
      let x = a.x
      let y = a.y
      if (a.mode === 'scroll') {
        x = w - (elapsed / 1000) * this.pxPerSec(a.duration)
        if (x + a.width < 0) continue
      } else {
        if (elapsed > a.duration) continue
      }
      if (a.mode === 'bottom') y = h - (a.y + size) - 8
      const color = `#${a.color.toString(16).padStart(6, '0')}`
      if (this.cfg.stroke) {
        ctx.lineWidth = Math.max(2, size / 8)
        ctx.strokeStyle = 'rgba(0,0,0,0.72)'
        ctx.lineJoin = 'round'
        ctx.strokeText(a.text, x, y)
      }
      ctx.fillStyle = color
      ctx.fillText(a.text, x, y)
      alive.push({ ...a, x })
    }
    this.active = alive
    void paused
  }

  debug() {
    return {
      items: this.items.length,
      active: this.active.length,
      cursor: this.cursor,
      size: [this.canvas.width, this.canvas.height],
      sample: this.active.slice(0, 5).map((a) => ({ x: Math.round(a.x), y: Math.round(a.y), c: a.color, t: a.text })),
    }
  }

  private seekIndex(time: number) {
    let lo = 0
    let hi = this.items.length - 1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (this.items[mid].time < time) lo = mid + 1
      else hi = mid - 1
    }
    return lo
  }

  private spawn(item: DanmakuItem, now: number, width: number) {
    const ctx = this.ctx
    const size = Math.round(this.fontSize() * (item.scale && item.scale > 0.7 && item.scale < 2 ? item.scale : 1))
    ctx.font = `${this.cfg.bold ? '700' : '500'} ${size}px ${this.fontFamily}`
    const textWidth = ctx.measureText(item.text).width
    const lane = this.lineHeight()
    const duration = BASE_DURATION / Math.max(0.4, this.cfg.speed)

    if (item.mode === 'scroll') {
      const laneIdx = this.pickLane('scroll', now, textWidth, duration)
      this.active.push({
        text: item.text,
        color: item.color,
        mode: 'scroll',
        born: Math.max(now, item.time),
        duration,
        y: laneIdx * lane + 4,
        width: textWidth,
        x: width,
      })
      return
    }
    const idx = this.pickLane(item.mode, now, textWidth, 4200)
    this.active.push({
      text: item.text,
      color: item.color,
      mode: item.mode,
      born: Math.max(now, item.time),
      duration: 4200,
      y: item.mode === 'top' ? idx * lane + 4 : idx * lane + 4,
      width: textWidth,
      x: Math.max(0, (width - textWidth) / 2),
    })
  }
}
