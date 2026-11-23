# ChmlFrp 启动器 · Android 端 frpc 启动器

第三方本地启动器：只在手机本地运行 `frpc` 内核，配合 ChmlFrp 官方面板使用。
**本仓库不提供任何服务器、节点或隧道服务**，节点可用性、带宽、账号与签到规则均由官方决定。

> 这是**非官方**项目，与 ChmlFrp 官方无隶属关系。采用 [MIT](LICENSE) 许可。

## 下载

到 [Releases](https://github.com/lyu323/chmlfrp-launcher/releases) 下载，当前版本 **v3.1.0**：

| 文件 | 适用 |
| --- | --- |
| `ChmlFrpLauncher-v3.1.0-arm64.apk` | 推荐，绝大多数现代安卓手机（arm64-v8a） |
| `ChmlFrpLauncher-v3.1.0-allarch.apk` | arm64 / armeabi-v7a / x86_64 |
| `ChmlFrpLauncher-v3.1.0-source.zip` | 与本仓库内容一致的源码快照（MIT，零二进制、零密钥） |

**两个架构的 APK 内置同一套检查更新，都连接本仓库的 Releases**；使用同一签名证书，可直接覆盖安装，本机令牌、配置、主题与隧道缓存都会保留。

## 检查更新（连接 GitHub Releases）

设置页「版本更新」或关于页「检查更新」→ 手动点击后，App 访问本仓库的公开接口
`https://api.github.com/repos/lyu323/chmlfrp-launcher/releases/latest`：

- **只在点击时联网**，启动、后台、隧道运行期间不自动请求；不点不联网；
- 只 GET 版本信息（tag / 标题 / 更新说明 / 下载直链），**不上传**任何设备、账号、令牌或日志数据；
- 不带任何令牌，GitHub 公开接口免认证即可读取；
- 发现新版只在弹窗里给 Releases 页面与 APK 下载入口，**不自动下载、不静默安装**；
- 已是最新版本时显示「当前已是最新版本：3.1.0」；网络失败会给出原因并可手动打开 Releases 页。

关于页还提供项目地址、在 GitHub 点个 Star、更新日志、开源许可声明和另一个项目的入口。

## 同账号下的两个项目

| 仓库 | 地址 | 说明 |
| --- | --- | --- |
| `chmlfrp-launcher` | https://github.com/lyu323/chmlfrp-launcher | 本项目（ChmlFrp 启动器的检查更新连接这里） |
| `cpolar-launcher` | https://github.com/lyu323/cpolar-launcher | 同账号下的另一个项目（Cpolar 启动器，检查更新连接它自己的 Releases） |

两个项目**各自独立维护、各自开源、包名不同**（签名证书相同，可同时安装、互不覆盖），都带检查更新，但各连各的仓库，不互相混用。两个仓库地址如上，可直接点开查看与下载。

## 主要能力

- 官方 OAuth 登录 / 粘贴令牌直登 / 多账号本机档案
- 隧道列表多选、一次启动多条、配置拉取与 INI 编辑
- 前台服务守护与网络恢复重连、通知栏启停重启、通知字段自定义
- 节点连通预检、内核能力与配置格式兼容检查、连接诊断
- 每日签到中心（官方端点未公开时引导到面板，不做假接口）
- 设备与 ROM 适配说明、21 套主题、液态玻璃弹窗、隐藏后台
- 日志搜索 / 只看异常 / 脱敏分享 / 一键自检
- 「联系作者」直达 QQ 官方加好友页（内置 `mqqopensdkapi` 通道，零联网、不写剪贴板）
- **检查更新 / 项目地址 / Star / 更新日志 / 开源许可**（v3.1.0）

## 开源边界

**公开**：本 App 自己编写的 Java 源码、资源、构建脚本、文档（MIT）。

**不公开 / 不在仓库与源码 zip 中**：
- 签名密钥 `.jks` 与任何硬编码密码 —— `build.sh` 只从 `KS_PASS` / `KEY_PASS` 环境变量读取；
- 第三方 `libfrpc.so` 内核二进制 —— 不属于本仓库代码，请自备后放入 `FRPC_LIBS_DIR`；
- 任何访问令牌、账号密码、设备标识、个人节点地址或私有域名；示例节点一律 `node.example.com` 占位。

APK 作为已编译产物随 Release 分发；源码仓库与源码 zip 均**零二进制、零密钥**。
`CLIENT_ID` 是官方 OAuth 的公开参数，不是密钥。

## 隐私

- 配置、令牌、日志、隧道列表、签到记录**只存在本机应用私有目录**，不上传任何服务器；
- 外部连接：ChmlFrp 官方 API 与你在面板配置的官方节点，以及**仅手动触发**的 GitHub 更新检查（api.github.com 公开 Release 接口）与浏览器跳转 GitHub 页面；
- 仓库与源码包**不含**签名密钥、访问令牌、账号密码、设备标识或个人节点配置；
- 内置的作者 QQ 加好友链接是 QQ 官方公开短链（非凭证），点击只拉起 QQ；
- 无广告 / 统计 / 崩溃上报 / 推送 SDK。

## 从源码构建

仓库**只含 Java 源码与资源**，不包含第三方 `libfrpc.so`、Android SDK 与签名密钥。

```bash
export JH=/path/to/jdk17
export BT=/path/to/android-build-tools
export AJ=/path/to/android-35/android.jar
export FRPC_LIBS_DIR=/path/to/jniLibs      # 下含 arm64-v8a / armeabi-v7a / x86_64
export OUT_DIR=./out

# 可选：签名（不设置则输出未签名 APK，仅供本地调试）
export KS_FILE=/path/to/your.jks
export KS_PASS='你的签名库密码'
export KEY_PASS='你的密钥密码'

bash build.sh
```

## 反馈

[Issues](https://github.com/lyu323/chmlfrp-launcher/issues) 或 App 内「✉ 联系作者」。

## 许可与免责

[MIT](LICENSE) · 第三方 `libfrpc.so` 内核二进制不在本许可范围内，权利归其原作者所有。
本 App 仅负责在本地运行 frpc 内核，不提供服务器、不保证节点可用；使用官方服务请遵守其条款。

项目主页、下载与反馈入口统一使用 GitHub 提供的地址：https://github.com/lyu323/chmlfrp-launcher
