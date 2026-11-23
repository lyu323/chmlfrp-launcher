# ChmlFrp 启动器 · Android 端 frpc 启动器

第三方本地启动器：只在手机本地运行 `frpc` 内核，配合 ChmlFrp 官方面板使用。
**本仓库不提供任何服务器、节点或隧道服务**，节点可用性、带宽、账号与签到规则均由官方决定。

> 这是**非官方**项目，与 ChmlFrp 官方无隶属关系。

## 下载

到 [Releases](https://github.com/lyu323/chmlfrp-launcher/releases) 下载，当前版本 **v3.0.1**：

| 文件 | 适用 |
| --- | --- |
| `ChmlFrp启动器-arm64.apk` | 推荐，绝大多数现代安卓手机（arm64-v8a） |
| `ChmlFrp启动器-全架构.apk` | arm64 / armeabi-v7a / x86_64 |
| `ChmlFrp启动器-v3.0.1-源码.zip` | 与本仓库内容一致的源码快照 |

两个 APK 使用同一签名证书，可直接覆盖安装升级，本机令牌、配置、主题与隧道缓存都会保留。

## 主要能力

- 官方 OAuth 登录 / 粘贴令牌直登 / 多账号本机档案
- 隧道列表多选、一次启动多条、配置拉取与 INI 编辑
- 前台服务守护与网络恢复重连、通知栏启停重启、通知字段自定义
- 节点连通预检、内核能力与配置格式兼容检查、连接诊断
- 每日签到中心（官方端点未公开时引导到面板，不做假接口）
- 设备与 ROM 适配说明、多套主题、液态玻璃弹窗、隐藏后台
- 日志搜索 / 只看异常 / 脱敏分享 / 一键自检
- 「联系作者」直达 QQ 官方加好友页（内置 `mqqopensdkapi` 通道，零联网、不写剪贴板）

## 隐私

- 配置、令牌、日志、隧道列表、签到记录**只存在本机应用私有目录**，不上传任何服务器；
- 外部连接仅限 ChmlFrp 官方 API 与你在面板配置的官方节点；
- 仓库中**不含**签名密钥、访问令牌、账号密码、设备标识或任何个人节点配置；
- 源码里的 `CLIENT_ID` 是官方 OAuth 的公开 client_id（授权页 URL 里本来就有），**不是密钥**；
- 内置的作者 QQ 加好友链接是 QQ 官方公开短链，点击只拉起 QQ，App 不读取设备或账号信息；
- 示例节点地址一律使用 `node.example.com` 占位，实际节点请在官方面板自行配置。

## 从源码构建

仓库**只含 Java 源码与资源**，不包含第三方 `libfrpc.so` 内核、Android SDK 与签名密钥。

需要自备：JDK 17、Android SDK build-tools（含 `aapt2` / `d8` / `zipalign` / `apksigner`）、`android-35/android.jar`、对应 ABI 的 `libfrpc.so`。

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

`build.sh` 只从环境变量读取签名密码，**仓库里没有任何硬编码密码**。请勿把 `.jks` 提交进版本库。

## 反馈

- Bug / 需求：[Issues](https://github.com/lyu323/chmlfrp-launcher/issues)
- 也可在 App 内点「✉ 联系作者」直达 QQ 加好友页

## 免责

本 App 仅负责在本地运行 frpc 内核，不提供服务器、不保证节点可用；使用官方服务请遵守其条款。
项目主页、下载与反馈入口统一使用 GitHub 提供的地址：https://github.com/lyu323/chmlfrp-launcher
