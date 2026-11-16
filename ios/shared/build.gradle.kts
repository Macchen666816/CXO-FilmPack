import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins { kotlin("multiplatform") }

kotlin {
    val xcf = XCFramework("CxoShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "CxoShared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies { /* stdlib only: the colour core is dependency-free */ }
        all { languageSettings.optIn("kotlin.ExperimentalUnsignedTypes") }
    }
}
