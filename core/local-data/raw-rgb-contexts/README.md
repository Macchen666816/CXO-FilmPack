# Native RAW RGB contexts

Six film-specific S1RII parameter assets: Kodak EIR, Kodak HIE and Cine Teal and
Pink at the existing warm/daylight anchors. Files without a suffix use P1040758
(4199 K); `-landscape` uses P1176257 (5485 K). These are bounded context anchors,
not a reconstructed arbitrary-WB/EV camera profile. S9 is not covered.

`tools/build_raw_rgb_contexts.py` reproduces every file from the native captures;
`--check` verifies byte identity. The manifest records capture labels, exact
parameter offsets, all source hashes and output hashes. No JPEG parameters,
sampled output grids or fitted pixels enter these assets. All captured film
curve samples are integer Q15 Float32; signed-int16 encoding restores their
Float bits exactly and retains the native guard sample. Total binary size is
656244 bytes. Shared assets are 65682 bytes; three-curve assets are 196758 bytes.

## Format

All numbers are little endian. Magic `CXORRGB1`; version 1.

| Offset | Field |
|---:|---|
| 0 | Magic, 8 bytes |
| 8 | Version UInt32, 1 |
| 12 | Flags UInt32, only bit 0 permitted: runtime BW |
| 16 | Curve count UInt32, 1 or 3 |
| 20 | Samples per curve UInt32, 32769 |
| 24 | Curve encoding UInt32, 1 = signed-int16 Q15 |
| 28 | Sensor EV Float32 |
| 32 | Rendering EV Float32 |
| 36 | Pre matrix, 9 Float32, row major |
| 72 | Post matrix, 9 Float32, row major |
| 108 | Extra matrix, 9 Float32, row major |
| 144 | One or three Q15 curves, including one guard per curve |

Header size is 144 bytes. Payload size must exactly match the declared supported
shape. Unknown flags/version/encoding, nonfinite parameters, signed-negative
curve codes, missing guards, truncation and trailing bytes are rejected.

HIE has runtime BW enabled. EIR/HIE have one curve and rendering EV=0; Teal and
Pink has three curves and rendering EV≈0.7. Every context has sensor EV≈0.6.
Teal and Pink's matrices must come from its own native RAW provider: substituting
JPEG matrices or the generic Elite pre matrix changes the rendering.

## Core API and Android integration

```kotlin
val ctx = assets.open("raw-rgb-contexts/s1rii-eir.cxorrgb").use(RawRgbContextIO::read)
val sensor = RawSensorToWorking(sensorMatrixForWb, ctx.ev, verifiedHighlightControl)
val renderer = RawRgbFilmRenderer(RawRgbGridBaker.bake(ctx.bakeParameters()), ctx.runtimeBw)
sensor.renderQ15(cameraInputQ15, workingQ15)
renderer.renderQ15(workingQ15, encodedWorkingQ15)
// Apply the existing working-space -> output ICC conversion afterwards.
```

`RawRgbContextIO.read(InputStream): RawRgbContext` leaves stream ownership to
the caller. `ctx.ev`, `ctx.renderingEv`, `ctx.runtimeBw` and `ctx.curveCount` are
read-only. `ctx.bakeParameters()` returns independent matrix/curve copies. Cache
baked renderers per asset rather than rebuilding or copying a 65³ grid for each
row chunk. An LRU of two renderers bounds the steady grid storage to about 8.4 MiB.
The file format intentionally does not change the shared HSV catalog.

`RawRgbContextVerificationKt` takes this asset directory and the native capture
directory. It checks every stored parameter/curve against native bits and then
runs independent Kotlin bake/render plus sensor/full 17³ comparisons for all six
contexts. It also exercises reader limits, invalid carriers and context ownership.
