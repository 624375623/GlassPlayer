'use strict'
/**
 * 本地 HTTP 媒体代理。
 *
 * 为什么需要它：
 * 1. 夸克 CDN 的直链必须带 Cookie / Referer / 客户端 UA，浏览器里的 <video> 无法自定义这些头；
 * 2. CDN 不保证返回 CORS 头，hls.js 直接拉 m3u8 会被拦；
 * 3. m3u8 里的分片地址需要重写成代理地址，否则分片请求照样缺头。
 *
 * 路由（都带随机 secret，避免本机其它网页盗用登录态）：
 *   GET /m/<secret>/q?u=<encoded upstream url>   → 远程媒体代理（支持 Range，自动重写 m3u8）
 *   GET /m/<secret>/f?p=<encoded local path>     → 本地文件（支持 Range）
 */
const http = require('http')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { Readable } = require('stream')

const MIME = {
  '.mp4': 'video/mp4',
  '.m4v': 'video/mp4',
  '.mkv': 'video/x-matroska',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.avi': 'video/x-msvideo',
  '.ts': 'video/mp2t',
  '.flv': 'video/x-flv',
  '.wmv': 'video/x-ms-wmv',
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.mpd': 'application/dash+xml',
  '.mp3': 'audio/mpeg',
  '.aac': 'audio/aac',
  '.m4a': 'audio/mp4',
  '.flac': 'audio/flac',
  '.wav': 'audio/wav',
  '.srt': 'text/plain; charset=utf-8',
  '.vtt': 'text/vtt; charset=utf-8',
}

const CLIENT_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 ' +
  'Safari/537.36 Channel/pckk_other_ch'

const HOP_SKIP = new Set([
  'connection',
  'keep-alive',
  'transfer-encoding',
  'content-encoding',
  'content-length',
  'set-cookie',
  'cookie',
])

class MediaServer {
  constructor(store) {
    this.store = store
    this.secret = crypto.randomBytes(10).toString('hex')
    this.port = 0
    this.server = null
  }

  async start() {
    if (this.server) return this.port
    this.server = http.createServer((req, res) => {
      this.handle(req, res).catch((e) => {
        if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end(`proxy error: ${e.message}`)
      })
    })
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(0, '127.0.0.1', resolve)
    })
    this.port = this.server.address().port
    return this.port
  }

  stop() {
    if (this.server) {
      try {
        this.server.close()
      } catch (_) {}
      this.server = null
    }
  }

  remote(u) {
    return `http://127.0.0.1:${this.port}/m/${this.secret}/q?u=${encodeURIComponent(u)}`
  }

  local(p) {
    return `http://127.0.0.1:${this.port}/m/${this.secret}/f?p=${encodeURIComponent(p)}`
  }

  async handle(req, res) {
    const base = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Expose-Headers': '*' }
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        ...base,
        'Access-Control-Allow-Methods': 'GET,HEAD,OPTIONS',
        'Access-Control-Max-Age': '86400',
      })
      res.end()
      return
    }
    const url = new URL(req.url, 'http://127.0.0.1')
    const parts = url.pathname.split('/').filter(Boolean)
    if (parts[0] !== 'm' || parts[1] !== this.secret) {
      res.writeHead(403, { ...base, 'Content-Type': 'text/plain' })
      res.end('forbidden')
      return
    }
    if (parts[2] === 'q') return this.proxyRemote(req, res, url, base)
    if (parts[2] === 'f') return this.serveLocal(req, res, url, base)
    res.writeHead(404, base)
    res.end('not found')
  }

  /* ---------------- 远程代理 ---------------- */

  async proxyRemote(req, res, url, base) {
    const target = url.searchParams.get('u')
    if (!target) {
      res.writeHead(400, { ...base, 'Content-Type': 'text/plain' })
      res.end('missing u')
      return
    }
    const headers = {
      'User-Agent': CLIENT_UA,
      Referer: 'https://pan.quark.cn/',
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9',
    }
    let host = ''
    try {
      host = new URL(target).host
    } catch (_) {}
    const isQuark = /(^|\.)quark\.cn$/i.test(host)
    if (isQuark) {
      const cookie = this.store.settings.quarkCookie
      if (cookie) headers.Cookie = cookie
      headers.Origin = 'https://pan.quark.cn'
    }

    // 播放列表先走文本路径，避免为一行 m3u8 做 Range 探测
    if (/\.m3u8(\?|$)/i.test(target)) {
      return this.proxyPlaylist(req, res, base, target, headers)
    }

    /*
     * 网盘下载直链的「单连接」被压到 ~100KB/s（手机版实测结论），单线程转发必然卡。
     * 对齐手机版 QuarkParallelDataSource：先探 206 拿总长度，再按 512KB 分片、
     * 8 路并发 Range 拉取，按序写给播放器 —— 相当于内置一个多线程下载器。
     */
    const probe = await probeRemote(target, headers)
    if (probe.total > PARALLEL_MIN_BYTES && req.method !== 'HEAD') {
      if (await this.streamParallel(req, res, base, target, headers, probe)) return
    }

    if (req.headers.range) headers.Range = req.headers.range
    let upstream
    try {
      upstream = await fetch(target, {
        method: req.method === 'HEAD' ? 'HEAD' : 'GET',
        headers,
        redirect: 'follow',
      })
    } catch (e) {
      res.writeHead(502, { ...base, 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(`upstream fetch failed: ${e.message}`)
      return
    }

    const ctype = upstream.headers.get('content-type') || ''
    const isPlaylist = /mpegurl/i.test(ctype)

    if (upstream.status >= 400) {
      const detail = await upstream.text().catch(() => '')
      res.writeHead(upstream.status, { ...base, 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(detail.slice(0, 400) || `upstream ${upstream.status}`)
      return
    }

    if (isPlaylist) {
      const text = await upstream.text()
      const rewritten = rewritePlaylist(text, target, (u) => this.remote(u))
      res.writeHead(200, {
        ...base,
        'Content-Type': 'application/vnd.apple.mpegurl',
        'Cache-Control': 'no-cache',
      })
      res.end(rewritten)
      return
    }

    const outHeaders = { ...base, 'Accept-Ranges': 'bytes' }
    for (const [k, v] of upstream.headers.entries()) {
      if (HOP_SKIP.has(k.toLowerCase())) continue
      outHeaders[k] = v
    }
    if (!outHeaders['content-type']) outHeaders['content-type'] = 'application/octet-stream'
    res.writeHead(upstream.status, outHeaders)
    if (!upstream.body || req.method === 'HEAD') {
      res.end()
      return
    }
    const stream = Readable.fromWeb(upstream.body)
    stream.on('error', () => {
      try {
        res.destroy()
      } catch (_) {}
    })
    req.on('close', () => {
      try {
        stream.destroy()
      } catch (_) {}
    })
    stream.pipe(res)
  }

  /** m3u8：取回文本并把分片 / 子列表 / 密钥地址全部改成代理地址。 */
  async proxyPlaylist(req, res, base, target, headers) {
    let upstream
    try {
      upstream = await fetch(target, { headers, redirect: 'follow' })
    } catch (e) {
      res.writeHead(502, { ...base, 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(`upstream fetch failed: ${e.message}`)
      return
    }
    if (upstream.status >= 400) {
      const detail = await upstream.text().catch(() => '')
      res.writeHead(upstream.status, { ...base, 'Content-Type': 'text/plain; charset=utf-8' })
      res.end(detail.slice(0, 400) || `upstream ${upstream.status}`)
      return
    }
    const text = await upstream.text()
    const rewritten = rewritePlaylist(text, target, (u) => this.remote(u))
    res.writeHead(200, { ...base, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-cache' })
    res.end(rewritten)
  }

  /**
   * 多连接分片拉取。返回 true 表示已完全接管响应（含头部），false 表示未发头可回退。
   * 拉取侧 8 路并行、最多领先 16 片（8MB），写侧严格按序，维持播放器的顺序读语义。
   */
  async streamParallel(req, res, base, target, headers, probe) {
    const total = probe.total
    let start = 0
    let end = total - 1
    const range = req.headers.range
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range)
      if (m) {
        if (m[1]) start = parseInt(m[1], 10)
        if (m[2]) end = parseInt(m[2], 10)
      }
      if (!Number.isFinite(start) || start < 0) start = 0
      if (!Number.isFinite(end) || end >= total || end < start) end = total - 1
    }
    if (start > end) {
      res.writeHead(416, { ...base, 'Content-Range': `bytes */${total}` })
      res.end()
      return true
    }

    const type = probe.type || MIME[path.extname(new URL(target).pathname).toLowerCase()] || 'application/octet-stream'
    const outHeaders = {
      ...base,
      'Content-Type': type,
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      'Cache-Control': 'no-cache',
    }
    if (range) outHeaders['Content-Range'] = `bytes ${start}-${end}/${total}`
    res.writeHead(range ? 206 : 200, outHeaders)
    if (req.method === 'HEAD') {
      res.end()
      return true
    }

    let closed = false
    const controller = new AbortController()
    const markClosed = () => {
      closed = true
      try {
        controller.abort()
      } catch (_) {}
    }
    res.on('close', markClosed)

    try {
      await pipeParallel(target, headers, res, start, end, controller.signal, () => closed)
    } catch (_) {
      // 头部已发出，只能断开让播放器重试这一段
      try {
        res.destroy()
      } catch (_) {}
    }
    return true
  }

  /* ---------------- 本地文件 ---------------- */

  async serveLocal(req, res, url, base) {
    const p = url.searchParams.get('p')
    if (!p) {
      res.writeHead(400, { ...base, 'Content-Type': 'text/plain' })
      res.end('missing p')
      return
    }
    let stat
    try {
      stat = await fs.promises.stat(p)
    } catch (e) {
      res.writeHead(404, { ...base, 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('file not found')
      return
    }
    const ext = path.extname(p).toLowerCase()
    const type = MIME[ext] || 'application/octet-stream'
    const total = stat.size
    const range = req.headers.range
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range)
      let start = m && m[1] ? parseInt(m[1], 10) : 0
      let end = m && m[2] ? parseInt(m[2], 10) : total - 1
      if (!Number.isFinite(start) || start < 0) start = 0
      if (!Number.isFinite(end) || end >= total) end = total - 1
      if (start > end) {
        res.writeHead(416, { ...base, 'Content-Range': `bytes */${total}` })
        res.end()
        return
      }
      res.writeHead(206, {
        ...base,
        'Content-Type': type,
        'Content-Range': `bytes ${start}-${end}/${total}`,
        'Content-Length': end - start + 1,
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'no-cache',
      })
      if (req.method === 'HEAD') return res.end()
      const stream = fs.createReadStream(p, { start, end })
      stream.on('error', () => res.destroy())
      req.on('close', () => stream.destroy())
      return stream.pipe(res)
    }
    res.writeHead(200, {
      ...base,
      'Content-Type': type,
      'Content-Length': total,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'no-cache',
    })
    if (req.method === 'HEAD') return res.end()
    const stream = fs.createReadStream(p)
    stream.on('error', () => res.destroy())
    req.on('close', () => stream.destroy())
    stream.pipe(res)
  }
}

/** 把 m3u8 里的分片 / 子播放列表 / 密钥地址全部改写成代理地址。 */
function rewritePlaylist(text, baseUrl, wrap) {
  const abs = (uri) => {
    try {
      return new URL(uri, baseUrl).toString()
    } catch (_) {
      return uri
    }
  }
  return text
    .split(/\r?\n/)
    .map((line) => {
      const trimmed = line.trim()
      if (!trimmed) return line
      if (trimmed.startsWith('#')) {
        if (!/URI="/.test(trimmed)) return line
        return line.replace(/URI="([^"]*)"/g, (_, uri) => `URI="${wrap(abs(uri))}"`)
      }
      return wrap(abs(trimmed))
    })
    .join('\n')
}

/* ================= 并行分片拉取 ================= */

const PARALLEL_MIN_BYTES = 2 * 1024 * 1024
const CHUNK_SIZE = 512 * 1024
const PARALLEL_WORKERS = 8
const MAX_AHEAD_CHUNKS = 16

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 探一次 206，拿总长度和真实 MIME；不支持 Range 时 total 为 0，调用方回退单连接。 */
async function probeRemote(target, headers) {
  try {
    const res = await fetch(target, {
      headers: { ...headers, Range: 'bytes=0-1' },
      redirect: 'follow',
      signal: AbortSignal.timeout(15000),
    })
    const cr = res.headers.get('content-range') || ''
    const total = Number(cr.slice(cr.lastIndexOf('/') + 1)) || 0
    const type = res.headers.get('content-type') || ''
    try {
      if (res.body) await res.body.cancel()
    } catch (_) {}
    return { total: res.status === 206 && total > 0 ? total : 0, type: /mpegurl/i.test(type) ? '' : type }
  } catch (_) {
    return { total: 0, type: '' }
  }
}

async function readRange(target, headers, start, end, signal) {
  const timeout = AbortSignal.timeout(25000)
  const res = await fetch(target, {
    headers: { ...headers, Range: `bytes=${start}-${end}` },
    redirect: 'follow',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (res.status !== 206) {
    try {
      await res.arrayBuffer()
    } catch (_) {}
    throw new Error(`range status ${res.status}`)
  }
  const buf = Buffer.from(await res.arrayBuffer())
  const want = end - start + 1
  if (buf.length !== want) throw new Error(`range short ${buf.length}/${want}`)
  return buf
}

/** 8 路并发拉取 [start,end]，按序写给客户端。 */
async function pipeParallel(target, headers, res, start, end, signal, isClosed) {
  const first = Math.floor(start / CHUNK_SIZE)
  const last = Math.floor(end / CHUNK_SIZE)
  const chunks = new Map()
  const failed = new Set()
  let next = first
  let cursor = first
  let stopped = false

  const worker = async () => {
    while (!stopped) {
      if (next > last) return
      if (next > cursor + MAX_AHEAD_CHUNKS) {
        await sleep(60)
        continue
      }
      const i = next
      next += 1
      const s = Math.max(start, i * CHUNK_SIZE)
      const e = Math.min(end, (i + 1) * CHUNK_SIZE - 1)
      let data = null
      for (let attempt = 0; attempt < 2 && !data && !stopped; attempt += 1) {
        try {
          data = await readRange(target, headers, s, e, signal)
        } catch (_) {}
      }
      if (stopped) return
      if (data) chunks.set(i, data)
      else failed.add(i)
    }
  }

  for (let k = 0; k < PARALLEL_WORKERS; k += 1) worker()

  try {
    for (let i = first; i <= last; i += 1) {
      while (!chunks.has(i)) {
        if (stopped || isClosed() || res.destroyed) return
        if (failed.has(i)) throw new Error(`分片 ${i} 拉取失败`)
        await sleep(20)
      }
      const buf = chunks.get(i)
      chunks.delete(i)
      cursor = i
      if (!res.write(buf)) {
        await new Promise((resolve) => {
          const done = () => {
            res.off('drain', done)
            res.off('close', done)
            resolve()
          }
          res.on('drain', done)
          res.on('close', done)
        })
        if (res.destroyed) return
      }
    }
    res.end()
  } finally {
    stopped = true
  }
}

module.exports = { MediaServer }
