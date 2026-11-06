# 本工程的自设规则清单

> 用途：分清「Android 平台要求」和「本工程自己加的约定」。后者全部可以删除，删不删由项目所有者决定。本文只列**自设**部分，平台行为不在此列。

## 判据

| 类别 | 典型长相 | 能否改动 |
|---|---|---|
| 平台行为 | AGP 编译行为、`PackageManager` 校验、`INSTALL_FAILED_*` 错误码 | 不能，只能适配 |
| **自设规则** | 本仓库里的 Gradle task、JSON 记录、文档、示例配置 | **能删，删了通常不会坏** |

## 清单

| 项 | 位置 | 是什么 | 删除后果 |
|---|---|---|---|
| `verifyPublicBetaReleaseGate` | `app/build.gradle.kts` | Gradle 检查任务 | 无影响，`publicBeta` 照常构建 |
| `verifyStableSigningGate` | `app/build.gradle.kts` | Gradle 检查任务 | 无影响；缺签名时只会得到装不上的未签名包 |
| `publicBeta` 变体 | `app/build.gradle.kts` | 构建变体 | 少一条构建线 |
| `stable` 变体 | `app/build.gradle.kts` | 构建变体（2026-11-06 新增） | 少一条构建线，正式包需另配 |
| `preview` 变体 | `app/build.gradle.kts` | 构建变体（来源未考证） | 少一条构建线 |
| `release/asset-clearance.json` | `release/` | 资产来源记录，仅门禁读取 | 门禁失败；门禁也删了则无影响 |
| `release/dependency-clearance.json` | `release/` | 依赖许可记录，仅门禁读取 | 同上 |
| `release/publisher-info.json` | `release/` | 发布者信息记录，仅门禁读取 | 同上 |
| `release/dependency-license-review.md` | `release/` | 许可盘点说明，被上一项的 `evidencePath` 指向 | 门禁失败 |
| `release/dependency-license-review.csv` | `release/` | 被构建打进 APK 的 `assets/licenses/` | **不报错**，但 APK 静默缺少该清单文件 |
| `release/PRIVACY.md` | `release/` | 被构建打进 APK 的 `assets/legal/` | **不报错**，但 APK 静默缺少隐私说明 |
| `release/dependency-pom-evidence.json` | `release/` | 许可取证数据 | 无影响 |
| `.cxo-release-signing.properties.example` | 项目根 | 本地签名配置模板 | 无影响 |
| 版本号覆盖段 | `app/build.gradle.kts` 的 `onVariants` | 给 `stable` / `publicBeta` 设 `versionCode`、`versionName` | 回落到 `defaultConfig` 的版本号 |

## 这些不代表什么

- **不是 Google / Android 的要求。** 删掉不会有任何系统层面的问题。
- **不是法律意见。** 这些文件记录的是「有没有找到依据」，不是「是否合法」的结论。
- **`pending` 不等于「确认侵权」。** 它只表示「目前没有记录到再分发依据」。
- **门禁不会保护你。** 它只是减速带：能挡住顺手，挡不住有意。有仓库写权限的人随时可以删。

## 怎么删

1. **删检查任务**：整段删掉 `tasks.register("verifyXxx") { ... }`，以及紧随其后的 `tasks.matching { ... }.configureEach { dependsOn(...) }`。
2. **删构建变体**：删掉 `buildTypes { create("xxx") { ... } }` 中对应块，以及 `androidComponents.onVariants` 里针对它的版本覆盖。
3. **删记录文件**：直接删。若仍保留门禁，门禁会失败——这是预期行为；不想看到失败就一并删门禁。
4. **删被构建引用的文件**（`PRIVACY.md`、`dependency-license-review.csv`）：实测 2026-11-06，删掉后 `:app:stageProductFilmAssets` 返回 `BUILD SUCCESSFUL`，产物 `legal/` 目录直接不存在——**Gradle 对缺失的 `from(file)` 源静默跳过，不报错**。要删就连同 `app/build.gradle.kts` 里对应的 `from(...)` 一起删，否则会得到一个悄悄少了文件的 APK。

> **静默失败比报错更危险。** 这两项不在任何检查里，删了没人提醒你，直到有人问"隐私声明在哪"。

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-06 | 建立清单；`app/build.gradle.kts` 中相关段落加 `[SELF-IMPOSED]` 标注 |
| 2026-11-06 | 修正：原先写"删了构建失败"，实测为静默跳过、产物缺文件且 `BUILD SUCCESSFUL` |
