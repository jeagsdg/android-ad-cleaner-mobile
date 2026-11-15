# 净屏 ClearScreen

为 Android 准备的开源广告清理工具。打开 App 时自动识别开屏广告并尝试跳过，也可以处理应用内广告弹窗和系统推广。支持 Android 7.0 及以上。开屏跳过、弹窗处理和系统推广清理可直接在手机上使用。

## 下载 2.0.0（版本号 19）

| 安装包 | 包含功能 |
| --- | --- |
| [完整版](dist/ClearScreen-2.0.0.apk?raw=true) | 开屏跳过、弹窗处理、系统推广清理、签名规则更新和可选的本机 DNS 过滤 |
| [纯离线版](dist/ClearScreen-2.0.0-offline.apk?raw=true) | 开屏跳过、弹窗处理、精选规则目录和系统推广清理。安装包无 Android 联网权限 |

两个版本使用同一应用 ID。官方签名一致时，可以覆盖升级或切换版本，保留已有设置和恢复记录。0.8.1 及更早版本需先下载安装一次。下载后可核对 [SHA-256](dist/SHA256SUMS.txt)。

## 能做什么

- **开屏跳过**。随包提供网易云音乐、爱奇艺、腾讯视频等应用的专属规则。确认关闭后可显示短暂的净屏标记。 [应用覆盖](docs/应用覆盖.md)
- **应用广告管理**。批量或单独排除开屏和弹窗。自定义规则可在本机只读试运行，也可从「刚才没跳过」记录一次广告并预览反馈。
- **精选规则**。搜索应用或包名，查看规则来源和有记录的适配版本，逐条启停。完整版可独立更新签名规则包，并退回上一份。
- **系统推广清理**。先读取本机开关，再勾选要调整的项目。应用改动前重新核对开关和设置路径，已记录的改动可逐项还原。 [机型适配](docs/机型适配.md)
- **本机 DNS 过滤**。完整版可选择开启，支持自定义拦截、放行域名和按应用绕过。 [网络过滤与开屏](docs/网络过滤与开屏.md)

## 安装后怎么用

1. 在首页点「开启自动跳过」。进入手机设置后找到「净屏」，打开开关，再返回应用。看到「已开启」后，像平时一样打开常用应用。 [授权帮助](docs/权限兼容.md)
2. 使用完整版的网络过滤时，首次开启需要确认 Android 的 VPN 请求。Android 一次只能启用一个 VPN。
3. 如果有广告漏跳，在「应用广告管理」选中该应用，点「刚才没跳过」。可以查看已有处理结果，也可以重现一次开屏或弹窗，预览后再决定是否导出。

小米、Redmi 和 POCO 用户经常遇到无障碍开关关闭时，可在首页点「无障碍掉线？自动恢复」。按页面提示完成一次性授权。Android 11 及以上可尝试在本机通过无线调试完成。 [设置步骤与 Windows 授权助手](docs/权限兼容.md#小米无障碍自动恢复)

广告样式、App 更新和厂商系统都会影响命中。覆盖表按包名和场景列出内置规则。 [应用覆盖](docs/应用覆盖.md)

## 隐私

净屏无需账号，无统计 SDK，也不会自动上报诊断。新安装默认只保留拦截累计次数，明细可在「使用说明」按需开启。系统设置的恢复记录留在本机。

完整版在打开净屏时可向 GitHub 查询公开应用版本和规则包。应用更新可在「使用说明」关闭，规则更新可在「精选规则」设置。出现新版时在首页点「查看更新」，下载完成后按手机提示覆盖安装。遇到文件接口故障时，2.0 可从 GitHub 公开归档读取更新。旧版的文件接口均不可用时，可以从公开归档的 `0-update` 目录取得官方完整版 APK，手动覆盖一次。纯离线版在浏览器查看下载。 [备用下载归档](https://codeload.github.com/jeagsdg/android-ad-cleaner-mobile/zip/refs/heads/main)

## 从源码构建

需要 JDK 17 或 21、Android SDK 35。Windows 将下面命令中的 `./gradlew` 换成 `.\gradlew.bat`，也可运行 `tools/build.ps1`。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew -PofflineOnly=true :app:assembleDebug
python tools/verify_sources.py
```

Windows 发布构建使用 `tools/build.ps1 -Release`，纯离线版追加 `-OfflineOnly`。发布签名从本机 `.local/signing.properties` 读取 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，请使用自己的密钥，保持配置文件和密钥在本机。

维护者可用 `python tools/publish_rule_pack.py --version 1 --min-version-code 19 --max-version-code 19` 生成首份官方签名规则包。后续包版本递增，APK 兼容范围须经过测试。新增或替换规则必须具备明确的广告场景、准确的 Activity 和应用自身的关闭控件，勾选状态保留在本机。APK 发布时保持根目录两份更新清单一致，并同步更新 `dist` 安装包和 `0-update` 的完整版镜像。

[MIT 许可证](LICENSE) · [第三方来源与许可证](NOTICE.md)
