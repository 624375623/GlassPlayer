'use strict'
const { contextBridge, ipcRenderer } = require('electron')

const listeners = new Set()

ipcRenderer.on('gp:event', (_event, message) => {
  listeners.forEach((fn) => {
    try {
      fn(message)
    } catch (e) {
      console.error('[preload] listener error', e)
    }
  })
})

contextBridge.exposeInMainWorld('gp', {
  async invoke(method, ...args) {
    const res = await ipcRenderer.invoke('gp', method, ...args)
    if (!res || res.ok !== true) {
      const err = new Error((res && res.error && res.error.message) || '操作失败')
      err.code = res && res.error && res.error.code
      throw err
    }
    return res.data
  },
  onEvent(fn) {
    listeners.add(fn)
    return () => listeners.delete(fn)
  },
})
