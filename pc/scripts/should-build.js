// 判断前端产物 dist 是否落后于源码 src
// 退出码: 1 = 需要重新构建, 0 = 无需构建
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const srcDir = path.join(root, 'src');
const distIndex = path.join(root, 'dist', 'index.html');

function newestMtime(dir, acc) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    let st;
    try {
      st = fs.statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) acc = newestMtime(p, acc);
    else if (st.mtimeMs > acc) acc = st.mtimeMs;
  }
  return acc;
}

try {
  if (!fs.existsSync(distIndex)) {
    console.log('[check] dist/index.html 不存在，需要构建');
    process.exit(1);
  }
  if (!fs.existsSync(srcDir)) process.exit(0);

  const srcNewest = newestMtime(srcDir, 0);
  const distTime = fs.statSync(distIndex).mtimeMs;

  if (srcNewest > distTime) {
    const gap = Math.round((srcNewest - distTime) / 1000);
    console.log(`[check] 源码比产物新 ${gap}s，需要构建`);
    process.exit(1);
  }
  process.exit(0);
} catch (e) {
  console.log('[check] 检查失败，按需要构建处理: ' + e.message);
  process.exit(1);
}
