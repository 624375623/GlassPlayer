'use strict'
/**
 * 资源站请求统一走 Electron 的网络栈（Chromium），好处：
 * 1. 自动跟随系统代理 / 用户开的加速器，Node 的 fetch 默认不认代理；
 * 2. TLS/HTTP2 行为与浏览器一致，不容易被站点风控拦掉；
 * 3. 支持流式响应（SSE / NDJSON 边收边解析）。
 * 在纯 Node 环境下自动退回全局 fetch。
 */
let chromaNet = null
try {
  const electron = require('electron')
  if (electron && electron.net) chromaNet = electron.net
} catch (_) {}

function normalizeHeaders(raw) {
  const map = new Map()
  for (const [k, v] of Object.entries(raw || {})) {
    map.set(k.toLowerCase(), Array.isArray(v) ? v.join(', ') : String(v))
  }
  return {
    get(name) {
      return map.get(String(name).toLowerCase()) ?? null
    },
    all() {
      return Object.fromEntries(map)
    },
  }
}

function requestWithElectron(url, { method = 'GET', headers = {}, body, timeout = 25000, redirect = 'follow' } = {}) {
  return new Promise((resolve, reject) => {
    let settled = false
    let req
    try {
      req = chromaNet.request({ method, url, redirect, useSessionCookies: false })
    } catch (e) {
      reject(e)
      return
    }
    for (const [k, v] of Object.entries(headers)) {
      if (v == null) continue
      try {
        req.setHeader(k, v)
      } catch (_) {}
    }
    const timer = setTimeout(() => {
      try {
        req.abort()
      } catch (_) {}
      if (!settled) {
        settled = true
        const err = new Error('请求超时')
        err.code = 'ETIMEDOUT'
        reject(err)
      }
    }, timeout)
    req.on('response', (res) => {
      clearTimeout(timer)
      settled = true
      const stream = res
      resolve({
        ok: res.statusCode >= 200 && res.statusCode < 300,
        status: res.statusCode,
        headers: normalizeHeaders(res.headers),
        stream,
        async text() {
          const chunks = []
          await new Promise((done, fail) => {
            stream.on('data', (c) => chunks.push(Buffer.from(c)))
            stream.on('end', done)
            stream.on('error', fail)
            stream.on('aborted', () => fail(new Error('响应被中断')))
          })
          return Buffer.concat(chunks).toString('utf8')
        },
        async json() {
          return JSON.parse(await this.text())
        },
      })
    })
    req.on('error', (err) => {
      clearTimeout(timer)
      if (!settled) {
        settled = true
        reject(err)
      }
    })
    if (body) req.write(typeof body === 'string' ? Buffer.from(body, 'utf8') : body)
    req.end()
  })
}

async function fetchFallback(url, opts = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), opts.timeout || 25000)
  try {
    const res = await fetch(url, {
      method: opts.method || 'GET',
      headers: opts.headers,
      body: opts.body,
      redirect: opts.redirect || 'follow',
      signal: controller.signal,
    })
    return {
      ok: res.ok,
      status: res.status,
      headers: {
        get: (n) => res.headers.get(n),
        all: () => Object.fromEntries(res.headers.entries()),
      },
      stream: res.body,
      text: () => res.text(),
      json: () => res.json(),
    }
  } finally {
    clearTimeout(timer)
  }
}

function netFetch(url, opts = {}) {
  return chromaNet ? requestWithElectron(url, opts) : fetchFallback(url, opts)
}

/** 逐行读取（同时兼容 Node 流和 Web 流），返回 false 可中止。 */
async function readLines(res, onLine) {
  const stream = res.stream
  if (stream && typeof stream.on === 'function') {
    let buffer = ''
    let stopped = false
    await new Promise((resolve) => {
      const finish = () => {
        if (!stopped) {
          stopped = true
          resolve()
        }
      }
      stream.on('data', (chunk) => {
        if (stopped) return
        buffer += Buffer.from(chunk).toString('utf8')
        let idx
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '')
          buffer = buffer.slice(idx + 1)
          if (onLine(line) === false) {
            stopped = true
            try {
              stream.destroy?.()
            } catch (_) {}
            resolve()
            return
          }
        }
      })
      stream.on('end', () => {
        if (!stopped && buffer.trim()) onLine(buffer.trim())
        finish()
      })
      stream.on('error', finish)
      stream.on('aborted', finish)
      setTimeout(finish, 60000)
    })
    return
  }
  const reader = stream?.getReader?.()
  if (!reader) return
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '')
      buffer = buffer.slice(idx + 1)
      if (onLine(line) === false) {
        try {
          await reader.cancel()
        } catch (_) {}
        return
      }
    }
  }
  if (buffer.trim()) onLine(buffer.trim())
}

module.exports = { netFetch, readLines, hasChromiumNet: !!chromaNet }
