---
kind: howto
---

# 安卓自用版

此子项目在手机本机运行原版 Peach 后端、前端和内置 CloudDrive2，非上游官方安卓发行版。

## 功能与范围

主界面使用 Peach 0.52.0 的原版网页资源，后端使用同版本 Python 源码、迁移和锁定依赖；Android 窗口只提供 WebView、文件选择、导出保存、全屏视频和额外的 CloudDrive2 管理入口。馆藏、详情、资料处理、字幕、下载、任务、配置等页面由原版路由提供，数据使用原版 ledger。

随包运行环境包含 Python 3.14.8、FFmpeg、OpenCV、人脸模型和独立 amane 桥。PRoot 在应用私有目录运行这些 Linux 文件，无需 Root。云盘资源接入原版远程资源管线；扫描、NFO、配套图片、字幕和按字节范围读取共用原版处理入口。整理操作复用原版官方目录核验，不把接口成功应答当作移动完成。

安卓拒绝读取 `/proc/stat` 时，任务监控通过系统 `CLOCK_BOOTTIME` 计算整数启动时间，并在进程内保持稳定，供原版进程身份与资源记录使用。后端继承手机时区；覆盖安装后会重建指向当前原生库位置的私有依赖链接。

原版首次设置与媒体设置的「选择文件夹」在安卓中打开本机选择入口：手机目录使用系统 Storage Access Framework 并保留用户授权，云盘目录使用内置 CloudDrive2。选择只回填表单，取消不写入配置；保存后仍使用原版库身份和 Windows 账本路径。手机目录通过 `DocumentsContract` 读取、定位、写入配套文件和移动，不能把授权 URI 转成未经授权的真实路径。存储提供方不支持的写入或移动会明确返回失败。

Android 3 使用独立包名 `video.peach.personal.full`，桌面名称为「Peach 完整版」，可与先前自用包共存；两者的数据与登录相互独立。运行新版前先从旧版通知停止服务，释放本机端口。后续覆盖更新需要保留本次构建所用签名。

Android 4 在载入原版页面前实测 WebView 的 HEVC Main/Main10 支持，并按 MP4 的 `hvc1`／`hev1` 标记和像素格式选择直读。只有当前 WebView 确认支持、音频兼容且时间戳正常的文件直接播放，其余保留原版转换流程，桌面判据不变。播放请求失败时显示实际 HTTP 状态；长按应用上方「CloudDrive2」可查看运行诊断、编码能力和最近一次播放请求错误。

默认面向 Android 8.0 以上 ARM64 手机。首次启动会在应用私有目录展开完整运行环境，建议预留 3 GB 空间。退出页面后服务继续运行，可从通知或 CloudDrive2 设置停止，不会开机自启。Android 的前台服务限制、WebView 编解码、第三方站点和设备后台策略仍需设备验证；桌面浏览器交互和硬件加速能力不能由源码随包推定可用。

## 准备本机核心

CloudDrive2 二进制、官方网页资源和生成 APK 不进入公开仓库。其使用、嵌入和分发遵循[官方许可](https://www.clouddrive2.com/eula.html)，Peach 的 AGPL 许可不覆盖这些文件。

从[官方下载页](https://www.clouddrive2.com/download.html)取得 `clouddrive-2-android-aarch64-1.1.3.tgz`，自行核对来源和适用许可。使用 Python 3.11 以上从仓库根目录执行：

```text
python android/prepare_core.py <官方核心归档路径> --sha256 17730d6e0aa2a83849aa1c9ab5bea4b165ddd06e715ba4d8c5efd2c096f16e21
```

摘要对应开发时从官方发布地址取得的归档。脚本校验摘要及归档路径，将原始文件放入已忽略的本机构建目录。上游重发同名文件时应重新核对来源，不忽略摘要差异。

## 构建

先准备完整运行环境。构建主机需要 Linux、Python 3.14、uv、Git 和 `dpkg-deb`；以下命令从仓库根目录运行，输出目录须为空。输入摘要见 `android/runtime-inputs.json`，Python 依赖沿用两个项目锁，模型沿用原版摘要：

```bash
PYTHONPATH=src python android/prepare_runtime.py --work /绝对路径/peach-android-build
```

运行包和原生依赖生成在已忽略的 `android/runtime-assets/` 与 `android/app/src/main/jniLibs/` 中，不提交归档或账号数据。再次准备源码时可用 `android/package_runtime.py` 的显式参数重新封装；首次构建使用上面的完整入口。

APK 构建需要 JDK 17、Android SDK 35 和 Build Tools 35.0.0。依赖由 Gradle 清单和锁文件固定。Android Studio 打开 `android/`，或在该目录运行 wrapper。

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

1. 打开应用，等待运行环境准备完成，进入原版 Peach。
2. 点应用上方的「CloudDrive2」，登录 CloudDrive 账号。首次登录是否同步云端网盘账号，由未默认勾选的选项决定；官方管理页也可添加网盘和创建 API 令牌。
3. 进入希望接入的云盘目录，点「将当前目录加入原版 Peach 媒体库」。令牌进入原版私有凭据存储，配置和账本不保存密码或令牌。
4. 返回原版 Peach，在媒体库和数据管理中扫描、处理自己的媒体。视频按需读取，不预先下载整部影片。
5. 在通知或 CloudDrive2 设置停止服务。覆盖安装保留原 CloudDrive2 数据目录和账号；清除应用数据或卸载会移除本机数据。

Peach 使用本机 `8900` 端口，CloudDrive2 使用 `19798` 端口。端口已有监听时报告冲突，不接管或终止其他服务。核心自身的监听、WebDAV 和账号权限由官方管理页维护；客户端只连接 `127.0.0.1`，不配置路由器或端口转发。

## 验证边界

构建、签名、安装、完整后端启动、页面加载和真实账号分别验证。原版前端文件通过 Git 对象和归档逐文件核对；这一点不替代业务和设备验收。账号及验证码不进入测试或公开日志。主程序正式测试入口仍为 `scripts/test.sh` / `scripts/test.ps1`；无桌面测试环境使用 pystray 官方的 `PYSTRAY_BACKEND=dummy`。
