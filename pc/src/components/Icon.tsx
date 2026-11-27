import type { ReactNode } from "react"

type Props = { name: string; size?: number; className?: string; strokeWidth?: number }

const P: Record<string, string> = {
  layers: 'M12 3 3 8l9 5 9-5-9-5Z M3 14l9 5 9-5 M3 11l9 5 9-5',
  folder: 'M3 7.5A2.5 2.5 0 0 1 5.5 5h3.2c.6 0 1.2.25 1.6.7l1 1.1c.4.45 1 .7 1.6.7h4.6A2.5 2.5 0 0 1 20 10v6.5A2.5 2.5 0 0 1 17.5 19h-12A2.5 2.5 0 0 1 3 16.5v-9Z',
  cloud: 'M7 18a4 4 0 0 1-.6-7.96 5.5 5.5 0 0 1 10.6-1.1A3.75 3.75 0 0 1 17.5 18H7Z',
  compass: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M14.8 9.2l-1.6 4-4 1.6 1.6-4 4-1.6Z',
  settings:
    'M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z M19.4 14.5a1.6 1.6 0 0 0 .32 1.77l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-1.77-.32 1.6 1.6 0 0 0-.97 1.47V20a2 2 0 1 1-4 0v-.06a1.6 1.6 0 0 0-1.05-1.47 1.6 1.6 0 0 0-1.77.32l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.6 1.6 0 0 0 .32-1.77 1.6 1.6 0 0 0-1.47-.97H4a2 2 0 1 1 0-4h.06A1.6 1.6 0 0 0 5.53 8.9a1.6 1.6 0 0 0-.32-1.77l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.6 1.6 0 0 0 1.77.32H10a1.6 1.6 0 0 0 .97-1.47V3a2 2 0 1 1 4 0v.06a1.6 1.6 0 0 0 .97 1.47 1.6 1.6 0 0 0 1.77-.32l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.6 1.6 0 0 0-.32 1.77v.09c.24.59.81.98 1.45.98H22a2 2 0 1 1 0 4h-.06a1.6 1.6 0 0 0-1.47.97Z',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z M21 21l-4.3-4.3',
  film: 'M4 5h16v14H4V5Z M4 9h16 M4 15h16 M9 5v14 M15 5v14',
  heart:
    'M12 20s-7.5-4.7-7.5-9.4A4.1 4.1 0 0 1 12 7.6a4.1 4.1 0 0 1 7.5 3C19.5 15.3 12 20 12 20Z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M12 7.5V12l3 2',
  play: 'M8 5.5v13l11-6.5-11-6.5Z',
  pause: 'M9 5.5v13 M15 5.5v13',
  prev: 'M18.5 5.5v13L9 12l9.5-6.5Z M6 5.5v13',
  next: 'M5.5 5.5v13L15 12 5.5 5.5Z M18 5.5v13',
  volume: 'M11 5 6.5 9H3v6h3.5L11 19V5Z M15.2 8.8a4.5 4.5 0 0 1 0 6.4 M18 6a8 8 0 0 1 0 12',
  mute: 'M11 5 6.5 9H3v6h3.5L11 19V5Z M16 9.5l4 5 M20 9.5l-4 5',
  maximize: 'M8 3H4v4 M16 3h4v4 M16 21h4v-4 M8 21H4v-4',
  minimize: 'M5 12h14',
  close: 'M6 6l12 12 M18 6 6 18',
  refresh: 'M20 12a8 8 0 1 1-2.6-5.9 M20 4.5V10h-5.4',
  left: 'M15 5l-7 7 7 7',
  right: 'M9 5l7 7-7 7',
  down: 'M6 9.5l6 6 6-6',
  up: 'M6 14.5l6-6 6 6',
  check: 'M4.5 12.5l5 5 10-11',
  download: 'M12 4v10 M8 10.5l4 4 4-4 M4.5 19.5h15',
  copy: 'M9 9h10v11H9V9Z M5 15H4V4h10v1',
  external: 'M14 4h6v6 M20 4 11 13 M19 14v5H5V5h5',
  subtitles: 'M3.5 5.5h17v13h-17v-13Z M7 12h3 M13 12h4 M7 15.5h6 M16 15.5h1',
  message: 'M4 5.5h16v10H9l-5 4v-14Z M8 9.5h8 M8 12h5',
  speed: 'M12 20a8 8 0 1 1 8-8 M12 12l4.5-3.2',
  pip: 'M3 5.5h18v13H3v-13Z M12.5 11.5h6v5h-6v-5Z',
  trash: 'M4 7h16 M9.5 7V4.5h5V7 M6.5 7l1 13h9l1-13',
  plus: 'M12 5v14 M5 12h14',
  sort: 'M7 5v14 M4 16l3 3 3-3 M14 7h6 M14 12h4.5 M14 17h3',
  grid: 'M4 4h7v7H4V4Z M13 4h7v7h-7V4Z M4 13h7v7H4v-7Z M13 13h7v7h-7v-7Z',
  list: 'M4 6h16 M4 12h16 M4 18h16',
  link: 'M10.5 13.5a3.5 3.5 0 0 0 5 0l3-3a3.54 3.54 0 0 0-5-5l-1.5 1.5 M13.5 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.54 3.54 0 0 0 5 5l1.5-1.5',
  sparkles: 'M12 3.5 13.6 8 18 9.6 13.6 11.2 12 15.7 10.4 11.2 6 9.6 10.4 8 12 3.5Z M18 15l.9 2.1 2.1.9-2.1.9L18 21l-.9-2.1-2.1-.9 2.1-.9L18 15Z',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z M4.5 20a7.5 7.5 0 0 1 15 0',
  qr: 'M4 4h6v6H4V4Z M14 4h6v6h-6V4Z M4 14h6v6H4v-6Z M14 14h2v2h-2v-2Z M18 14h2v2h-2v-2Z M14 18h2v2h-2v-2Z M18 18h2v2h-2v-2Z',
  logout: 'M15 5.5H19V19h-4 M12 12H4 M7.5 8.5 4 12l3.5 3.5',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z M12 11v5.5 M12 7.6v.6',
  expand: 'M9 4H4v5 M15 4h5v5 M15 20h5v-5 M9 20H4v-5',
  shuffle: 'M4 7h3.5L16 17h4 M17.5 17H16 M4 17h3.5L11 12 M16 7h4 M17.5 7H16',
  rotate: 'M20 11a8 8 0 1 0-3 6.2 M20 5.5V11h-5.5',
  key: 'M14.5 4a5 5 0 1 1-3.6 8.5L9 14.5l-2 2-3 1 1-3 2-2 1.9-1.9A5 5 0 0 1 14.5 4Z',
  more: 'M6.5 12m-1.3 0a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0 M12 12m-1.3 0a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0 M17.5 12m-1.3 0a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0',

  monitor: 'M3 5h18v11H3V5Z M9 20h6 M12 16v4',
  cpu: 'M7 7h10v10H7V7Z M10 4v3 M14 4v3 M10 17v3 M14 17v3 M4 10h3 M4 14h3 M17 10h3 M17 14h3',
  folderPlus: 'M3 7.5A2.5 2.5 0 0 1 5.5 5h3.2l2.6 2.4h5.2A2.5 2.5 0 0 1 19 10v6.5A2.5 2.5 0 0 1 16.5 19h-11A2.5 2.5 0 0 1 3 16.5v-9Z M11 5v11 M5.5 10.5h11',
}

export function Icon({ name, size = 20, className, strokeWidth = 1.7 }: Props) {
  const d = P[name]
  if (!d) return null
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {d.split(' M').map((seg, i) => (
        <path key={i} d={i === 0 ? seg : `M${seg}`} />
      ))}
    </svg>
  )
}

export function Spinner({ size = 20 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} />
}

export function Empty({
  icon = 'film',
  title,
  desc,
  children,
}: {
  icon?: string
  title: string
  desc?: string
  children?: ReactNode
}) {
  return (
    <div className="empty fade-in">
      <div className="art">
        <Icon name={icon} size={34} strokeWidth={1.4} />
      </div>
      <h3>{title}</h3>
      {desc ? <p>{desc}</p> : null}
      {children}
    </div>
  )
}
