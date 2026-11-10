# 开源来源与迁移说明

上游：[rddmwmddr/android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner)

固定提交：`19bb74fbefece01763f713c3c26220f63659ed87`。上游许可为 MIT，完整原文保存在 `vendor/android-ad-cleaner/LICENSE`，同时打包为 `app/src/main/assets/upstream-license.txt`，应用内可查看。

直接保留：37 份机型 Python 脚本、`channel_policy.py`、`fast.py`、`row.py` 与恢复说明。这里只保留相关源码，不宣称取得完整上游 checkout。

实际迁移：

1. `channel_policy.py` 的营销关键词、服务排除词、默认不选词、拍摄功能保护词、vivo 固定渠道 ID、广告网络标识，由 `tools/import_rules.py` 生成 `policy.json`，`Policy.java` 实现判定。
2. `fast.py` 的反向保护前缀移植为目标状态判断。
3. 75 组规则、112 条入口、148 个目标定义（139 个自动、9 个手动）从相关 recipes 改写为原生声明式规则，每条规则记录源文件。`tools/curate_routes.py` 补充菜单备选、同义名称、明确的跨包步骤及手动处理说明；移除不明确属于广告的搜索建议目标。数量不代表厂商验证结果。
4. `row.py` 的“行内唯一开关、同名放弃、越界不操作”思想适配到 AccessibilityNodeInfo；原 Python 的全部几何判据未逐行移植。

新实现：Android 原生界面、无障碍适配器、只读选择路径并固定恢复入口、明确的执行/失败状态、本机通知渠道观察、先写原值的改动账本、批量恢复、未完成项重试和诊断导出。

0.3 新增原生 DNS-only VPN、UDP/TCP DNS 编解码和有限连接处理、域名放行、开屏控件识别及页面稳定等待/自动补查。网络域名规则来自 [Block List Project](https://github.com/blocklistproject/Lists) 的固定提交 `ee3bdbaddf49c756cf3340bb2e6a9dee5282d1c6`，使用 Unlicense。许可原文在 `app/src/main/assets/dns-license.txt`；来源和哈希见 `dns-source.json`，生成过程见 `tools/import_dns.py`。没有改变本仓库 MIT 代码许可证。

用户提供的“李跳跳 · 派大星2.2”安装包只用于本地参考核对，没有分发该 APK、提取其受保护代码或将其资源打包进净屏。其 ARM64 原生库无法在现有 x86_64 模拟器运行；不能声称已对该参考产品完成实际 UI 对比验收。

开屏控件候选参考 MIT 许可的 ganlinte/GKD-subscription，Copyright (c) 2024 ganlinte。订阅版本 v72，以源文件 SHA256 `5624137f8c70566a8dc985990dd18571600efa1c76129734703533ca5ee71c23` 固定。源文件及许可证保存在 vendor/gkd-splash-reference，应用内打包并展示 splash-license.txt。0.5 的 tools/import_splash.py 生成 64 条受限谓词规则及 2 类明确 SDK 开屏资源名，保留应用范围、匹配期限及可表达的上下文条件。[订阅源码](https://github.com/ganlinte/GKD-subscription)

0.4 继续使用固定的 v72 资料核对抖音与网易云音乐的更新控件，以及微信与 QQ 的原图控件。已有兼容规则的执行由净屏独立实现，社区 JSON 用于核对历史格式。当前备份还原只接受净屏格式。来源见 [作者进阶指南](https://juejin.cn/post/6938590373740544007) 和 [社区规则样本](https://github.com/wtwang1998/LiTiaotiao-Custom-Rules/blob/main/BasicRules.json)。

0.6 的 `tools/import_popup_ads.py` 从同一固定 MIT 订阅生成 72 条应用内广告候选，另加入一条依照公开控件关系实现的携程首页规则。三款优先应用的本地预设使用公开记录中的包名、页面及控件条件。应用按前台包名、可见节点、广告上下文和原生点击能力决定是否执行。[固定订阅源码](https://github.com/ganlinte/GKD-subscription) [携程控件参考](https://github.com/AIsouler/GKD_subscription/blob/main/src/apps/ctrip.android.view.ts) [社区应用规则参考](https://github.com/kernelai/LiTiaotiao/blob/main/BasicRules.json)

无障碍事件、排除项与关键词匹配思路参考用户提供的文章，净屏通过自己的窗口复查、唯一目标匹配及结果回读执行。[原理文章](https://www.cnblogs.com/Cl0ud/p/17684204.html)

小米旧版系统菜单另参考厂商公开隐私说明，具体 URL 保存在相应 route.reference 与 [机型适配说明](docs/机型适配.md)；未复制网页全文。设备识别数据只用于展示名称，不冒充固件版本或实机认证。

未迁移能力及厂商真机验证缺口见 README；上游脚本的实测状态不自动继承到本 App。Gradle/AGP/JUnit/Robolectric 为构建或测试依赖，不随 APK 作为运行时 SDK 打包。

支持作者页的赞赏码图片由仓库维护者提供并授权随应用展示，保留原始字节。Fork 发布时请替换或移除此图片，避免将赞赏误付给原维护者。
