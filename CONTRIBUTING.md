# 参与贡献

感谢你愿意改进 GlassPlayer。

## 提交 Issue

- Bug 请写清机型、系统版本、应用版本、复现步骤、期望结果与实际结果。
- 播放问题请附上视频容器 / 编码 / 分辨率信息；不要上传有版权的完整视频。
- **不要**在 Issue 里贴账号、Cookie、Token、网盘直链、二维码截图或其他凭据。
- 功能建议请说明使用场景，而不只是「加一个功能」。

## 提交 Pull Request

1. Fork 仓库，从 `main` 建分支。
2. 保持改动聚焦：一个 PR 只做一件事，避免顺手重构无关代码。
3. 遵循现有代码风格：Kotlin、4 空格缩进、Compose 状态尽量下沉、UI 文案用中文。
4. 提交前至少跑通：

   ```bash
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:assembleDebug
   ```

5. 如果改了播放、字幕、网盘、投屏等核心路径，请在 PR 描述里写清真机验证的机型与步骤。
6. 新增第三方依赖前请先开 Issue 讨论，并确认许可证与 Apache-2.0 兼容。

## 安全与隐私

- 不要把任何真实账号、Cookie、Token、密钥、签名文件提交到仓库。
- 不要在日志里打印完整直链或鉴权头。
- 发现安全问题请按 [SECURITY.md](SECURITY.md) 私下报告，不要直接开公开 Issue。

## 许可证

提交代码即表示你同意以本项目的 Apache License 2.0 许可证授权你的贡献。
