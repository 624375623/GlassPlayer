const { app, session } = require('electron')
const resources = require('../electron/api/resources')
const danmaku = require('../electron/api/danmaku')

async function main() {
  const proxyEnv = process.env.HTTPS_PROXY || process.env.HTTP_PROXY
  if (proxyEnv) {
    await session.defaultSession.setProxy({ proxyRules: proxyEnv, proxyBypassRules: '<local>' })
  }
  const kw = process.env.TEST_KW || '凡人修仙传'
  for (const id of ['mipan', 'gugeso', 'kkso', 'panpansou', 'pansoude']) {
    const t0 = Date.now()
    try {
      const list = await resources.searchOne(id, kw)
      const quark = list.filter((x) => x.type === 'quark')
      console.log(`RESULT [${id}] ${list.length} 条（夸克 ${quark.length}）${Date.now() - t0}ms · 例：${(list[0] || {}).note || '无'}`)
    } catch (e) {
      console.log(`RESULT [${id}] 失败：${e.message}`)
    }
  }
  try {
    const m = await danmaku.bili.search(kw)
    console.log(`RESULT [bili] ${m.length} 条 · ${m[0]?.title} / ${m[0]?.subtitle}`)
  } catch (e) {
    console.log('[bili] 失败：', e.message)
  }
  app.quit()
}

app.whenReady().then(main).catch((e) => {
  console.log('FATAL', e && e.message)
  app.quit()
})
