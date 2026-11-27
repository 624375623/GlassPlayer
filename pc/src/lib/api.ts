import type { Settings, Snapshot } from './types'

export const api = {
  info: () => window.gp.invoke<any>('app:info'),
  settings: () => window.gp.invoke<Settings>('settings:get'),
  patchSettings: (patch: Partial<Settings>) => window.gp.invoke<Settings>('settings:patch', patch),
  snapshot: () => window.gp.invoke<Snapshot>('store:snapshot'),
  savePosition: (key: string, pos: number, duration: number) =>
    window.gp.invoke('store:savePosition', { key, pos, duration }),
  clearPosition: (key: string) => window.gp.invoke('store:clearPosition', key),
  resetPositions: () => window.gp.invoke('store:resetPositions'),
  toggleFavorite: (key: string) => window.gp.invoke<boolean>('store:toggleFavorite', key),
  videoPref: (key: string) => window.gp.invoke<import('./types').VideoPref | null>('store:videoPref', { key }),
  saveVideoPref: (key: string, patch: Partial<import('./types').VideoPref>) =>
    window.gp.invoke<import('./types').VideoPref>('store:saveVideoPref', { key, patch }),
  saveCloudMeta: (meta: any) => window.gp.invoke('store:saveCloudMeta', meta),
  toggleHiddenCloud: (fid: string) => window.gp.invoke<boolean>('store:toggleHiddenCloud', fid),

  // 夸克
  quarkQr: () => window.gp.invoke<{ token: string; scanUrl: string; dataUrl: string }>('quark:qr'),
  quarkPoll: (token: string) =>
    window.gp.invoke<{ state: 'wait' | 'ok' | 'expired' | 'error'; message?: string }>('quark:poll', token),
  quarkStatus: () =>
    window.gp.invoke<{ loggedIn: boolean; nickname?: string; vip?: string | null; message?: string; expired?: boolean }>(
      'quark:status'
    ),
  quarkLogout: () => window.gp.invoke('quark:logout'),
  quarkList: (fid: string, page = 1, size = 100) =>
    window.gp.invoke<{ list: import('./types').QuarkFile[]; hasMore: boolean; total: number }>('quark:list', {
      fid,
      page,
      size,
    }),
  quarkPlay: (fid: string, meta: any, mode?: string) =>
    window.gp.invoke<{ url: string; kind: 'hls' | 'file'; mode: string; warning: string | null; raw: string }>(
      'quark:play',
      { fid, meta, mode }
    ),
  sharePreview: (url: string, passcode: string, pdirFid = '0') =>
    window.gp.invoke<any>('quark:sharePreview', { url, passcode, pdirFid }),
  saveShare: (url: string, passcode: string) => window.gp.invoke<{ count: number }>('quark:saveShare', { url, passcode }),

  // 资源
  sources: () => window.gp.invoke<import('./types').SourceMeta[]>('res:sources'),
  search: (sources: string[], keyword: string) =>
    window.gp.invoke<{ list: import('./types').ResourceLink[]; errors: { label: string; message: string }[] }>(
      'res:search',
      { sources, keyword }
    ),
  checkLinks: (links: import('./types').ResourceLink[]) =>
    window.gp.invoke<Record<string, string>>('res:check', { links }),

  // 弹幕
  danmakuSearch: (source: string, keyword: string, serverUrl: string) =>
    window.gp.invoke<import('./types').DanmakuMatch[]>('danmaku:search', { source, keyword, serverUrl }),
  danmakuEpisodes: (seasonId: string, source?: string) =>
    window.gp.invoke<import('./types').DanmakuMatch[]>('danmaku:episodes', { seasonId, source }),
  danmakuTencentEpisode: (id: string, index: number) =>
    window.gp.invoke<import('./types').DanmakuMatch | null>('danmaku:tencentEpisode', { id, index }),
  danmakuLoad: (source: string, id: string, serverUrl: string, limit?: number) =>
    window.gp.invoke<{ items: import('./types').DanmakuItem[]; episode?: import('./types').DanmakuMatch; part?: string }>(
      'danmaku:load',
      { source, id, serverUrl, limit }
    ),
  cleanTitle: (name: string) => window.gp.invoke<string>('danmaku:cleanTitle', name),

  // 本地
  scan: (folders: string[]) => window.gp.invoke<import('./types').LocalItem[]>('lib:scan', { folders }),
  mediaLocal: (path: string) => window.gp.invoke<string>('media:local', path),
  pickFolders: () => window.gp.invoke<string[]>('dialog:pickFolders'),
  pickFiles: (filters?: any) => window.gp.invoke<string[]>('dialog:pickFiles', filters),
  pickSubtitle: () => window.gp.invoke<string[]>('dialog:pickSubtitle'),
  readText: (path: string) => window.gp.invoke<string>('fs:readText', path),
  exists: (path: string) => window.gp.invoke<boolean>('fs:exists', path),

  // 系统
  openExternal: (url: string) => window.gp.invoke('shell:openExternal', url),
  showItem: (path: string) => window.gp.invoke('shell:showItem', path),
  copy: (text: string) => window.gp.invoke('clipboard:write', text),
  minimize: () => window.gp.invoke('window:minimize'),
  toggleMax: () => window.gp.invoke<boolean>('window:toggleMax'),
  close: () => window.gp.invoke('window:close'),
  isMax: () => window.gp.invoke<boolean>('window:isMax'),
  onEvent: (fn: (msg: { type: string; payload: any }) => void) => window.gp.onEvent(fn),
}
