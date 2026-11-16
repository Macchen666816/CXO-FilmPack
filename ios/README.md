# CXO FilmPack for iOS / iPadOS

CXO FilmPack 的 iPhone / iPad 版本。胶片计算、颗粒、相机 RAW 标定沿用安卓版的 `core` 代码和数据，界面用 SwiftUI 重写。

An iPhone / iPad port of CXO FilmPack. The colour core, grain and camera-RAW calibration are the Android `core`
code and data compiled for iOS with Kotlin Multiplatform; the interface is rewritten in SwiftUI.

非商业使用，许可与上级仓库相同（`../LICENSE`）。修改说明见 [MODIFICATIONS.md](MODIFICATIONS.md)。

## 功能

- 普通照片（JPEG / HEIC / PNG）走与安卓版相同的胶片管线，153 款胶片，强度 0–200
- 颗粒、光线 / 色彩调整、边缘与漏光，各模块可单独开关
- 相机 RAW：默认用 LibRaw 0.22.3 + 584 台机身标定（与安卓版同一条管线）；可在设置中改用苹果 RAW 解码；未标定机身自动用苹果解码
- Apple ProRAW：固定用苹果 Core Image RAW 解码
- 多张照片同时编辑（底部照片条），复制 / 粘贴 / 同步设置
- iPad 采用侧边面板布局，iPhone 采用底部工具栏；Liquid Glass 风格
- 导出页实时预览：胶片 135 边框（自动 / 横排 / 竖排）、中画幅扫描、参数信息条、日期戳、相纸白边、角标；多张照片的索引图（135 / 120）
- 渲染只用一个串行队列，按优先级分带处理（预览 > 照片条 > 胶片缩略图 > 导出）

## 构建（macOS + Xcode）

在 `ios/` 目录里依次双击：

1. `fetch-assets.command`：从 [Releases](https://github.com/Macchen666816/CXO-FilmPack/releases/latest) 的安卓安装包里取出胶片数据、RAW 标定和颗粒包，放进 `ios/Assets`（约 330MB，太大，不放进 Git）
2. `build-shared.command`：编译 Kotlin 共享库 `CxoShared.xcframework`（需要 JDK 17+，没有会自动下载）
3. `build-libraw.command`：把 `app/src/main/cpp/vendor/libraw` 编成 `CxoRaw.xcframework`（-O3）
4. 用 Xcode 打开 `App/CXOFilmPack.xcodeproj`，在 Signing 里选你自己的开发团队后运行；或双击 `build-ios.command` 只做编译检查
5. `build-ipa.command`：打包未签名的 `CXOFilmPack.ipa`，可用 SideStore / AltStore 侧载（会用你的 Apple ID 重新签名）

可选：
- 在 `ios/local.cache-dir` 写一个目录路径（比如外置硬盘），Gradle / Kotlin / Xcode / LibRaw 的缓存就放在那里
- 在 `ios/local.team` 写你的开发团队 ID，重新运行 `App/gen_project.py` 生成工程时会保留签名设置
- 新增 Swift 文件后要重新运行 `python3 App/gen_project.py`

## 目录

| 路径 | 内容 |
|---|---|
| `App/` | SwiftUI 应用、工程生成脚本 `gen_project.py` |
| `shared/` | Kotlin Multiplatform 模块：`core` 的 iOS 编译副本（见修改说明）、`engine/`（安卓 `PhotoProcessor` 与 RAW 管线的平台无关部分）、`iosMain/` 桥接 |
| `native/` | LibRaw 的 C 接口 `cxo_raw.h/.cpp` |
