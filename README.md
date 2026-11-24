# GlassPlayer

一个 **Android 原生视频播放器**：本地媒体库 + 网盘播放 + 外挂字幕库 + 弹幕 + DLNA 投屏。

播放内核使用 [Media3 / ExoPlayer](https://github.com/androidx/media)，界面使用 Kotlin + Jetpack Compose，玻璃态控件来自开源库 [Glasmio](https://github.com/NEORUAA/Glasmio)。

![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-1.11.1-FF6F00)
![License](https://img.shields.io/badge/license-Apache--2.0-blue)

- 包名：`com.glassplayer`
- 最低系统：Android 13（API 33）
- 编译 / 目标：API 37
- 当前版本：1.4.50（versionCode 125）
- 仓库地址：https://github.com/624375623/GlassPlayer

## 功能

### 媒体库
- MediaStore 扫描本机视频，按文件夹分组，支持全部 / 文件夹 / 收藏
- 文件名与路径搜索、按最近添加 / 名称 / 时长 / 大小排序
- 「继续观看」、视频详情、缩略图缓存
- 播放进度、最近播放、字幕记忆按视频独立保存

### 播放
- Media3 播放内核，支持 MP4 / MKV / WebM / TS / FLV / OGG / 3GP 等常见容器
- 硬解优先，失败可回退；音轨切换、画面比例切换、A-B 循环
- 0.25×–3× 倍速、±10 秒、进度拖动、上一集 / 下一集
- 左侧亮度、右侧音量、横滑快进 / 快退手势
- 控件锁定、画中画、后台播放、媒体通知、锁屏控制
- 网盘 HLS 流播与逐帧步进（受分片粒度限制）

### 字幕
- 自研 SRT / VTT / ASS / SSA 解析，字号、位置、透明度、延迟可调
- 字幕位置支持负值，可继续向下压到画面底部之外
- 每个视频单独记忆字幕文件；关闭字幕后不会在下次打开时反复弹出
- 支持通过 SAF 导入**多个字幕文件夹**，递归扫描、持久授权
- 两级目录：先选文件夹，再选字幕文件；所有视频通用
- 打开字幕面板自动展开到当前字幕所在文件夹
- 按集号自动关联上下集：切换上一集 / 下一集时自动加载同系列字幕，并写回该集记忆

### 网盘
- 夸克网盘扫码登录，Cookie 仅保存在本机
- 网盘目录浏览、分页、文件搜索、播放记录
- 网盘视频直链解析、并行分片流播、磁盘缓存
- 分享链接预览与转存
- 网盘播放进度与本地媒体库统一记录

### 弹幕
- B 站、腾讯视频、爱奇艺、优酷弹幕源
- 按文件名 / 集号自动匹配，支持手动搜索
- 本地弹幕文件导入、弹幕合并、屏蔽词、透明度、速度、字号
- 可配置自建弹弹play v2 兼容弹幕服务器

### 投屏
- DLNA / UPnP 设备发现与控制
- 本地 HTTP 媒体代理，网盘视频由手机侧带鉴权拉流，电视不需要登录网盘
- 支持进度、暂停、音量、停止等控制

### 界面与系统
- Glasmio 液态玻璃主题，深色 / 浅色 / 跟随系统
- 底部导航弹簧回弹，切换页面不跳变
- 沉浸式全屏、横竖屏、画中画、屏幕常亮

## 下载

- GitHub Releases：https://github.com/624375623/GlassPlayer/releases/latest
- 当前提供的 `GlassPlayer-1.4.50-release.apk` 使用 debug 证书签名，只用于直接侧载体验；正式分发请按下面的构建说明配置自己的签名。

## 构建

环境要求：

- JDK 17 或更高（本项目在 JDK 21 上验证）
- Android SDK Platform 37
- Build Tools 37.0.0
- Gradle 9.8.2（仓库自带 wrapper）

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

国内网络下 `settings.gradle.kts` 已把阿里云 Maven 镜像放在前面；首次构建需要下载依赖。

说明：

- `app/libs/media3-exoplayer-hls-1.11.1.aar` 是官方 Media3 HLS 模块的本地副本，保证离线构建可用；也可以删掉它，改成 `implementation("androidx.media3:media3-exoplayer-hls:1.11.1")`。
- Release 构建默认使用 debug 证书签名，只是为了方便侧载体验。**正式分发请配置你自己的 signingConfig**，不要用 debug 证书发布。
- 没有内置任何账号、Cookie、API Key；网盘登录态只存在设备本地。

## 工程结构

```text
GlassPlayer/
├─ app/
│  ├─ build.gradle.kts
│  ├─ proguard-rules.pro
│  ├─ libs/media3-exoplayer-hls-1.11.1.aar
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ res/
│     └─ java/com/glassplayer/
│        ├─ MainActivity.kt              # 媒体库入口
│        ├─ GlassPlayerApp.kt
│        ├─ cast/                        # DLNA 发现、控制、本地媒体代理
│        ├─ danmaku/                     # 各平台弹幕源、解析、合并、渲染
│        ├─ data/                        # MediaStore、网盘 API、设置、字幕扫描
│        ├─ player/                      # 播放 Service、播放页、手势、字幕
│        └─ ui/                          # 媒体库、网盘页、玻璃组件、弹窗
├─ gradle/wrapper/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ CHANGELOG.md
├─ LICENSE
└─ NOTICE
```

## 格式兼容

| 容器 | 支持 | 说明 |
| --- | --- | --- |
| MP4 / MOV / M4V | ✅ | 硬解覆盖最好 |
| MKV | ✅ | 多音轨可切换，字幕建议外挂 |
| WebM | ✅ | VP8 / VP9 / AV1 |
| TS / M2TS | ✅ | 电视录制流常见 |
| FLV | ✅ | ExoPlayer 内置解复用 |
| OGG / OGV | ✅ | Vorbis / Theora 视设备 |
| 3GP | ✅ | |
| AVI / RMVB / WMV | ❌ | Media3 无对应解复用器，需要接 ffmpeg 扩展 |

视频编码 H.264 / HEVC / VP8 / VP9 / AV1 走 MediaCodec 硬解；音频 AAC / MP3 / Opus / Vorbis / FLAC 普遍可播，AC-3 / E-AC-3 / DTS 取决于设备解码器。10bit / 杜比视界 / HDR 的实际表现由设备能力决定。

## 已知限制

- 网盘、弹幕、资源搜索使用的是第三方非官方接口，可能随服务端调整而失效；请遵守对应平台的服务条款。
- 网盘登录需要扫码，Cookie 只保存在本机，不会上传。
- HLS 流播的逐帧步进受分片粒度限制，部分片源只能 0.5 秒步进。
- 字幕自动关联依赖视频标题与字幕文件名里的集号；没有集号时需要手动选择。
- 当前只在有限机型上验证过，不同设备的解码器、HDR、杜比能力差异较大。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)；请不要在 Issue 里贴账号、Cookie、Token 或完整直链。

## 第三方与致谢

- [AndroidX Media3](https://github.com/androidx/media) — Apache-2.0
- [Glasmio](https://github.com/NEORUAA/Glasmio) — Apache-2.0
- [Jetpack Compose](https://developer.android.com/compose) / AndroidX — Apache-2.0
- 播放器交互参考了 VLC、mpv、MX Player、Video.js / Plyr 的公开设计与文档，未复制其代码

完整第三方声明见 [NOTICE](NOTICE)。

## 许可证

本项目使用 [Apache License 2.0](LICENSE) 开源。

> 本项目仅供学习与个人使用。请勿用于侵犯版权、绕过付费或违反第三方平台服务条款的用途。项目与夸克、哔哩哔哩、腾讯视频、爱奇艺、优酷等公司无关联。
