export type QuarkFile = {
  fid: string
  name: string
  isDir: boolean
  size: number
  updatedAt: number
  parentFid?: string
  fileType?: string
}

export type ResourceLink = {
  type: string
  url: string
  password: string
  note: string
  datetime: string
  site: string
  siteLabel: string
  extra?: string
  valid?: 'valid' | 'invalid' | 'unknown'
}

export type SourceMeta = { id: string; label: string; host?: string }

export type CloudMeta = {
  fid: string
  name: string
  size: number
  updatedAt: number
  parentFid?: string
  parentName?: string
  durationMs?: number
  isDir?: boolean
}

export type PlayTarget = {
  key: string
  title: string
  subtitle?: string
  kind: 'local' | 'cloud'
  path?: string
  fid?: string
  meta?: Partial<CloudMeta>
}

export type Settings = {
  theme: 'light' | 'dark'
  defaultSpeed: number
  volume: number
  muted: boolean
  resume: boolean
  quarkCookie: string | null
  quarkUserName: string | null
  quarkVip: string | null
  quarkPlayMode: 'stream' | 'download'
  libraryFolders: string[]
  gridLayout: boolean
  sortMode: number
  sortAscending: boolean
  cloudGridLayout: boolean
  cloudSortMode: number
  cloudSortAscending: boolean
  danmakuEnabled: boolean
  danmakuOpacity: number
  danmakuScale: number
  danmakuSpeed: number
  danmakuArea: number
  danmakuShowScroll: boolean
  danmakuShowTop: boolean
  danmakuShowBottom: boolean
  danmakuBlockWords: string
  danmakuBlockRegex: string
  danmakuSource: 'bili' | 'tencent' | 'server'
  danmakuServerUrl: string
  danmakuOffsetMs: number
  /** 单集最多采集多少条弹幕，0 = 不限 */
  danmakuLimit: number
  danmakuLastQuery: string
  resourceLastQuery: string
  resourceSource: string
  subtitleScale: number
  subtitleDelayMs: number
  subtitleColor: string
  onlyVideoFolders: boolean
  danmakuFontBold: boolean
  danmakuStroke: boolean
}

export type Position = { pos: number; duration: number; at: number }

export type Snapshot = {
  settings: Settings
  positions: Record<string, Position>
  favorites: string[]
  cloudMeta: Record<string, CloudMeta>
  recent: string[]
  hiddenCloud: string[]
}

export type DanmakuItem = { time: number; text: string; mode: 'scroll' | 'top' | 'bottom'; color: number; scale?: number }
export type DanmakuMatch = {
  source: string
  id: string
  title: string
  subtitle?: string
  isSeason?: boolean
  /** 片名相似度 0~100，低于 60 不允许自动匹配（避免配到预告片/衍生剧） */
  score?: number
  /** 季号（第二季 = 2），用于优先选同季，避免第一季的弹幕配到第四季 */
  season?: number | null
}

/** 每部视频自己的设置，重新打开时自动恢复 */
export type VideoPref = {
  speed?: number
  subtitlePath?: string
  danmaku?: { source: string; id: string; title: string; subtitle?: string }
  at?: number
}

export type LocalItem = {
  key: string
  path: string
  name: string
  dir: string
  size: number
  mtime: number
}

declare global {
  interface Window {
    gp: {
      invoke<T = any>(method: string, ...args: any[]): Promise<T>
      onEvent(fn: (msg: { type: string; payload: any }) => void): () => void
    }
  }
}
