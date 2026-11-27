// 纯 Node 生成 512x512 图标（渐变圆角方块 + 白色播放三角），无需任何图形依赖
const fs = require('fs'), zlib = require('zlib'), path = require('path')
const W = 512, H = 512
const px = Buffer.alloc(W * H * 4)

function mix(a, b, t) { return a + (b - a) * t }
function inRoundRect(x, y, r) {
  const m = 0
  const x0 = m, y0 = m, x1 = W - m, y1 = H - m
  if (x < x0 + r && y < y0 + r) return (x - (x0 + r)) ** 2 + (y - (y0 + r)) ** 2 <= r * r
  if (x > x1 - r && y < y0 + r) return (x - (x1 - r)) ** 2 + (y - (y0 + r)) ** 2 <= r * r
  if (x < x0 + r && y > y1 - r) return (x - (x0 + r)) ** 2 + (y - (y1 - r)) ** 2 <= r * r
  if (x > x1 - r && y > y1 - r) return (x - (x1 - r)) ** 2 + (y - (y1 - r)) ** 2 <= r * r
  return x >= x0 && x <= x1 && y >= y0 && y <= y1
}
// 播放三角（圆角由抗锯齿近似）
function inTriangle(x, y) {
  const cx = 214, cy = 256, size = 150
  const ax = cx, ay = cy - size, bx = cx, by = cy + size, ex = cx + size * 1.5, ey = cy
  const sign = (px1, py1, px2, py2, px3, py3) => (px1 - px3) * (py2 - py3) - (px2 - px3) * (py1 - py3)
  const d1 = sign(x, y, ax, ay, bx, by), d2 = sign(x, y, bx, by, ex, ey), d3 = sign(x, y, ex, ey, ax, ay)
  const neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0
  return !(neg && pos)
}
for (let y = 0; y < H; y++) {
  for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4
    const t = (x / W) * 0.45 + (y / H) * 0.55
    let r = mix(85, 155, t), g = mix(102, 107, t), b = mix(245, 255, t)
    // 柔和高光
    const hl = Math.max(0, 1 - Math.hypot(x - W * 0.28, y - H * 0.2) / (W * 0.72))
    r = Math.min(255, r + hl * 46); g = Math.min(255, g + hl * 46); b = Math.min(255, b + hl * 30)
    let a = inRoundRect(x + 0.5, y + 0.5, 110) ? 255 : 0
    if (a) {
      if (inTriangle(x, y)) { r = 255; g = 255; b = 255 }
      // 边缘抗锯齿
      const edge = !inRoundRect(x + 1.5, y + 1.5, 110) || !inRoundRect(x - 1.5, y - 1.5, 110) || !inRoundRect(x + 1.5, y - 1.5, 110) || !inRoundRect(x - 1.5, y + 1.5, 110)
      if (edge) a = 170
    }
    px[i] = Math.round(r); px[i + 1] = Math.round(g); px[i + 2] = Math.round(b); px[i + 3] = a
  }
}
const raw = Buffer.alloc((W * 4 + 1) * H)
for (let y = 0; y < H; y++) {
  raw[y * (W * 4 + 1)] = 0
  px.copy(raw, y * (W * 4 + 1) + 1, y * W * 4, (y + 1) * W * 4)
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const t = Buffer.from(type, 'ascii')
  const crcTable = []
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0 }
  let crc = 0xffffffff
  const body = Buffer.concat([t, data])
  for (const byte of body) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8)
  const c = Buffer.alloc(4); c.writeUInt32BE((crc ^ 0xffffffff) >>> 0)
  return Buffer.concat([len, body, c])
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
])
fs.writeFileSync(path.join(__dirname, '..', 'build', 'icon.png'), png)
console.log('icon.png', png.length, 'bytes')
