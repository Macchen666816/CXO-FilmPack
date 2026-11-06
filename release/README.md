# 发布与签名准备

## 构建线

| 变体 | 用途 | 版本 | 签名 |
|---|---|---|---|
| `debug` | 开发调试 | `defaultConfig` 里的值 | Android debug 证书 |
| `stable` | **唯一发版线** | `1.0.0 / 32` | 本地长期密钥 |

`preview` 与 `publicBeta` 已于 2026-11-06 删除，对应的发布门槛任务一并移除（Git 提交 `a6b7ae3`）。现在构建不会因为资产清权、依赖许可或发布者记录而失败——**工程里不存在自设的发布门槛**。

## 长期签名密钥

已创建，不要重新生成：

- 密钥库：`.secrets/cxo-release.jks`（PKCS12，RSA 4096，SHA384withRSA，有效期至 2054-03-24）
- 别名：`cxo-public`
- 密码：`.secrets/release-credentials.txt`（`600`）
- 配置：`.cxo-release-signing.properties`（`600`，已被 `.gitignore` 忽略）

证书指纹 SHA-256：

```
9D:5C:D5:2E:CC:12:1B:CD:E7:90:83:97:27:76:9E:40:
9F:5C:43:B8:C3:AA:7B:71:83:04:57:96:28:31:13:C3
```

**必须离线备份两处**：`.secrets/` 整个目录（密钥库＋密码文件）。丢失密钥库等于所有已安装用户永远无法升级，只能卸载重装。不要把密钥、密码或填好的配置文件发到群里、提交到仓库或放进云盘共享链接。

## 构建与校验

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew --offline :app:assembleStable
```

输出 `app/build/outputs/apk/stable/app-stable.apk`。校验：

```sh
/Users/chenyixuan/Library/Android/sdk/build-tools/37.0.0/apksigner \
  verify --verbose --print-certs app/build/outputs/apk/stable/app-stable.apk
```

期望结果：v2 与 v3 均通过，16 KiB zip 对齐通过，证书指纹与上文一致。

## 版本号策略

`stable` 的 `versionCode`／`versionName` 在 `app/build.gradle.kts` 的 `androidComponents.onVariants` 里设置，当前 `32 / 1.0.0`。每次发版把 `versionCode` 至少加一，否则同签名的旧版无法覆盖升级。

## 迁移记录（已完成）

2026-11-06 在 K40（`b549157b`）完成一次性迁移：卸载 debug 证书的 `0.7.3-preview / 30`，安装 `1.0.0 / 32`。安装后核对 `versionCode=32`、`versionName=1.0.0`、签名方案 v3、应用可正常启动。

卸载清空了该机上的 `GrainSettings`、`PostEditingSettings` 偏好与已导入的颗粒包；原始照片不受影响（应用走系统选择器，不接管原图）。此后所有 `stable` 版本可正常覆盖升级。

## release/ 下的记录文件

这些是**证据记录，不是门槛**。删掉不会让构建失败。

| 文件 | 内容 | 对构建的影响 |
|---|---|---|
| `asset-clearance.json` | DxO 来源素材的核查状态，当前 `pending` | 无 |
| `dependency-clearance.json` | 依赖许可盘点结论 | 无 |
| `publisher-info.json` | 发布者、反馈渠道、隐私说明位置 | 无 |
| `dependency-license-review.md` | 许可盘点说明 | 无 |
| `dependency-pom-evidence.json` | 许可取证数据 | 无 |
| `dependency-license-review.csv` | 许可清单，**会打进 APK** 的 `assets/licenses/` | 删掉不报错，APK 静默少文件 |
| `PRIVACY.md` | 隐私说明，**会打进 APK** 的 `assets/legal/` | 删掉不报错，APK 静默少文件 |

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-06 | 删除 `preview`／`publicBeta` 变体与两个发布门槛任务；K40 迁移完成；记录文件降级为纯证据 |
| 2026-11-06 | 新增 `stable` 线、长期密钥、`1.0.0/32` 签名包 |
| 2026-11-06 | 原说明只描述 `preview`／`publicBeta`，改写为当前状态 |
