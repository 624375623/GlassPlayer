// 用法：node scripts/shot.js <tab> <out.png> [jsFile] [waitMs]
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')

const [, , tab, out, jsFile, wait] = process.argv
const root = path.join(__dirname, '..')
const env = { ...process.env, GP_SHOT: out, GP_SHOT_TAB: tab, GP_SHOT_DELAY: '4500' }
delete env.ELECTRON_RUN_AS_NODE
if (jsFile) {
  env.GP_SHOT_JS_FILE = jsFile
  env.GP_SHOT_JS_WAIT = wait || '9000'
}
const child = spawn(path.join(root, 'node_modules', 'electron', 'dist', 'electron.exe'), ['.', ...(process.env.GP_USER_DATA ? [`--user-data-dir=${process.env.GP_USER_DATA}`] : [])], {
  cwd: root,
  env,
  stdio: 'inherit',
})
child.on('exit', (code) => {
  console.log('shot exit', code, fs.existsSync(out) ? `ok ${out}` : 'missing')
})
