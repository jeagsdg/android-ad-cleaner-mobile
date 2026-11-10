# 净屏 ClearScreen

为 Android 准备的开源广告清理工具。打开 App 时自动识别开屏广告并尝试跳过，也可以处理应用内广告弹窗和系统推广。支持 Android 8.0 及以上，无需 Root、Shizuku 或电脑。

## 下载 0.9.0

| 安装包 | 包含功能 |
| --- | --- |
| [完整版](dist/ClearScreen-0.9.0.apk?raw=true) | 开屏跳过、弹窗处理、系统推广清理，以及可选的本机 DNS 过滤 |
| [纯离线版](dist/ClearScreen-0.9.0-offline.apk?raw=true) | 开屏跳过、弹窗处理和系统推广清理。安装包无 Android 联网权限 |

两个版本使用同一应用 ID。官方签名一致时，可以覆盖升级或切换版本，保留已有设置和恢复记录。0.8.1 及更早版本需先下载安装一次。下载后可核对 [SHA-256](dist/SHA256SUMS.txt)。

## 能做什么

- **开屏跳过**。随包提供网易云音乐、爱奇艺、腾讯视频等应用的专属规则。确认关闭后可显示短暂的净屏标记。 [应用覆盖](docs/应用覆盖.md)
- **应用内弹窗**。按应用启停，也可以编辑自定义规则，限定目标页面、等待时间和连续操作。规则在手机上运行。
- **系统推广清理**。先预览本机可尝试的设置，再选择要关闭的项目。已记录的改动可在「改动记录与恢复」逐项还原。 [机型适配](docs/机型适配.md)
- **本机 DNS 过滤**。完整版可选择开启，支持放行域名。视频内嵌广告以及与内容共用域名的广告可能保留。 [网络过滤与开屏](docs/网络过滤与开屏.md)

## 安装后怎么用

1. 在首页开启需要的功能。开屏、弹窗和系统设置自动操作由系统无障碍服务执行，请在系统设置中授权。 [授权帮助](docs/权限兼容.md)
2. 使用完整版的网络过滤时，首次开启需要确认 Android 的 VPN 请求。Android 一次只能启用一个 VPN。
3. 如果开屏广告漏跳，可在「使用说明 → 开屏漏跳诊断」选择一个应用，开启五分钟诊断并预览结果。

广告样式、App 更新和厂商系统都会影响命中。覆盖表按包名和场景列出内置规则，验收记录说明本版实际验证的范围。 [应用覆盖](docs/应用覆盖.md) · [验收记录](artifacts/验收记录-v09.md)

## 隐私

净屏无需账号，无统计 SDK，也不会自动上报诊断。新安装默认只保留拦截累计次数，明细可在「使用说明」按需开启。系统设置的恢复记录留在本机。

完整版默认在打开净屏时向 GitHub 查询公开版本，最多每天一次。有新版时会在首页提示，可在「使用说明 → 应用更新」关闭自动提示或手动检查。纯离线版可通过浏览器访问下载页。

## 从源码构建

需要 JDK 17 或 21、Android SDK 35。Windows 将下面命令中的 `./gradlew` 换成 `.\gradlew.bat`，也可运行 `tools/build.ps1`。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew -PofflineOnly=true :app:assembleDebug
python tools/verify_sources.py
```

Windows 发布构建使用 `tools/build.ps1 -Release`，纯离线版追加 `-OfflineOnly`。发布签名从本机 `.local/signing.properties` 读取 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，请使用自己的密钥，保持配置文件和密钥在本机。

[MIT 许可证](LICENSE) · [第三方来源与许可证](NOTICE.md)
