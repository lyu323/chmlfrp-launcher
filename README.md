# ChmlFrp 启动器 · Android 端 frpc 启动器

第三方本地启动器：只在手机本地运行 `frpc` 内核，配合 ChmlFrp 官方面板使用。
**本仓库不提供任何服务器、节点或隧道服务**，节点可用性、带宽、账号与签到规则均由官方决定。

> 非官方项目，与 ChmlFrp 官方无隶属关系。采用 [MIT](LICENSE) 许可。

**包名 `net.lyu323.chmlfrp`**（v3.2.0 起；更早版本使用不同的包名）。
改包名意味着**不能覆盖安装旧版**，会与旧版并存：建议卸载旧版，新版重新登录一次（令牌 / 配置只存本机私有目录，跨包名无法自动迁移）。

## 下载

[Releases](https://github.com/lyu323/chmlfrp-launcher/releases) · 当前版本 **v3.2.0**

| 文件 | 适用 |
| --- | --- |
| `ChmlFrpLauncher-v3.2.0-arm64.apk` | 推荐，绝大多数现代安卓手机（arm64-v8a） |
| `ChmlFrpLauncher-v3.2.0-allarch.apk` | arm64 / armeabi-v7a / x86_64 |
| `ChmlFrpLauncher-v3.2.0-source.zip` | 与本仓库一致的源码快照（MIT，零二进制、零密钥） |

两个架构 APK 内置同一套检查更新，都连接本仓库 Releases；同一签名证书，可互相覆盖安装。

## 检查更新（连接 GitHub Releases）

设置页「版本更新」或关于页「检查更新」→ 访问本仓库公开接口
`https://api.github.com/repos/lyu323/chmlfrp-launcher/releases/latest`：

- **只在点击时联网**，启动、后台、隧道运行期间不自动请求；不点不联网；
- 只 GET 版本信息（tag / 标题 / 更新说明 / 下载直链），**不上传**任何设备、账号、令牌或日志数据；不带任何令牌；
- **10 分钟本机缓存**：期间重复点不重复联网，结果弹窗标注「使用了 10 分钟内缓存」；可「↻ 强制刷新」；
- 结果弹窗可「复制 APK 下载直链」，设置页显示上次检查时间；
- 发现新版只给 Releases 页面与下载入口，**不自动下载、不静默安装**；已是最新显示「当前已是最新版本：3.2.0」；
- 关于页提供项目地址、GitHub Star、更新日志、**内联 MIT 许可全文弹窗**与另一个项目入口；长按版本号可复制构建信息。

## 同账号下的两个项目

| 仓库 | 地址 | 说明 |
| --- | --- | --- |
| `chmlfrp-launcher` | https://github.com/lyu323/chmlfrp-launcher | 本项目（检查更新连接这里） |
| `cpolar-launcher` | https://github.com/lyu323/cpolar-launcher | 同账号下的另一个项目（Cpolar 启动器，连接它自己的 Releases） |

两个项目各自独立维护、各自开源、包名不同（签名证书相同，可同时安装、互不覆盖），都带检查更新，但**各连各的仓库**，不互相混用。

## 主要能力

- 官方 OAuth 登录 / 粘贴令牌直登 / 多账号本机档案
- 隧道列表多选、一次启动多条、配置拉取与 INI 编辑
- 前台服务守护与网络恢复重连、通知栏启停重启、通知字段自定义
- 节点连通预检、内核能力与配置格式兼容检查、连接诊断
- 签到中心、设备与 ROM 适配说明、21 套主题、液态玻璃弹窗、隐藏后台
- 日志搜索 / 只看异常 / 脱敏分享 / 一键自检
- 「联系作者」直达 QQ 官方加好友页（`mqqopensdkapi` 通道，零联网、不写剪贴板）
- 检查更新 / 项目地址 / Star / 更新日志 / 开源许可

## 开源边界

**公开**：本 App 自己编写的 Java 源码、资源、构建脚本、文档（MIT）。

**不公开 / 不在仓库与源码 zip 中**：
- 签名密钥 `.jks` 与任何硬编码密码 —— `build.sh` 只从 `KS_PASS` / `KEY_PASS` 环境变量读取；
- 第三方 `libfrpc.so` 内核二进制 —— 不属于本仓库代码，请自备后放入 `FRPC_LIBS_DIR`；
- 任何访问令牌、账号密码、设备标识、个人节点地址或私有域名；示例节点一律 `node.example.com` 占位。

`CLIENT_ID` 是官方 OAuth 的公开参数，不是密钥。

## 隐私

配置、令牌 / authtoken、日志、隧道列表只存在本机应用私有目录，不上传任何服务器。
外部连接：官方 API 与节点、以及**仅手动触发**的 GitHub 更新检查（`api.github.com` 公开 Release 接口）与浏览器跳转 GitHub 页面。
App 内置的「联系作者」是 QQ 官方公开加好友短链（非凭证），点击只拉起 QQ，不读取设备或账号信息。
无广告 / 统计 / 崩溃上报 / 推送 SDK。


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
