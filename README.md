# ChmlFrp 启动器 · Android 端 frpc 启动器

第三方本地启动器：只在手机本地运行 `frpc` 内核，配合 ChmlFrp 官方面板使用。
**本仓库不提供任何服务器、节点或隧道服务**，节点可用性、带宽、账号与签到规则均由官方决定。

> 非官方项目，与 ChmlFrp 官方无隶属关系。采用 [MIT](LICENSE) 许可。

**包名 `net.lyu323.chmlfrp` 保持不变**：v3.3.0 可**直接覆盖安装 v3.2.0**，令牌、配置、主题与隧道缓存全部保留。

## 下载

[Releases](https://github.com/lyu323/chmlfrp-launcher/releases) · 当前版本 **v3.3.0**

| 文件 | 适用 |
| --- | --- |
| `ChmlFrpLauncher-v3.3.0-arm64.apk` | 推荐，绝大多数现代安卓手机（arm64-v8a） |
| `ChmlFrpLauncher-v3.3.0-allarch.apk` | arm64 / armeabi-v7a / x86_64 |
| `ChmlFrpLauncher-v3.3.0-source.zip` | 与本仓库一致的源码快照（MIT，零二进制、零密钥） |

两个架构 APK 内置同一套检查更新，都连接本仓库 Releases；同一签名证书，可互相覆盖安装。

## 检查更新 · 本地下载 · 更新日志（v3.3.0）

**启动后自动检查一次**：App 启动几秒后访问本仓库公开接口
`https://api.github.com/repos/lyu323/chmlfrp-launcher/releases/latest`：

- **有新版本才弹窗**，没有就完全静默（不 Toast、不打扰）；
- **不是强制更新**：同一版本只提醒一次，6 小时内重复启动不重复联网；
- **设置里可关闭**「启动后自动检查更新」，关掉后只有手动点「检查更新」才联网；
- 手动检查带 **10 分钟本机缓存**，可「↻ 强制刷新」忽略缓存；
- 只 GET 版本信息（tag / 标题 / 更新说明 / 下载直链），**不上传**任何设备、账号、令牌 / authtoken 或日志；不带任何令牌。

**App 内本地下载（不跳浏览器）**：更新弹窗点「⬇ 本地下载」→ 直接从 GitHub Release 资产把 APK 下载到 App 私有目录（带进度条），完成后**调起系统安装界面，是否安装由你确认**。不自动下载、不静默安装、不绕过系统签名校验；下载地址只接受 `github.com` 及其资产 CDN，重定向后的域名仍会再校验一次，并校验文件长度与 ZIP 魔数。下载失败可回退「打开 Releases 下载页」或「复制下载直链」。

**更新日志**：弹窗里的 Release 说明会自动整理成好读的文字（去掉 Markdown 标记）；点「查看更新日志」直接打开本仓库 [CHANGELOG.md](https://github.com/lyu323/chmlfrp-launcher/blob/main/CHANGELOG.md) 看每一版更新了什么，也可在关于页 / 设置页进入。


## 3D 动态启动特效（可关闭）

v3.3.0 新增纯 Canvas 手写、零第三方依赖的启动界面：透视投影的旋转线框二十面体（三层描边辉光）、外层反向立方体、三条倾斜光环、60 个环绕粒子、中心光晕、扫描光带、应用名与呼吸进度条，约 2.3 秒后淡出。

**设置 → 版本更新 → 「3D 动态启动特效」可关闭**，关闭后启动更快、更省电；特效加载失败也不影响主流程。

> 免责声明版本已升级，**首次打开本版会重新弹一次**免责声明，点同意即可（本机配置不受影响）。


## 同账号下的两个项目

| 仓库 | 地址 | 说明 |
| --- | --- | --- |
| `chmlfrp-launcher` | https://github.com/lyu323/chmlfrp-launcher | 本项目（检查更新 / 本地下载连接这里） |
| `cpolar-launcher` | https://github.com/lyu323/cpolar-launcher | 同账号下的另一个项目（Cpolar 启动器，连接它自己的 Releases） |

两个项目各自独立维护、各自开源、包名不同（签名证书相同，可同时安装、互不覆盖），都带检查更新与本地下载，但**各连各的仓库**，不互相混用。

## 主要能力

- 官方 OAuth 登录 / 粘贴令牌直登 / 多账号本机档案
- 隧道列表多选、一次启动多条、配置拉取与 INI 编辑
- 前台服务守护与网络恢复重连、通知栏启停重启、通知字段自定义
- 节点连通预检、内核能力与配置格式兼容检查、连接诊断
- 签到中心、设备与 ROM 适配说明、21 套主题、液态玻璃弹窗、隐藏后台
- 日志搜索 / 只看异常 / 脱敏分享 / 一键自检
- 「联系作者」直达 QQ 官方加好友页（`mqqopensdkapi` 通道，零联网、不写剪贴板）
- 检查更新 / 本地下载 / 项目地址 / Star / 更新日志 / 开源许可

## 开源边界

**公开**：本 App 自己编写的 Java 源码、资源、构建脚本、文档（MIT）。

**不公开 / 不在仓库与源码 zip 中**：
- 签名密钥 `.jks` 与任何硬编码密码 —— `build.sh` 只从 `KS_PASS` / `KEY_PASS` 环境变量读取；
- 第三方 `libfrpc.so` 内核二进制 —— 不属于本仓库代码，请自备后放入 `FRPC_LIBS_DIR`；
- 任何访问令牌、账号密码、设备标识、个人节点地址或私有域名；示例节点一律 `node.example.com` 占位。

`CLIENT_ID` 是官方 OAuth 的公开参数，不是密钥。

## 隐私

配置、令牌 / authtoken、日志、隧道列表只存在本机应用私有目录，不上传任何服务器。
外部连接：官方 API 与节点、`api.github.com`（启动自动检查一次 + 手动检查）、浏览器跳转 `github.com`，以及**仅在你点「本地下载」时**的 GitHub 资产 CDN。
App 内置的「联系作者」是 QQ 官方公开加好友短链（非凭证），点击只拉起 QQ。
无广告 / 统计 / 崩溃上报 / 推送 SDK。下载与安装均需你手动触发和确认。


## 从源码构建

仓库**只含 Java 源码与资源**，不含第三方内核二进制、Android SDK 与签名密钥。

```bash
export JH=/path/to/jdk17
export BT=/path/to/android-build-tools
export AJ=/path/to/android-35/android.jar
export FRPC_LIBS_DIR=/path/to/jniLibs      # 下含 arm64-v8a / armeabi-v7a / x86_64
export OUT_DIR=./out
# 可选签名（不设置则输出未签名 APK，仅供本地调试）
export KS_FILE=/path/to/your.jks KS_PASS='密码' KEY_PASS='密码'
bash build.sh
```

## 反馈与许可

[Issues](https://github.com/lyu323/chmlfrp-launcher/issues) 或 App 内「✉ 联系作者」。[MIT](LICENSE) · 第三方内核二进制不在本许可范围内。

项目主页、下载与反馈入口统一使用 GitHub 地址：https://github.com/lyu323/chmlfrp-launcher
