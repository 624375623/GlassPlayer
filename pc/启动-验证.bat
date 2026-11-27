@echo off
cd /d "%~dp0"
title GlassPlayer 人工验证启动器

echo ==========================================================
echo    GlassPlayer PC    人工验证模式
echo ==========================================================
echo.

rem ---- 清理可能污染 Electron 启动的环境变量 ----
set "ELECTRON_RUN_AS_NODE="
set "ELECTRON_NO_ATTACH_CONSOLE="

rem ---- 定位 Node 运行环境 ----
set "NODE_HOME="
where node >nul 2>nul
if not errorlevel 1 goto node_ok

for /d %%D in ("%USERPROFILE%\.workbuddy\binaries\node\versions\*") do set "NODE_HOME=%%~fD"
if not defined NODE_HOME goto no_node
set "PATH=%NODE_HOME%;%PATH%"
echo [环境] 使用内置 Node: %NODE_HOME%

:node_ok
for /f "delims=" %%V in ('node -v') do echo [环境] Node %%V
call npm -v >nul 2>nul
if errorlevel 1 goto no_npm
echo.

rem ---- 依赖检查 ----
if not exist "node_modules\electron" (
  echo [!] 缺少依赖，正在安装，首次较慢请耐心等待...
  call npm install --registry=https://registry.npmmirror.com
  if errorlevel 1 goto fail
  echo.
)

rem ---- 界面产物是否需要重新构建 ----
node "scripts\should-build.js"
if errorlevel 1 (
  echo [构建] 正在重新打包界面...
  call npm run build
  if errorlevel 1 goto fail
  echo.
) else (
  echo [构建] 界面产物已是最新，跳过构建。
)

echo.
echo [启动] 正在打开播放器窗口...
echo        验证完成后直接关闭播放器窗口即可，本窗口会自动结束。
echo.
call npm start

echo.
echo [结束] 播放器已关闭。
exit /b 0

:no_node
echo.
echo [错误] 没有找到 Node.js 运行环境。
echo        请确认 WorkBuddy 安装完整，或自行安装 Node.js 18 以上版本。
goto pause_end

:no_npm
echo.
echo [错误] 找到了 node，但无法调用 npm。
goto pause_end

:fail
echo.
echo [错误] 启动失败，请把上面的报错内容截图反馈。
goto pause_end

:pause_end
echo.
pause
exit /b 1
