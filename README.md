![mpv NAS Player banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpv NAS Player

[![Latest release](https://img.shields.io/github/v/release/XIONGPEILIN/mpvExtended-android?logo=github&label=GitHub)](https://github.com/XIONGPEILIN/mpvExtended-android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/XIONGPEILIN/mpvExtended-android/total?logo=github)](https://github.com/XIONGPEILIN/mpvExtended-android/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)](https://github.com/XIONGPEILIN/mpvExtended-android/releases/latest)

> **NAS 优化版重点 / NAS-first Android player**
> 基于 libmpv 与 mpv-android，重点优化在 Android 手机和平板上浏览、串流和管理 NAS 视频。保留原包名 `app.marlboroadvance.mpvex`，可从旧版原位升级并继续使用已有配置与播放记录。

## NAS 优化与本分支改动

| 范围 | 本项目的重点改动 |
| --- | --- |
| NAS 协议 | 原生浏览 SMB、FTP、WebDAV；保存多个连接、自动连接，并支持 HTTP/HTTPS 等网络流。 |
| 稳定串流 | 本地 HTTP 代理支持范围请求与拖动；处理连接中断、MP4 交错数据、代理资源释放及慢速网络切换。 |
| SMB 稳定性 | SMB 会话保活、掉线自动重连、共享名/根目录正确拆分，减少长时间播放或切集后的卡死。 |
| NAS 浏览与连播 | 根据网络目录自动生成播放列表，保持浏览器排序与播放器顺序一致；窄屏和平板布局自适应。 |
| 播放状态 | 为网络文件使用稳定媒体标识，保存进度、最近播放、音轨/字幕轨与视频设置，避免切集状态串到下一视频。 |
| 字幕与缩略图 | 支持 NAS 同目录字幕选择、外部/在线字幕以及受控并发的网络缩略图提取。 |
| 远程文件管理 | 支持 NAS 文件多选删除、播放中删除当前本地或远程视频，并正确关闭代理句柄、更新历史和播放列表。 |
| 中文与易用性 | 完整简体中文、本地与网络媒体搜索、Material 3 界面、手势、平板/横竖屏播放控制。 |
| 播放能力 | 硬件/软件解码、精确拖动、画中画、后台播放、倍速、AB 循环、逐帧、Anime4K 与 mpv 配置文件。 |

### v1.3.11 最新修复

- 修复从“最近播放”打开本地视频时，由 Application Context 启动播放器导致的崩溃。
- 点击“删除当前视频”时立即暂停，确认框取消后只在原本播放中的情况下恢复，防止等待确认时自动跳到下一集并删错文件。
- 拖动或点击进度条时不再强制暂停/恢复视频，仅执行定位，保持用户原有播放状态。
- 延续此前的本地/NAS 当前视频删除修复：删除期间抑制错误 EOF 跳集，网络删除时保持 mpv 核心存活，并在成功后可靠切换剩余播放项。

## Why mpv NAS Player?

mpv NAS Player is a fork of [mpv-android](https://github.com/mpv-android/mpv-android) built around libmpv. It keeps mpv's rendering and configuration flexibility while making a NAS library feel like a first-class Android media source rather than a collection of raw URLs.

- No advertisements or trackers
- Open source under the Apache-2.0 license
- Local and NAS playback in one library
- Customizable controls, gestures, rendering, subtitles, audio and playlists
- Architecture-specific and universal APKs

This project is actively developed. Please report reproducible problems in [Issues](https://github.com/XIONGPEILIN/mpvExtended-android/issues).

## Installation

Download the current stable APK from [GitHub Releases](https://github.com/XIONGPEILIN/mpvExtended-android/releases/latest). Most modern Android devices should use the `arm64-v8a` APK; use `universal` when the device architecture is unknown.

[![Download latest release](https://img.shields.io/badge/Download-Latest%20APK-2563EB?style=for-the-badge&logo=github)](https://github.com/XIONGPEILIN/mpvExtended-android/releases/latest)

The Android application ID remains `app.marlboroadvance.mpvex` for upgrade and data compatibility.

## Screenshots

<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/player.png" width="98%" alt="Player" />
</div>

<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/folderscreen.png" width="23.5%" alt="Folders" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/videoscreen.png" width="23.5%" alt="Videos" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/about.png" width="23.5%" alt="About" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/pip.png" width="23.5%" alt="Picture in picture" />
</div>

## Building

Requirements:

- JDK 17
- Android SDK 36 and recent build tools
- Git

Build the standard release variants:

```bash
./gradlew assembleDefaultRelease
```

APK outputs are written to `app/build/outputs/apk/default/release/` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and `universal`.

## Upstream

The canonical upstream is [mpv-android/mpv-android](https://github.com/mpv-android/mpv-android). The old `mpvEx` fork is not used as upstream.

```bash
git fetch upstream
git log --oneline HEAD..upstream/master
```

## Release process

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build and test the release APKs.
3. Commit and push the source changes.
4. Create a matching Git tag and GitHub Release.
5. Upload the architecture-specific and universal APKs to `XIONGPEILIN/mpvExtended-android`.

## Acknowledgments

- [mpv](https://mpv.io/) and [mpv-android](https://github.com/mpv-android/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next Player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)
