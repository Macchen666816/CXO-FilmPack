# Vendored Backdrop Catalog 2.0.1 (Android)

This local Android library retains the source of the user's installed Backdrop
Catalog: LiquidButton, LiquidToggle, LiquidSlider, LiquidBottomTabs and
LiquidBottomTab, including their spring/velocity animation, gesture observation,
press distortion, interactive highlight, refraction, chromatic aberration,
combined backdrop, shadows and Backdrop rendering implementation.

Packages remain `com.kyant.backdrop.*` and
`com.kyant.backdrop.catalog.components.*`. No runtime/source reference to the
separate AndroidLiquidGlass project is required after vendoring.

Only the common `expect` / Android `actual` binding declarations are resolved to
ordinary Android Kotlin functions; the ColorFilterEffect default argument moves
from the expect declaration to the Android function. No shader, gesture or
animation code is rewritten. The five components and three shared interaction
utilities are byte-for-byte upstream source.

`UPSTREAM.json` records every input/output SHA-256 and mechanical adaptation.
The full Apache-2.0 notice is retained in the main application's
`THIRD_PARTY_NOTICES/AndroidLiquidGlass.txt` and is copied into its APK assets.

## Dependencies

The same versions as the supplied built catalog are used: Compose 1.12.0,
activity-compose 1.13.0 and Kyant Shapes 1.2.1. Kotlin JVM and Compose compiler
plugins are aligned to 2.4.10. The app and this module enable Compose via AGP's
built-in Kotlin; do not apply a separate Kotlin Android plugin.

The module API exports Compose Foundation/UI/Graphics/Ripple, Shapes and
activity-compose, so the app may embed components in ComposeView. A
ComponentActivity supplies lifecycle/ViewModel/saved-state owners. Configure
`ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed` for Activity-owned
ComposeViews; transient dialog controls should explicitly dispose their
composition on dialog dismissal (or use an appropriate lifecycle owner).

Each composable needs a real Backdrop input. A recorded `rememberLayerBackdrop`
with `Modifier.layerBackdrop` or a `rememberCanvasBackdrop` can provide one.
Hosts must draw content actually intended as the component's optical backdrop;
they should not modify or filter the exported photo pixels.

## Verification

The module's `:glass-catalog:assembleDebug` passes offline (23 tasks, 17 seconds)
with two existing upstream asFrameworkPaint deprecation warnings. Initial use of
an old default-memory Gradle daemon failed with Metaspace before module build;
the recorded retry with heap 2GiB / metaspace 1GiB passes. The module subtask has
not installed an APK or run UI/device tests. The parent integration performs
those checks and core regression because of the Kotlin compiler alignment.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew --offline --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
  :glass-catalog:assembleDebug
```
