# 第三方来源与许可证

## android-ad-cleaner

MIT，固定提交 `19bb74fbefece01763f713c3c26220f63659ed87`。仓库保留相关机型的 37 份参考脚本、`channel_policy.py`、`fast.py`、`row.py` 和恢复说明。净屏将相关营销通知策略、目标状态检查和设置导航改写为 Android 原生执行流程。生成入口为 `tools/import_rules.py` 与 `tools/curate_routes.py`。 [上游源码](https://github.com/rddmwmddr/android-ad-cleaner/tree/19bb74fbefece01763f713c3c26220f63659ed87)

许可原文保存在 [vendor/android-ad-cleaner/LICENSE](vendor/android-ad-cleaner/LICENSE)，也作为 [upstream-license.txt](app/src/main/assets/upstream-license.txt) 随应用分发。

## ganlinte/GKD-subscription

MIT，Copyright (c) 2024 ganlinte。固定订阅 v72，提交 `4746d192b4c57a4b62ed92a7f5deaf79e64fa811`，源文件 SHA-256 为 `5624137f8c70566a8dc985990dd18571600efa1c76129734703533ca5ee71c23`。`tools/import_splash.py` 和 `tools/import_popup_ads.py` 将其中可表达的条件转换为净屏的开屏和应用内候选，保留应用范围和相关上下文。 [固定版本源文件](https://github.com/ganlinte/GKD-subscription/blob/4746d192b4c57a4b62ed92a7f5deaf79e64fa811/dist/ganlin_gkd.json5)

许可原文保存在 [vendor/gkd-splash-reference/LICENSE](vendor/gkd-splash-reference/LICENSE)，也作为 [splash-license.txt](app/src/main/assets/splash-license.txt) 随应用分发。

## 公开控件快照

净屏依据公开 Android 无障碍快照独立编写部分规则。仓库与 APK 保留选中的控件条件、快照链接、应用版本和快照压缩包 SHA-256。 [开屏样本](vendor/gkd-splash-reference/startup-samples.json) · [应用内样本](vendor/gkd-splash-reference/popup-samples.json)

## Block List Project

Unlicense，固定提交 `ee3bdbaddf49c756cf3340bb2e6a9dee5282d1c6`。网络过滤的域名资产由 `tools/import_dns.py` 生成。[dns-source.json](app/src/main/assets/dns-source.json) 记录来源和哈希，许可原文随应用分发为 [dns-license.txt](app/src/main/assets/dns-license.txt)。 [上游域名列表](https://github.com/blocklistproject/Lists/blob/ee3bdbaddf49c756cf3340bb2e6a9dee5282d1c6/ads.txt)

## 实现参考

净屏使用独立的规则存储、匹配和执行流程。公开资料用于核对可导入的规则格式与无障碍匹配行为。 [兼容规则格式说明](https://juejin.cn/post/6938590373740544007) · [无障碍匹配原理](https://www.cnblogs.com/Cl0ud/p/17684204.html)

支持作者页面的赞赏图片经授权随本项目分发。派生版本发布时请替换或移除。
