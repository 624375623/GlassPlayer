export function formatSize(bytes?: number): string {
  const n = Number(bytes) || 0
  if (n <= 0) return '大小未知'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i += 1
  }
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} ${units[i]}`
}

export function formatDuration(ms?: number): string {
  const total = Math.round((Number(ms) || 0) / 1000)
  if (!total) return '--:--'
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (x: number) => String(x).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

export function formatDurationLong(ms?: number): string {
  const total = Math.round((Number(ms) || 0) / 1000)
  if (!total) return ''
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分钟`
}

export function formatDate(ts?: number | string): string {
  if (!ts) return ''
  const d = typeof ts === 'number' ? new Date(ts < 1e12 ? ts * 1000 : ts) : new Date(ts)
  if (Number.isNaN(d.getTime())) return String(ts)
  const now = Date.now()
  const diff = now - d.getTime()
  if (diff >= 0 && diff < 60_000) return '刚刚'
  if (diff >= 0 && diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff >= 0 && diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const sameYear = d.getFullYear() === new Date().getFullYear()
  const pad = (x: number) => String(x).padStart(2, '0')
  return sameYear
    ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 去掉视频扩展名，用于标题展示。 */
export function stripExt(name: string): string {
  return String(name || '').replace(/\.[A-Za-z0-9]{2,5}$/, '')
}

export function panLabel(type: string): string {
  const map: Record<string, string> = {
    quark: '夸克',
    baidu: '百度',
    aliyun: '阿里',
    '115': '115',
    uc: 'UC',
    xunlei: '迅雷',
    tianyi: '天翼',
    '123': '123',
    pikpak: 'PikPak',
    mobile: '移动',
  }
  return map[type] || type || '网盘'
}

export const SITE_LABEL: Record<string, string> = {
  mipan: '觅盘',
  gugeso: '谷哥搜',
  kkso: 'KK搜',
  panpansou: '盘盘搜',
  pansoude: 'Pansou.de',
}

export function sourceLabel(link: { site?: string; siteLabel?: string }): string {
  return link.siteLabel || SITE_LABEL[link.site || ''] || link.site || ''
}
