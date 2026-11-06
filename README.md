# CXO FilmPack

Android照片胶片编辑器，Kotlin主体，NDK承担部分计算与JPEG编码。当前版本 `1.0.0 / versionCode 32`，由 `stable` 变体构建，使用项目自己的长期密钥签名。

## 当前范围

- 153项胶片／风格，146固定模型有受控原版证据、7项实验模型；99种内置颗粒、独立后处理和边缘缺陷。
- JPEG／PNG，平台支持的HEIC（API28+，内部转8bit sRGB JPEG100），有标定且LibRaw可读取的RAW。584个可查询profile不等于584台实拍验收；缺机身／效果规则明确拒绝。
- 导出JPEG（质量1–100，默认98）或RGB8无压缩TIFF，SDR sRGB ICC，可选缩小。缩小发生在全源图处理后；不保证TIFF输入／16bit输出。
- 深棕玻璃工作台、搜索联动、500ms按住比较、捏合／双击复位、抽屉与实时调整预览。

## 分发范围

项目所有者当前目标是给少量摄影／技术朋友长期使用，按封闭小组管理，不按面向不特定用户的公开发布处理。`stable` 是唯一发版线，打包现有的 DxO 原始颗粒／漏光等核心资源，由本地长期密钥签名；同一签名可长期覆盖升级。

工程**没有任何自设的发布门槛**：构建不会因为资产清权、依赖许可或发布者记录而失败。`release/` 下的记录文件只作证据保留，不参与构建。发行与否是人的决定，不是构建系统的决定。

第三方声明在 THIRD_PARTY_NOTICES 及 APK 的 `assets/licenses`；LibRaw 对应源码包在 `releases/`。全部 125 个解析运行时坐标已映射为 Apache-2.0 并随包提供许可证及清单；坐标不等于实际嵌入库数量。This software is based in part on the work of the Independent JPEG Group.

## 构建

使用 Android Studio JBR；SDK／NDK／CMake 版本以 Gradle 文件为准，不同时启动多个 Gradle。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew --offline :app:assembleStable
```

产物：`app/build/outputs/apk/stable/app-stable.apk`。签名校验、版本号递增与密钥备份见 [release/README.md](release/README.md)。

开发期调试仍用默认的 `debug` 变体（`assembleDebug`）。测试Runner为 `android.test.InstrumentationTestRunner`；仪器测试包只是开发辅助，不随主APK分发。

## 文档与历史

人类阅读入口位于既有 Obsidian 项目目录的《00 CXO FilmPack 项目总览与文档导航》。研究与数值证据在 `/Users/chenyixuan/ReverseWork/DxOFilmPack`。

[原README历史记录](docs/history/README-before-public-beta-audit-20261106.md)保留此前实验过程及阶段失败；这些旧“当前／待办”不代表今天状态。旧版本和固定 hash 仍保留在 `releases/`。

本仓库自 2026-11-06 起纳入 Git（分支 `main`）。`releases/` 与 `evidence/` 不入库，仍留在本机。
