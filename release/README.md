# 发布与签名准备

> 本文件里的检查门槛、记录文件与自定义变体，**全部是本工程自设的，不是 Android 平台要求**。哪些能删、删了会怎样，见 [SELF-IMPOSED.md](SELF-IMPOSED.md)。源码中以 `[SELF-IMPOSED]` 标注。

## 当前构建线

工程有三条互不复用的构建线，别把它们混成一个概念：

| 变体 | 用途 | 版本 | 签名 | 打包门槛 |
|---|---|---|---|---|
| `preview` | 日常小组测试 | `0.7.3-preview / 30` | 本机 Android debug 证书 | 无 |
| `stable` | 小组正式版 | `1.0.0 / 32` | 发布者长期密钥 | 只检查密钥材料 |
| `publicBeta` | 面向不特定用户的公开候选 | `0.8.0-beta01 / 31` | 同一把长期密钥 | 签名＋资产清权＋依赖许可＋发布者信息 |

`stable` 与 `preview` 打包的是同一批 DxO 来源资源（99 份颗粒 patch、边缘／漏光贴图、中文胶片介绍、派生标定／曲线）。实测两个 APK 的 `assets/` 条目共 1,564 个，CRC 与大小逐条一致，签名不改变内容。

`release/asset-clearance.json` 保持 `pending`。这批资源没有公开再分发依据，因此 `stable` 只能交给已知的小组成员，不得上架应用商店、不得提供公开下载入口。`stable` 的门禁只验密钥，不读该清权文件——这是"换个正经签名"，不是"取得授权"。

## 长期签名密钥

已创建，不要重新生成：

- 密钥库：`.secrets/cxo-release.jks`（PKCS12，RSA 4096，SHA384withRSA，有效期至 2054-03-24）
- 别名：`cxo-public`
- 密码：`.secrets/release-credentials.txt`（`chmod 600`）
- 配置：`.cxo-release-signing.properties`（`chmod 600`，已被 `.gitignore` 忽略）

证书指纹 SHA-256：

```
9D:5C:D5:2E:CC:12:1B:CD:E7:90:83:97:27:76:9E:40:
9F:5C:43:B8:C3:AA:7B:71:83:04:57:96:28:31:13:C3
```

**必须离线备份两处**：`.secrets/` 整个目录（密钥库＋密码文件）。丢失密钥库等于所有已安装用户永远无法升级，只能卸载重装。不要把密钥、密码或填好的配置文件发到群里、提交到仓库或放进云盘共享链接。

## 构建正式版

```sh
cd "/Users/chenyixuan/AndroidStudioProjects/CXO FilmPack"
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew --offline :app:assembleStable
```

输出 `app/build/outputs/apk/stable/app-stable.apk`，归档副本在 `releases/CXO-FilmPack-1.0.0-stable-20261106.apk`，SHA256 记录在 `releases/SHA256SUMS.txt`。

校验：

```sh
/Users/chenyixuan/Library/Android/sdk/build-tools/37.0.0/apksigner \
  verify --verbose --print-certs app/build/outputs/apk/stable/app-stable.apk
```

当前结果：v2 与 v3 均通过，16 KiB zip 对齐通过。

## 版本号策略

`stable` 的 `versionCode`／`versionName` 在 `app/build.gradle.kts` 的 `androidComponents.onVariants` 里单独设置，当前 `32 / 1.0.0`。以后每次发版把 `versionCode` 至少加一，否则同一签名的旧版无法被覆盖升级。`preview` 与 `publicBeta` 的版本号是各自独立的，不受影响。

## 首次迁移（只做一次）

现有测试机安装的是 debug 证书的 `preview` 包。换成长期证书后签名不同，系统会拒绝覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），必须卸载后重装。

- 会丢失：`GrainSettings`、`PostEditingSettings` 中的偏好，以及通过应用导入的颗粒包文件。
- 不会丢失：手机里的原始照片（应用从不接管原图，走系统选择器读取）。

迁移后，后续同签名的 `stable` 版本可以正常覆盖升级。

## 公开分发前仍缺

1. `release/asset-clearance.json` 四组资产仍为 `pending`；没有明确再分发依据前不要改成 `cleared`。
2. 最终 APK 里实际二进制与 `THIRD_PARTY_NOTICES` 的逐项复核仍未收尾。
3. `publicBeta` 门禁因此仍会阻止构建，这是预期行为，不是故障。

## 变更记录

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-11-06 | 记录三条构建线的区别；`stable` 线启用、创建长期密钥、产出 `1.0.0/32` 签名包 | `apksigner verify` 输出、`releases/SHA256SUMS.txt` |
| 2026-11-06 | 原说明只描述 `preview`／`publicBeta`，改写为当前三线状态 | `app/build.gradle.kts` 变体定义 |
