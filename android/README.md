---
kind: howto
---

# 安卓自用实验版

这是独立的安卓原生子项目，使用本机 CloudDrive2 和公开 gRPC API，非上游官方安卓发行版。

## 功能与范围

首版包含本机核心前台服务、CloudDrive 账号或 API 令牌登录、官方云盘管理页、目录浏览、当前列表筛选、独立媒体库、系统播放器断点播放和外部播放器入口。密码不保存，服务令牌通过 Android Keystore 加密。媒体库保存在应用私有 SQLite 中，不读取或修改桌面 ledger。

Python 刮削、NFO 导入、封面下载、字幕管理、桌面同步及原版全部功能尚未移植。系统播放器的编码能力取决于设备；外部播放器进度不会自动写回。网盘路径变化后须重新加入媒体库。

默认面向 Android 8.0 以上 ARM64 手机。核心退出页面后继续运行，可从通知或设置停止，不会开机自启。

## 准备本机核心

CloudDrive2 二进制、官方网页资源和生成 APK 不进入公开仓库。其使用、嵌入和分发遵循[官方许可](https://www.clouddrive2.com/eula.html)，Peach 的 AGPL 许可不覆盖这些文件。

从[官方下载页](https://www.clouddrive2.com/download.html)取得 `clouddrive-2-android-aarch64-1.1.3.tgz`，自行核对来源和适用许可。使用 Python 3.11 以上从仓库根目录执行：

```text
python android/prepare_core.py <官方核心归档路径> --sha256 17730d6e0aa2a83849aa1c9ab5bea4b165ddd06e715ba4d8c5efd2c096f16e21
```

摘要对应开发时从官方发布地址取得的归档。脚本校验摘要及归档路径，将原始文件放入已忽略的本机构建目录。上游重发同名文件时应重新核对来源，不忽略摘要差异。

## 构建

需要 JDK 17、Android SDK 35 和 Build Tools 35.0.0。依赖由 Gradle 清单和锁文件固定。Android Studio 打开 `android/`，或在该目录运行 wrapper。

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

输出为 `android/app/build/outputs/apk/debug/app-debug.apk`，使用本机调试签名，不需要 Root 或电脑常驻服务。

## 首次使用

1. 点「启动核心」，等待「本机服务运行中」。通知提供服务状态和停止入口。
2. 点「登录账号」。首次登录时是否同步云端网盘账号，由未默认勾选的选项决定。其他登录方式可在「云盘管理」使用官方流程，再创建 API 令牌连接。
3. 在「云盘管理」添加自己的网盘，回「浏览云盘」刷新。
4. 点视频播放或加入媒体库。视频按需读取，不预先下载整部影片。
5. 在设置或通知里停止核心。清除 Peach 令牌不会删除核心中的网盘账号。

核心使用 `19798` 端口。端口已有监听时报告冲突，不接管或终止其他服务。核心自身的监听、WebDAV 和账号权限由官方管理页维护；客户端只连接 `127.0.0.1`，不配置路由器或端口转发。

## 验证边界

构建、签名、安装界面、核心启动与真实账号分别验证。构建通过不能证明真实网盘、后台保活和设备解码通过。账号及验证码不进入测试或公开日志。主程序正式测试入口仍为 `scripts/test.sh` / `scripts/test.ps1`。
