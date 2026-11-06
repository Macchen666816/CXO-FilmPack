# CXO FilmPack

Android照片胶片编辑器，Kotlin主体，NDK承担部分计算与JPEG编码。当前源码仍为0.7.3-preview / versionCode30，含公测审查修复；不等同旧0.7.3固定APK，尚未公开放行。

## 当前范围

- 153项胶片／风格，146固定模型有受控原版证据、7项实验模型；99种内置颗粒、独立后处理和边缘缺陷。
- JPEG／PNG，平台支持的HEIC（API28+，内部转8bit sRGB JPEG100），有标定且LibRaw可读取的RAW。584个可查询profile不等于584台实拍验收；缺机身／效果规则明确拒绝。
- 导出JPEG（质量1–100，默认98）或RGB8无压缩TIFF，SDR sRGB ICC，可选缩小。缩小发生在全源图处理后；不保证TIFF输入／16bit输出。
- 深棕玻璃工作台、搜索联动、500ms按住比较、捏合／双击复位、抽屉与实时调整预览。

## 当前分发范围

项目所有者当前目标是给少量摄影／技术朋友长期使用，按封闭小组预览管理，不按面向不特定用户的公开发布处理。工程`preview`变体保留现有DxO原始颗粒／漏光等核心资源，使用本机Android debug签名；同一签名可在小组成员间升级，但不能安装到另一签名渠道的版本上。不要把未确认的素材权限描述成已获授权。

如果以后改为公开网站、开放网盘或面向不特定用户发布，使用隔离的`publicBeta`变体（`0.8.0-beta01 / code31`）。该构建要求长期签名key、DxO资源分发依据、最终APK二进制／NOTICE核对和发布资料门槛。资源清权目前仍pending。全部125个解析运行时坐标已映射为Apache-2.0并随包提供许可证及清单；坐标不等于实际嵌入库数量。发布者Macchen、测试群反馈与随包隐私说明已填入。

[本次审查报告](evidence/public-beta-audit-20261105/REPORT.md)记录测试边界、固定内部APK及hash。保存需保持前台，不具备持久后台恢复。API26–32／非ARM64实机／16KiB页设备未本轮实测。HEIC40MP在K40成功，在模拟器平台解码失败。

## 构建

使用Android Studio JBR；SDK／NDK／CMake版本以Gradle文件为准，不同时启动多个Gradle。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :core:check :app:assemblePreview :app:assemblePreviewAndroidTest :app:lintPreview -PcxoTestBuildType=preview '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m'
```

测试Runner为 `android.test.InstrumentationTestRunner`。仪器测试包只是开发辅助，不随公开主APK分发。第三方声明在THIRD_PARTY_NOTICES及APK licenses资产；LibRaw对应源码包在releases，审查核对与当前vendor源文件一致。This software is based in part on the work of the Independent JPEG Group.

## 文档与历史

人类阅读入口位于既有Obsidian项目目录的《00 CXO FilmPack 项目总览与文档导航》，当前公测清单／使用说明／隐私草案已链接其中。研究与数值证据在 `/Users/chenyixuan/ReverseWork/DxOFilmPack`。

[原README历史记录](docs/history/README-before-public-beta-audit-20261106.md)保留此前实验过程及阶段失败；这些旧“当前／待办”不代表今天状态。旧版本和固定hash仍保留在releases。本项目当前尚未建立Git历史，审查目录已保存源码hash清单，不能据此声称有可恢复的完整Git快照。
