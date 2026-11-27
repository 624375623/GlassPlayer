// 验证媒体代理的并行分片：上游每连接限速 128KB/s，8 路并发应快约 8 倍且字节完全一致
const http = require('http')
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { MediaServer } = require('../electron/mediaServer')

const SIZE = 16 * 1024 * 1024
const file = path.join(os.tmpdir(), 'gp-parallel-test.bin')
if (!fs.existsSync(file) || fs.statSync(file).size !== SIZE) {
  fs.writeFileSync(file, crypto.randomBytes(SIZE))
}
const expect = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const LIMIT = 128 * 1024

const upstream = http.createServer((req, res) => {
  const stat = fs.statSync(file)
  let start = 0
  let end = stat.size - 1
  const range = req.headers.range
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range)
    if (m && m[1]) start = parseInt(m[1], 10)
    if (m && m[2]) end = parseInt(m[2], 10)
  }
  res.writeHead(range ? 206 : 200, {
    'Content-Type': 'video/mp4',
    'Content-Length': end - start + 1,
    'Accept-Ranges': 'bytes',
    ...(range ? { 'Content-Range': `bytes ${start}-${end}/${stat.size}` } : {}),
  })
  const stream = fs.createReadStream(file, { start, end, highWaterMark: 32 * 1024 })
  stream.on('data', (chunk) => {
    stream.pause()
    res.write(chunk)
    setTimeout(() => stream.resume(), Math.max(1, Math.round((chunk.length / LIMIT) * 1000)))
  })
  stream.on('end', () => res.end())
  req.on('close', () => stream.destroy())
})

async function run() {
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r))
  const upPort = upstream.address().port
  const upUrl = `http://127.0.0.1:${upPort}/video.mp4`

  const t0 = Date.now()
  const single = Buffer.from(await (await fetch(upUrl)).arrayBuffer())
  const singleMs = Date.now() - t0

  const store = { settings: { quarkCookie: null } }
  const media = new MediaServer(store)
  await media.start()

  const t1 = Date.now()
  const res = await fetch(media.remote(upUrl))
  const buf = Buffer.from(await res.arrayBuffer())
  const parMs = Date.now() - t1
  const hash = crypto.createHash('sha256').update(buf).digest('hex')

  // Range 分片请求（模拟拖动）
  const t2 = Date.now()
  const r2 = await fetch(media.remote(upUrl), { headers: { Range: 'bytes=5242880-6291455' } })
  const b2 = Buffer.from(await r2.arrayBuffer())
  const sliceMs = Date.now() - t2
  const wantSlice = fs.readFileSync(file).subarray(5242880, 6291456)

  console.log('SINGLE', (SIZE / 1024 / 1024).toFixed(1) + 'MB', singleMs + 'ms', Math.round(SIZE / 1024 / singleMs * 1000) + 'KB/s')
  console.log('PARALLEL', (buf.length / 1024 / 1024).toFixed(1) + 'MB', parMs + 'ms', Math.round(buf.length / 1024 / parMs * 1000) + 'KB/s', 'status', res.status)
  console.log('HASH ok:', hash === expect, '| SPEEDUP', (singleMs / parMs).toFixed(1) + 'x')
  console.log('RANGE status', r2.status, 'len', b2.length, 'match', b2.equals(wantSlice), sliceMs + 'ms')
  media.stop()
  upstream.close()
  process.exit(0)
}
run().catch((e) => {
  console.log('FAIL', e)
  process.exit(1)
})
