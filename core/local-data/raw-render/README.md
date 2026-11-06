# RAW HSV 后段 JVM 验证

生产算法：`core/src/main/kotlin/dev/cxo/filmpack/core/RawHsvFilmRenderer.kt`。

输入是 `RawSensorToWorking` 输出的 **linear working RGB Q15**（0..32767，RGB interleaved）。输出是 **encoded working RGB Q15**，仍需 output ICC 转换，不能直接当作 sRGB。该实现承载已还原的 HSV / shared-post / 强度100 / 非黑白分支，无 JPEG 输入反曲线、颗粒或其他调整。

构造参数明确注入，且防御复制：

- repaired grid 为 `[S17,H91,V12,3]`，V 最快；分量分别为色相角增量、饱和度倍率、亮度倍率。
- shared postcurve 为 32769 Float，幅度为 Q15；第32768点供端点插值。
- 该通用类不包含 P1040758、相机矩阵、Elite 或其他照片/胶片专属值，不会隐式选择 profile。
- **原版 grid 内存是 S 最快、4 分量**，需明确转置为上述布局。fixture 生成器演示了此转换，生产使用者不能直接读4分量原始存储当该参数。

渲染原地可用、并发调用无共享 scratch、没有每像素对象/数组分配。参数检查拒绝非有限值、未修补 sentinel、负 S/V 倍率、非法曲线和负 Q15；编码/线性的语义仍由调用者负责，数值检查无法识别已编码但非负的像素。

运行：

```sh
./gradlew --offline :core:verifyRawRender
```

任务已注册到 `core/build.gradle.kts`，并挂入 `:core:check`。修改前快照保存于研究目录的 `evidence/raw-fidelity-20261103/before/android-raw-render/core-build.gradle.kts`。重新生成测试数据：

```sh
python3 core/local-data/raw-render/generate_fixtures.py /Users/chenyixuan/ReverseWork/DxOFilmPack
```

本目录的 `*.golden` 是 **verification-only**，没有复制到 app assets。来源为研究目录的实际 native 接口参数和输出：`evidence/raw-fidelity-20261103/native/dynamic-compute-s1rii-p1040758-elite-sensor512.tif.*`、`small-grid-s1rii-p1040758-elite-grid17.*`。17³ 仅前4913点有效，排除了SIMD padding。文件包含测试所需的实际矩阵、EV、网格、曲线、camera input、sensor output、final output；这些不表示生产已经支持构造任意 RAW 胶片参数。

2026-11-03 本机 JVM 实测：

| 集合/阶段 | 分量数 | 逐位相等 | 最大 Q15 差 | RMSE /255 |
| --- | ---: | ---: | ---: | ---: |
| Elite 照片512 后段 | 1536 | 1535 | 1 | 0.00019857 |
| Elite 照片512 完整数学链 | 1536 | 1535 | 1 | 0.00019857 |
| Elite 17³ 后段 | 14739 | 14726 | 1 | 0.00023112 |
| Elite 17³ 完整数学链 | 14739 | 14716 | 1 | 0.00030742 |

“完整数学链”指 camera Q15 → `RawSensorToWorking` → `RawHsvFilmRenderer`，不包含 RAW 解码、相机 profile 构建或 output ICC。另验证参数外部修改不影响实例、原地结果一致、错误输入拒绝；既有 JPEG 路径没有修改。

构造器目前只接受实际已验证的 repaired HSV grid／单调 shared postcurve 形态；非单调 post、三曲线 post、黑白或其他 RAW 分支未必适用，不能因类名通用而宣称已覆盖。

尚未完成任意胶片的 RAW grid/postcurve bake、跨照片相机/白平衡参数生成或主 app RAW 管线接入。这些 fixture 验证不代表新 APK 已交付。

## 跨上下文追加验收

主线程新增 `elite-landscape-grid17.golden`（S1RII P1176257）和 `elite-s9-grid17.golden`（S9 P1035087），参数与oracle来自各自受控原版上下文。Kotlin render-only三组均最大1 Q15；full分别最大1、5、1 Q15，风光最坏约0.03891/255。full验收容差6 Q15（小于0.05/255），用于容纳sensor量化传播，不追末位。两份额外fixture由同一generate_fixtures.py再生。

相同Elite共享后曲线跨两张S1RII相同，grid不同，因此这几份数据均只作验证，不能直接用于所有照片。根复跑日志：研究目录 `evidence/raw-fidelity-20261103/reference/android-multi-context-root.log`。
