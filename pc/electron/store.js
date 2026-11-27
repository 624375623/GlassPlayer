'use strict'
const fs = require('fs')
const path = require('path')
const { app } = require('electron')

const DEFAULTS = {
  settings: {
    theme: 'light',
    defaultSpeed: 1,
    volume: 0.9,
    muted: false,
    resume: true,
    quarkCookie: null,
    quarkUserName: null,
    quarkAvatar: null,
    quarkVip: null,
    // stream = 转码 m3u8 流播（省流量、起播快） / download = 原画直链
    quarkPlayMode: 'stream',
    libraryFolders: [],
    gridLayout: true,
    sortMode: 0, // 0 更新 1 名称 2 大小 3 时长
    sortAscending: false,
    cloudGridLayout: true,
    cloudSortMode: 0,
    cloudSortAscending: false,
    danmakuEnabled: true,
    danmakuOpacity: 0.92,
    danmakuScale: 1,
    danmakuSpeed: 1,
    danmakuArea: 0.62,
    danmakuShowScroll: true,
    danmakuShowTop: true,
    danmakuShowBottom: true,
    danmakuBlockWords: '',
    danmakuBlockRegex: '',
    danmakuSource: 'bili',
    danmakuServerUrl: '',
    danmakuOffsetMs: 0,
    /** 单集最多采集多少条弹幕（0 = 不限），超出时沿时间轴等间隔抽样 */
    danmakuLimit: 3000,
    danmakuLastQuery: '',
    resourceLastQuery: '',
    resourceSource: 'mipan',
    subtitleScale: 1,
    subtitleDelayMs: 0,
    subtitleColor: '#ffffff',
    onlyVideoFolders: true,
    danmakuFontBold: true,
    danmakuStroke: true,
  },
  positions: {}, // key -> { pos, duration, at }
  /** key -> { speed, subtitlePath, danmaku: { source, id, title, subtitle }, at } 每部视频自己的设置 */
  videoPrefs: {},
  favorites: [], // keys
  cloudMeta: {}, // fid -> { fid, name, size, updatedAt, parentFid, parentName, durationMs, isDir }
  hiddenCloudFolders: [],
  recent: [], // 最近播放 keys（最多 300）
}

class Store {
  constructor() {
    this.file = path.join(app.getPath('userData'), 'glassplayer.json')
    this.data = JSON.parse(JSON.stringify(DEFAULTS))
    this.timer = null
    this.load()
  }

  load() {
    try {
      if (fs.existsSync(this.file)) {
        const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'))
        this.data = {
          ...this.data,
          ...raw,
          settings: { ...this.data.settings, ...(raw.settings || {}) },
        }
      }
    } catch (e) {
      console.error('[store] load failed', e.message)
    }
  }

  save() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => this.flush(), 250)
  }

  flush() {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true })
      const tmp = `${this.file}.tmp`
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 1), 'utf8')
      fs.renameSync(tmp, this.file)
    } catch (e) {
      console.error('[store] save failed', e.message)
    }
  }

  get(key) {
    return key ? this.data[key] : this.data
  }

  set(key, value) {
    this.data[key] = value
    this.save()
    return value
  }

  get settings() {
    return this.data.settings
  }

  patchSettings(patch) {
    this.data.settings = { ...this.data.settings, ...patch }
    this.save()
    return this.data.settings
  }

  /* ---------- 播放进度 ---------- */
  position(key) {
    return this.data.positions[key] || null
  }

  savePosition(key, pos, duration) {
    if (!key || !Number.isFinite(pos) || pos <= 0) return
    const prev = this.data.positions[key] || {}
    this.data.positions[key] = {
      pos: Math.round(pos),
      duration: Math.round(duration || prev.duration || 0),
      at: Date.now(),
    }
    const recent = this.data.recent.filter((k) => k !== key)
    recent.unshift(key)
    this.data.recent = recent.slice(0, 300)
    this.save()
  }

  clearPosition(key) {
    delete this.data.positions[key]
    this.data.recent = this.data.recent.filter((k) => k !== key)
    this.save()
  }

  resetPositions() {
    this.data.positions = {}
    this.data.recent = []
    this.save()
  }

  /* ---------- 每部视频的偏好（倍速 / 字幕 / 弹幕绑定） ---------- */
  videoPref(key) {
    return (key && this.data.videoPrefs[key]) || null
  }

  saveVideoPref(key, patch) {
    if (!key || !patch) return null
    const prev = this.data.videoPrefs[key] || {}
    const next = { ...prev, ...patch, at: Date.now() }
    // undefined 用来表达「清掉这一项」
    for (const k of Object.keys(next)) if (next[k] === undefined) delete next[k]
    this.data.videoPrefs[key] = next
    const entries = Object.entries(this.data.videoPrefs)
    if (entries.length > 800) {
      entries.sort((a, b) => (b[1].at || 0) - (a[1].at || 0))
      this.data.videoPrefs = Object.fromEntries(entries.slice(0, 800))
    }
    this.save()
    return next
  }

  /* ---------- 收藏 ---------- */
  isFavorite(key) {
    return this.data.favorites.includes(key)
  }

  toggleFavorite(key) {
    const has = this.isFavorite(key)
    this.data.favorites = has
      ? this.data.favorites.filter((k) => k !== key)
      : [...this.data.favorites, key]
    this.save()
    return !has
  }

  /* ---------- 网盘元信息 ---------- */
  cloudMeta(fid) {
    return this.data.cloudMeta[fid] || null
  }

  allCloudMeta() {
    return Object.values(this.data.cloudMeta)
  }

  saveCloudMeta(meta) {
    if (!meta || !meta.fid) return
    const prev = this.data.cloudMeta[meta.fid] || {}
    this.data.cloudMeta[meta.fid] = { ...prev, ...meta }
    const entries = Object.entries(this.data.cloudMeta)
    if (entries.length > 800) {
      entries.sort((a, b) => (b[1].updatedAt || 0) - (a[1].updatedAt || 0))
      this.data.cloudMeta = Object.fromEntries(entries.slice(0, 800))
    }
    this.save()
  }

  hiddenCloud() {
    return this.data.hiddenCloudFolders
  }

  toggleHiddenCloud(fid) {
    const has = this.data.hiddenCloudFolders.includes(fid)
    this.data.hiddenCloudFolders = has
      ? this.data.hiddenCloudFolders.filter((x) => x !== fid)
      : [...this.data.hiddenCloudFolders, fid]
    this.save()
    return !has
  }
}

module.exports = { Store }
