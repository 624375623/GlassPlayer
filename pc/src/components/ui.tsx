import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Icon } from './Icon'

/* ---------------- Toast ---------------- */

type ToastKind = 'info' | 'error'
type ToastCtx = (message: string, kind?: ToastKind) => void
const ToastContext = createContext<ToastCtx>(() => {})
export const useToast = () => useContext(ToastContext)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<{ id: number; message: string; kind: ToastKind }[]>([])
  const seq = useRef(1)
  const push = useCallback<ToastCtx>((message, kind = 'info') => {
    const id = seq.current++
    setItems((prev) => [...prev, { id, message, kind }])
    setTimeout(() => setItems((prev) => prev.filter((t) => t.id !== id)), kind === 'error' ? 4200 : 2400)
  }, [])
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.kind === 'error' ? 'error' : ''}`}>
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/* ---------------- 基础控件 ---------------- */

export function Switch({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      className={`switch ${on ? 'on' : ''}`}
      onClick={() => onChange(!on)}
      aria-pressed={on}
    >
      <i />
    </button>
  )
}

export function Segmented<T extends string | number>({
  value,
  options,
  onChange,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
}) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button
          key={String(o.value)}
          type="button"
          className={value === o.value ? 'active' : ''}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Slider({
  value,
  min,
  max,
  step = 1,
  onChange,
  width = 130,
  suffix,
  format,
}: {
  value: number
  min: number
  max: number
  step?: number
  onChange: (v: number) => void
  width?: number
  suffix?: string
  format?: (v: number) => string
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        style={{ width }}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <span style={{ fontSize: 11.5, color: 'var(--ink-3)', minWidth: 38, fontVariantNumeric: 'tabular-nums' }}>
        {format ? format(value) : value}
        {suffix || ''}
      </span>
    </div>
  )
}

export function Modal({
  title,
  subtitle,
  onClose,
  children,
  footer,
  wide,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`}>
        <div className="mhead">
          <div style={{ flex: 1 }}>
            <h2>{title}</h2>
            {subtitle ? <div className="sub">{subtitle}</div> : null}
          </div>
          <button className="icon-btn" onClick={onClose} title="关闭">
            <Icon name="close" />
          </button>
        </div>
        <div className="mbody">{children}</div>
        {footer ? <div className="mfoot">{footer}</div> : null}
      </div>
    </div>
  )
}

export function Sheet({
  title,
  subtitle,
  onClose,
  children,
  footer,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <>
      <div className="overlay" style={{ background: 'rgba(12,16,28,0.28)' }} onMouseDown={onClose} />
      <div className="sheet">
        <div className="mhead">
          <div style={{ flex: 1, minWidth: 0 }}>
            <h2 style={{ margin: 0, fontSize: 16 }}>{title}</h2>
            {subtitle ? <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 3 }}>{subtitle}</div> : null}
          </div>
          <button className="icon-btn" onClick={onClose}>
            <Icon name="close" />
          </button>
        </div>
        <div className="mbody">{children}</div>
        {footer ? <div className="mfoot">{footer}</div> : null}
      </div>
    </>
  )
}

export function SpinnerBox({ text }: { text: string }) {
  return (
    <div className="loading-line">
      <span className="spinner" />
      <span>{text}</span>
    </div>
  )
}
