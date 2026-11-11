# 净屏 ClearScreen

为 Android 准备的开源广告清理工具。打开 App 时自动识别开屏广告并尝试跳过，也可以处理应用内广告弹窗和系统推广。支持 Android 7.0 及以上，无需 Root、Shizuku 或电脑。

## 下载 1.0.0（版本号 14）

| 安装包 | 包含功能 |
| --- | --- |
| [完整版](dist/ClearScreen-1.0.0.apk?raw=true) | 开屏跳过、弹窗处理、系统推广清理，以及可选的本机 DNS 过滤 |
| [纯离线版](dist/ClearScreen-1.0.0-offline.apk?raw=true) | 开屏跳过、弹窗处理和系统推广清理。安装包无 Android 联网权限 |

两个版本使用同一应用 ID。官方签名一致时，可以覆盖升级或切换版本，保留已有设置和恢复记录。0.8.1 及更早版本需先下载安装一次。下载后可核对 [SHA-256](dist/SHA256SUMS.txt)。

## 能做什么

- **开屏跳过**。随包提供网易云音乐、爱奇艺、腾讯视频等应用的专属规则。确认关闭后可显示短暂的净屏标记。 [应用覆盖](docs/应用覆盖.md)
- **应用广告管理**。按名称或包名查看专属规则，批量或单独排除开屏和弹窗。自定义规则可以在本机只读试运行，检查实际页面是否匹配。
- **系统推广清理**。先预览本机候选，再选择要关闭的项目。中断后可以续跑或重试单项，已记录的改动可以逐项还原。 [机型适配](docs/机型适配.md)
- **本机 DNS 过滤**。完整版可选择开启，支持自定义拦截、放行域名和按应用绕过。 [网络过滤与开屏](docs/网络过滤与开屏.md)

## 安装后怎么用

1. 在首页点「开启自动跳过」。进入手机设置后找到「净屏」，打开开关，再返回应用。看到「已开启」后，像平时一样打开常用应用。 [授权帮助](docs/权限兼容.md)
2. 使用完整版的网络过滤时，首次开启需要确认 Android 的 VPN 请求。Android 一次只能启用一个 VPN。
3. 如果开屏广告漏跳，在「应用广告管理」选中该应用，点「开屏漏跳诊断」。开屏结束后回到同一入口查看结果。

广告样式、App 更新和厂商系统都会影响命中。覆盖表按包名和场景列出内置规则。 [应用覆盖](docs/应用覆盖.md) · [1.0.0 验收](artifacts/验收记录-v10.md)

## 隐私

净屏无需账号，无统计 SDK，也不会自动上报诊断。新安装默认只保留拦截累计次数，明细可在「使用说明」按需开启。系统设置的恢复记录留在本机。

完整版默认在打开净屏时向 GitHub 查询公开版本。成功后次日再查，网络失败可以手动重试。有新版时可在首页打开「查看更新」，下载完成后点「安装更新」，按手机提示覆盖安装。应用内下载使用 GitHub 的文件接口，无需访问网页或登录。可在「使用说明 → 应用更新」关闭自动提示。纯离线版可通过浏览器访问下载页。

## 从源码构建

需要 JDK 17 或 21、Android SDK 35。Windows 将下面命令中的 `./gradlew` 换成 `.\gradlew.bat`，也可运行 `tools/build.ps1`。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew -PofflineOnly=true :app:assembleDebug
python tools/verify_sources.py
```

Windows 发布构建使用 `tools/build.ps1 -Release`，纯离线版追加 `-OfflineOnly`。发布签名从本机 `.local/signing.properties` 读取 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，请使用自己的密钥，保持配置文件和密钥在本机。

[MIT 许可证](LICENSE) · [第三方来源与许可证](NOTICE.md)
