import { Icon } from './Icon'
import { formatDuration } from '../lib/format'

export function VideoCard({
  title,
  sub,
  durationMs,
  progress,
  favorite,
  badge,
  onOpen,
  onFavorite,
  onContext,
}: {
  title: string
  sub: string
  durationMs?: number
  progress?: number
  favorite?: boolean
  badge?: string
  onOpen: () => void
  onFavorite?: () => void
  onContext?: (e: React.MouseEvent) => void
}) {
  return (
    <div
      className="vcard"
      onClick={onOpen}
      onContextMenu={onContext}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && onOpen()}
    >
      <div className="thumb">
        <div className="glyph">
          <Icon name="play" size={20} />
        </div>
        <div className="playmask">
          <Icon name="play" size={34} />
        </div>
        {badge ? (
          <div className="corner">
            <span className="tag">{badge}</span>
          </div>
        ) : null}
        {durationMs ? <span className="dur">{formatDuration(durationMs)}</span> : null}
        {progress && progress > 0.01 && progress < 0.99 ? (
          <div className="progress">
            <i style={{ width: `${Math.min(100, progress * 100)}%` }} />
          </div>
        ) : null}
      </div>
      <div className="meta">
        <div className="name" title={title}>
          {title}
        </div>
        <div className="sub">{sub}</div>
      </div>
      {onFavorite ? (
        <button
          className={`fav ${favorite ? 'on' : ''}`}
          title={favorite ? '取消收藏' : '收藏'}
          onClick={(e) => {
            e.stopPropagation()
            onFavorite()
          }}
        >
          <Icon name="heart" size={15} />
        </button>
      ) : null}
    </div>
  )
}

export function VideoRow({
  title,
  sub,
  icon = 'film',
  folder,
  durationMs,
  favorite,
  onOpen,
  onFavorite,
  onMore,
  onContext,
}: {
  title: string
  sub: string
  icon?: string
  folder?: boolean
  durationMs?: string
  favorite?: boolean
  onOpen: () => void
  onFavorite?: () => void
  onMore?: () => void
  onContext?: (e: React.MouseEvent) => void
}) {
  return (
    <div className="row" onClick={onOpen} onContextMenu={onContext}>
      <div className={`ic ${folder ? 'folder' : ''}`}>
        <Icon name={icon} size={19} />
      </div>
      <div className="main">
        <div className="t" title={title}>
          {title}
        </div>
        <div className="s">
          <span>{sub}</span>
          {durationMs ? <span>{durationMs}</span> : null}
        </div>
      </div>
      <div className="acts">
        {onFavorite ? (
          <button
            className={`icon-btn ${favorite ? 'active' : ''}`}
            title={favorite ? '取消收藏' : '收藏'}
            onClick={(e) => {
              e.stopPropagation()
              onFavorite()
            }}
          >
            <Icon name="heart" size={16} />
          </button>
        ) : null}
        {onMore ? (
          <button
            className="icon-btn"
            title="更多"
            onClick={(e) => {
              e.stopPropagation()
              onMore()
            }}
          >
            <Icon name="more" size={16} />
          </button>
        ) : null}
      </div>
    </div>
  )
}
