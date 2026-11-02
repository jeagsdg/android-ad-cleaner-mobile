# 开源来源与迁移说明

上游：[rddmwmddr/android-ad-cleaner](https://github.com/rddmwmddr/android-ad-cleaner)

固定提交：`b437125829b7e78589850f6a0e54f865d25a8e08`。上游许可为 MIT，完整原文保存在 `vendor/android-ad-cleaner/LICENSE`，同时打包为 `app/src/main/assets/upstream-license.txt`，应用内可查看。

直接保留：36 份机型 Python 脚本、`channel_policy.py`、`fast.py`、`row.py` 与恢复说明。这里只保留相关源码，不宣称取得完整上游 checkout。

实际迁移：

1. `channel_policy.py` 的营销关键词、服务排除词、默认不选词、拍摄功能保护词、vivo 固定渠道 ID、广告网络标识，由 `tools/import_rules.py` 生成 `policy.json`，`Policy.java` 实现判定。
2. `fast.py` 的反向保护前缀移植为目标状态判断。
3. 49 条设置路径、88 个目标定义及明确目标开关从相关 recipes 改写为原生声明式规则，每条规则记录源文件。
4. `row.py` 的“行内唯一开关、同名放弃、越界不操作”思想适配到 AccessibilityNodeInfo；原 Python 的全部几何判据未逐行移植。

新实现：Android 原生界面、无障碍适配器、明确的执行/失败状态、本机通知渠道观察、先写原值的改动账本、恢复与导出。

未迁移能力及厂商真机验证缺口见 README；上游脚本的实测状态不自动继承到本 App。Gradle/AGP/JUnit/Robolectric 为构建或测试依赖，不随 APK 作为运行时 SDK 打包。
