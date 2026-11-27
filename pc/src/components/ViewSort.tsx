import { useEffect, useRef, useState } from 'react'
import { Icon } from './Icon'
import { Segmented, Switch } from './ui'

export type SortOption = { value: number; label: string }

export function ViewSortMenu({
  grid,
  onGrid,
  sort,
  onSort,
  asc,
  onAsc,
  options,
  extra,
}: {
  grid: boolean
  onGrid: (v: boolean) => void
  sort: number
  onSort: (v: number) => void
  asc: boolean
  onAsc: (v: boolean) => void
  options: SortOption[]
  extra?: React.ReactNode
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  return (
    <div style={{ position: 'relative' }} ref={ref}>
      <button className={`btn ${open ? 'primary' : ''}`} onClick={() => setOpen((v) => !v)}>
        <Icon name={grid ? 'grid' : 'list'} size={15} />
        视图与排序
        <Icon name="down" size={13} />
      </button>
      {open && (
        <div
          style={{
            position: 'absolute',
            right: 0,
            top: 42,
            zIndex: 20,
            width: 268,
            padding: 12,
            borderRadius: 16,
            background: 'var(--glass-strong)',
            border: '1px solid var(--line-strong)',
            boxShadow: 'var(--shadow-lg)',
            backdropFilter: 'blur(28px) saturate(180%)',
          }}
        >
          <div style={{ fontSize: 11, color: 'var(--ink-3)', marginBottom: 6 }}>布局</div>
          <Segmented
            value={grid ? 'grid' : 'list'}
            options={[
              { value: 'grid', label: '网格' },
              { value: 'list', label: '列表' },
            ]}
            onChange={(v) => onGrid(v === 'grid')}
          />
          <div style={{ fontSize: 11, color: 'var(--ink-3)', margin: '12px 0 6px' }}>排序</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {options.map((o) => (
              <button key={o.value} className={`chip ${sort === o.value ? 'active' : ''}`} onClick={() => onSort(o.value)}>
                {o.label}
              </button>
            ))}
          </div>
          <div className="form-row" style={{ padding: '10px 0 2px', borderBottom: 0 }}>
            <div className="label">
              <b>升序</b>
              <span>{asc ? '从小到大 / A→Z' : '从大到小 / 新→旧'}</span>
            </div>
            <Switch on={asc} onChange={onAsc} />
          </div>
          {extra}
        </div>
      )}
    </div>
  )
}
