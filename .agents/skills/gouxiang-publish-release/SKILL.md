---
name: gouxiang-publish-release
description: 为 GouXiang Android 应用构建签名 APK 并发布 GitHub Release。用户要求给狗箱打包发版、发布新版本、上传 APK 到 Release，或演练该发布流程时使用；不用于发布 AIAvatar-SDK 或 Maven 库。
---

# 发布 GouXiang

在 `/home/neethu/projects/GouXiang` 执行。先检查仓库、现有 Release 和用户指定的版本或演练要求。不要把 AIAvatar-SDK 的 Maven 发布步骤套用到本项目。

正式发布前，根据上一个 tag 到当前 HEAD 的变化撰写面向用户的中文发布说明，保存为仓库外的 UTF-8 文件。按实际变更概括新功能和修复，说明安装包需要用户配置自己的 AI 服务密钥；不要罗列原始提交、泄露密钥或虚构功能。必要时说明不同签名的调试包不能直接覆盖安装，提醒先备份数据。

```bash
# 默认将当前 versionName 最后一段数字加 1，并将 versionCode 加 1
NOTES_FILE=/tmp/gouxiang-release-notes.md python3 .agents/skills/gouxiang-publish-release/scripts/publish_release.py
# 指定版本
NOTES_FILE=/tmp/gouxiang-release-notes.md python3 .agents/skills/gouxiang-publish-release/scripts/publish_release.py 1.2
# 只检查并显示计划，不改版本、不提交、不推送、不发布
python3 .agents/skills/gouxiang-publish-release/scripts/publish_release.py --dry-run
```

脚本检查工作区、版本、远端和凭据，更新版本，运行 `:app:assembleRelease` 和 `:app:testDebugUnitTest`，签名并校验 APK，再提交、打 tag、推送，创建 GitHub Release 草稿，上传并校验附件后公开。遵守用户指定版本；若版本格式不适合自动递增，则指定版本，不要猜测。上传前确保 Release 构建不内置本机服务密钥。发布完成后报告 Release 与 APK 链接、版本、测试结果及重要安装限制。

## 凭据和签名

- GitHub token 从仓库根目录被忽略的 `secrets.properties` 的 `GITHUB_TOKEN` 读取；不要打印、提交或放进 APK。
- 发布密钥及密码位于 `~/.config/gouxiang/release-signing/`，必须复用已有密钥。`v1.0` 证书 SHA-256 为 `fc01bf770afe15cd449d2c4891509c64b4535e1b58974771adf58bf7d1c51beb`。密钥丢失或证书不一致时停止，不要自动创建新密钥：不同签名无法覆盖升级。
- `app/build.gradle.kts` 的 Release 服务密钥必须保持空值，Debug 可使用本地配置。应验证生成的配置和签名 APK，而不是只检查源码。

## 失败恢复

脚本发生错误时先核对实际 Git 工作区、远端分支、tag、Release 草稿和附件，再从已完成的阶段恢复。标签已存在不代表可以覆盖或删除；禁止强推、自动删 tag、覆盖已发布附件，或通过重置工作区丢弃用户改动。若 Release 草稿已创建，确认它对应的 tag 和构建后再补传或发布；保持原版本号，避免失败重试时意外再次递增。
