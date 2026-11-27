import { createContext, useContext } from 'react'
import type { PlayTarget, Settings, Snapshot } from './types'

export type Tab = 'library' | 'cloud' | 'resource' | 'history' | 'settings'

export type AppCtxValue = {
  snap: Snapshot
  patch: (patch: Partial<Settings>) => Promise<void> | void
  refresh: () => Promise<void>
  openPlayer: (target: PlayTarget, queue?: PlayTarget[]) => void
  setTab: (tab: Tab) => void
  toast: (msg: string, kind?: 'info' | 'error') => void
}

export const AppCtx = createContext<AppCtxValue | null>(null)

export function useApp(): AppCtxValue {
  const ctx = useContext(AppCtx)
  if (!ctx) throw new Error('AppCtx missing')
  return ctx
}
