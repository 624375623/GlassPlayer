import { useState } from 'react'
import { api } from '../lib/api'
import { useApp } from '../lib/ctx'
import { Icon } from '../components/Icon'
import { Modal, Segmented, Slider, Switch } from '../components/ui'

export function SettingsPage() {
  const { snap, patch, refresh, toast, setTab } = useApp()
  const s = snap.settings
  const [showShortcuts, setShowShortcuts] = useState(false)

  const addFolder = async () => {
    const picked = await api.pickFolders()
    if (!picked.length) return
    await patch({ libraryFolders: [...new Set([...s.libraryFolders, ...picked])] })
    toast('已添加文件夹')
  }

  const removeFolder = async (folder: string) => {
    await patch({ libraryFolders: s.libraryFolders.filter((f) => f !== folder) })
  }

  return (
    <>
      <div className="page-head">
        <div className="titles">
          <h1>设置</h1>
          <div className="sub">播放、弹幕、字幕与本地媒体库</div>
        </div>
        <div className="grow" />
        <button className="btn" onClick={() => setShowShortcuts(true)}>
          <Icon name="info" size={15} /> 快捷键
        </button>
      </div>

      <div className="page-body">
        <div className="settings-grid">
          <div className="card-panel">
            <h3>账号</h3>
            <div className="form-row">
              <div className="label">
                <b>{s.quarkUserName || '未登录夸克网盘'}</b>
                <span>{s.quarkCookie ? '登录态已保存在本机，接口失效时重新扫码即可' : '扫码登录后才能浏览与播放网盘视频'}</span>
              </div>
              {s.quarkCookie ? (
                <button
                  className="btn danger"
                  onClick={async () => {
                    await api.quarkLogout()
                    await refresh()
                    toast('已退出登录')
                  }}
                >
                  退出
                </button>
              ) : (
                <button className="btn primary" onClick={() => setTab('cloud')}>
                  <Icon name="qr" size={15} /> 去登录
                </button>
              )}
            </div>
            <div className="form-row">
              <div className="label">
                <b>网盘播放方式</b>
                <span>
                  {s.quarkPlayMode === 'stream'
                    ? '转码流播：走官方 m3u8 播放节点，起播快、不吃下载通道限速'
                    : '原画直链：直接拉原文件，画质最高，但大文件可能被限速'}
                </span>
              </div>
              <Segmented
                value={s.quarkPlayMode}
                options={[
                  { value: 'stream', label: '转码流播' },
                  { value: 'download', label: '原画直链' },
                ]}
                onChange={(v) => patch({ quarkPlayMode: v as any })}
              />
            </div>
          </div>

          <div className="card-panel">
            <h3>播放</h3>
            <div className="form-row">
              <div className="label">
                <b>断点续播</b>
                <span>记住每个视频的进度，下次打开自动跳回</span>
              </div>
              <Switch on={s.resume} onChange={(v) => patch({ resume: v })} />
            </div>
            <div className="form-row">
              <div className="label">
                <b>默认倍速</b>
                <span>每次开始播放时使用</span>
              </div>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'flex-end', maxWidth: 268 }}>
                {[0.75, 1, 1.25, 1.5, 2].map((v) => (
                  <button key={v} className={`chip ${s.defaultSpeed === v ? 'active' : ''}`} onClick={() => patch({ defaultSpeed: v })}>
                    {v}x
                  </button>
                ))}
              </div>
            </div>
            <div className="form-row">
              <div className="label">
                <b>字幕字号</b>
                <span>播放器内也可以随时调整</span>
              </div>
              <Slider
                min={0.7}
                max={2}
                step={0.05}
                value={s.subtitleScale}
                onChange={(v) => patch({ subtitleScale: v })}
                format={(v) => v.toFixed(2)}
                suffix="x"
              />
            </div>
          </div>

          <div className="card-panel">
            <h3>外观</h3>
            <div className="form-row">
              <div className="label">
                <b>主题</b>
                <span>浅色玻璃 / 深色玻璃</span>
              </div>
              <Segmented
                value={s.theme}
                options={[
                  { value: 'light', label: '浅色' },
                  { value: 'dark', label: '深色' },
                ]}
                onChange={(v) => patch({ theme: v as any })}
              />
            </div>
            <div className="form-row">
              <div className="label">
                <b>本地库布局</b>
                <span>网格更像海报墙，列表更适合看文件信息</span>
              </div>
              <Segmented
                value={s.gridLayout ? 'grid' : 'list'}
                options={[
                  { value: 'grid', label: '网格' },
                  { value: 'list', label: '列表' },
                ]}
                onChange={(v) => patch({ gridLayout: v === 'grid' })}
              />
            </div>
          </div>

          <div className="card-panel">
            <h3>本地媒体库</h3>
            <div className="hint">GlassPlayer 只扫描你选择的文件夹，视频不会上传到任何服务器。</div>
            {s.libraryFolders.map((f) => (
              <div className="folder-row" key={f}>
                <Icon name="folder" size={16} />
                <span className="p" title={f}>
                  {f}
                </span>
                <button className="icon-btn" title="移除" onClick={() => removeFolder(f)}>
                  <Icon name="trash" size={15} />
                </button>
              </div>
            ))}
            <div style={{ padding: '12px 0' }}>
              <button className="btn" onClick={addFolder}>
                <Icon name="folderPlus" size={15} /> 添加文件夹
              </button>
            </div>
          </div>

          <div className="card-panel">
            <h3>弹幕</h3>
            <div className="form-row">
              <div className="label">
                <b>默认开启弹幕</b>
                <span>进入播放页后自动用片名搜索正片弹幕</span>
              </div>
              <Switch on={s.danmakuEnabled} onChange={(v) => patch({ danmakuEnabled: v })} />
            </div>
            <div className="form-row">
              <div className="label">
                <b>弹幕来源</b>
                <span>主源搜不到正片时会自动换另一个源；自建需兼容弹弹play v2</span>
              </div>
              <Segmented
                value={s.danmakuSource}
                options={[
                  { value: 'bili', label: 'B 站' },
                  { value: 'tencent', label: '腾讯' },
                  { value: 'server', label: '自建' },
                ]}
                onChange={(v) => patch({ danmakuSource: v as any })}
              />
            </div>
            {s.danmakuSource === 'server' && (
              <div className="form-row">
                <div className="label">
                  <b>服务器地址</b>
                  <span>例如 https://danmu.example.com</span>
                </div>
                <input
                  className="text-input"
                  style={{ width: 200 }}
                  value={s.danmakuServerUrl}
                  onChange={(e) => patch({ danmakuServerUrl: e.target.value })}
                  placeholder="https://"
                />
              </div>
            )}
            <div className="form-row">
              <div className="label">
                <b>不透明度</b>
              </div>
              <Slider min={0.15} max={1} step={0.01} value={s.danmakuOpacity} onChange={(v) => patch({ danmakuOpacity: v })} format={(v) => `${Math.round(v * 100)}`} suffix="%" />
            </div>
            <div className="form-row">
              <div className="label">
                <b>字号</b>
              </div>
              <Slider min={0.7} max={1.6} step={0.05} value={s.danmakuScale} onChange={(v) => patch({ danmakuScale: v })} format={(v) => v.toFixed(2)} suffix="x" />
            </div>
            <div className="form-row">
              <div className="label">
                <b>滚动速度</b>
              </div>
              <Slider min={0.5} max={2} step={0.05} value={s.danmakuSpeed} onChange={(v) => patch({ danmakuSpeed: v })} format={(v) => v.toFixed(2)} suffix="x" />
            </div>
            <div className="form-row">
              <div className="label">
                <b>显示区域</b>
                <span>弹幕占屏幕高度比例</span>
              </div>
              <Slider min={0.3} max={1} step={0.02} value={s.danmakuArea} onChange={(v) => patch({ danmakuArea: v })} format={(v) => `${Math.round(v * 100)}`} suffix="%" />
            </div>
            <div className="form-row">
              <div className="label">
                <b>弹幕数量上限</b>
                <span>单集最多采集条数，0 = 不限</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input
                  className="text-input"
                  style={{ width: 90, textAlign: 'right' }}
                  type="number"
                  min={0}
                  max={50000}
                  step={100}
                  value={s.danmakuLimit}
                  onChange={(e) => {
                    const raw = Number(e.target.value)
                    const next = Number.isFinite(raw) ? Math.max(0, Math.min(50000, Math.round(raw))) : 0
                    patch({ danmakuLimit: next })
                  }}
                />
                <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>条</span>
              </div>
            </div>
            <div className="form-row">
              <div className="label">
                <b>屏蔽词</b>
                <span>逗号 / 空格分隔，含这些词的弹幕不显示</span>
              </div>
              <input
                className="text-input"
                style={{ width: 180 }}
                value={s.danmakuBlockWords}
                onChange={(e) => patch({ danmakuBlockWords: e.target.value })}
                placeholder="例如：广告 前排"
              />
            </div>
            <div className="form-row">
              <div className="label">
                <b>正则屏蔽</b>
                <span>一行一条，非法表达式会被忽略</span>
              </div>
              <input
                className="text-input"
                style={{ width: 180 }}
                value={s.danmakuBlockRegex}
                onChange={(e) => patch({ danmakuBlockRegex: e.target.value })}
                placeholder="^\\d+$"
              />
            </div>
          </div>

          <div className="card-panel">
            <h3>数据</h3>
            <div className="form-row">
              <div className="label">
                <b>清空播放记录</b>
                <span>同时清除所有断点续播进度，收藏不受影响</span>
              </div>
              <button
                className="btn danger"
                onClick={async () => {
                  await api.resetPositions()
                  await refresh()
                  toast('播放记录已清空')
                }}
              >
                清空
              </button>
            </div>
            <div className="form-row">
              <div className="label">
                <b>打开数据目录</b>
                <span>设置、Cookie、播放进度都存在这里</span>
              </div>
              <button
                className="btn"
                onClick={async () => {
                  const info = await api.info()
                  api.showItem(info.userData + '\\glassplayer.json')
                }}
              >
                打开
              </button>
            </div>
          </div>

          <div className="card-panel">
            <h3>关于</h3>
            <div className="form-row">
              <div className="label">
                <b>GlassPlayer PC 1.0.6</b>
                <span>基于手机版 GlassPlayer 3.9.40 的接口与功能重写：夸克网盘扫码登录、五个资源站搜索、转存、弹幕、字幕、断点续播。</span>
              </div>
            </div>
            <div className="form-row">
              <div className="label">
                <b>隐私</b>
                <span>所有请求都在本机发起：夸克 Cookie 只存在本地配置文件，不经过任何第三方服务器。</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {showShortcuts && (
        <Modal title="播放器快捷键" onClose={() => setShowShortcuts(false)}>
          {[
            ['空格 / K', '播放 / 暂停'],
            ['← / →', '快退 / 快进 5 秒（按住 Shift 为 30 秒）'],
            ['↑ / ↓', '音量 +5% / -5%'],
            ['F / 双击画面', '全屏切换'],
            ['M', '静音'],
            ['D', '弹幕开关'],
            ['[ / ]', '减速 / 加速'],
            ['Esc', '退出播放'],
          ].map(([k, v]) => (
            <div className="form-row" key={k}>
              <div className="label">
                <b>{v}</b>
              </div>
              <span className="tag gray">{k}</span>
            </div>
          ))}
        </Modal>
      )}
    </>
  )
}
