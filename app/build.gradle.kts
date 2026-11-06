import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Package only production render assets referenced by films.json. Research
// catalogs and golden fixtures stay out of the application APK.
val productFilmAssets = layout.buildDirectory.dir("generated/product-film-assets")
abstract class StageProductFilmAssets : Sync() {
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
}
val stageProductFilmAssets = tasks.register<StageProductFilmAssets>("stageProductFilmAssets") {
    assetsDirectory.set(productFilmAssets)
    from(project(":core").layout.projectDirectory.dir("local-data")) {
        include("shared-catalog/*.cxohsv")
        include("bw-catalog/*.cxohsv")
        include("threecurve-catalog/*.cxohsv")
        include("rgb-catalog/*.cxorgb")
        include("dynamic-direct-catalog/*.cxohsv")
        include("dynamic-provider-bw-catalog/*.cxohsv")
        include("srgb-entry-pix65.cxoentry")
        include("output-cmm/widegamut-to-srgb.lcmsbin")
        exclude("shared-catalog/ColorModeArgentiqueKodakEkta64T.cxohsv")
        into("films")
    }
    from(rootProject.layout.projectDirectory.dir("THIRD_PARTY_NOTICES")) {
        include("*.txt")
        into("licenses")
    }
    from(rootProject.layout.projectDirectory.file("release/dependency-license-review.csv")) {
        into("licenses")
    }
    from(rootProject.layout.projectDirectory.file("release/PRIVACY.md")) {
        into("legal")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/grain")) {
        include("no-toning-context.cxoctx")
        into("films")
    }
    // Static camera calibration and provider resources; no captured photo bakes.
    from(project(":core").layout.projectDirectory.dir("local-data/raw-profile")) {
        include("cameras.json")
        include("s1rii-fidelity.json")
        include("s9-fidelity.json")
        include("sony-a7iv-fidelity.json")
        include("batch-generated/cameras.json")
        include("batch-generated/profiles/*.json")
        include("batch-generated/white-balance/*.cxowb")
        into("raw")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-wb")) {
        include("s1rii-grid.golden")
        include("s9-grid.cxowb")
        include("sony-a7iv-grid.cxowb")
        into("raw")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-films")) {
        include("curves/*.bin")
        into("raw-films")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-negative-providers")) {
        include("catalog.cxornp")
        into("raw-negative-providers")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-static-providers")) {
        include("catalog.cxornp")
        into("raw-static-providers")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-special-providers")) {
        include("catalog.cxornp")
        into("raw-special-providers")
    }
    from(project(":core").layout.projectDirectory.dir("local-data/raw-camera-bw")) {
        include("catalog.json")
        include("curves/*.bin")
        into("raw-camera-bw")
    }
    into(assetsDirectory)
}

// Stage the canonical verified resource pack once into generated APK assets.
val bundledGrainAssets = layout.buildDirectory.dir("generated/bundled-grain-assets")
abstract class StageBundledGrainAsset : Sync() {
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
}
val stageBundledGrainAsset = tasks.register<StageBundledGrainAsset>("stageBundledGrainAsset") {
    assetsDirectory.set(bundledGrainAssets)
    from(rootProject.layout.projectDirectory.file("releases/native-grain8-v1.cxograinpack"))
    into(assetsDirectory)
    doFirst {
        check(rootProject.file("releases/native-grain8-v1.cxograinpack").isFile) {
            "Missing canonical bundled grain pack"
        }
    }
}

// ---------------------------------------------------------------------------
// [SELF-IMPOSED] 从这一行往下的签名读取、发布检查与自定义变体，都是本工程自己
// 加的约定，不是 Android / Gradle 的平台要求，也不是法律结论。删除它们不会影响
// 构建、签名或安装，只会让对应的"门槛"消失。保留还是删除由项目所有者决定。
// 完整清单与删除后果：release/SELF-IMPOSED.md
// ---------------------------------------------------------------------------
val publicSigningFile = rootProject.file(".cxo-release-signing.properties")
val publicSigning = Properties().apply {
    if (publicSigningFile.isFile) publicSigningFile.inputStream().use(::load)
}
val publicSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !publicSigning.getProperty(it).isNullOrBlank() }
val publicSigningStore = publicSigning.getProperty("storeFile")?.let(rootProject::file)
val publicAssetsClearance = rootProject.file("release/asset-clearance.json")
val dependencyClearanceFile = rootProject.file("release/dependency-clearance.json")
val publisherInfoFile = rootProject.file("release/publisher-info.json")

val verifyPublicBetaReleaseGate = tasks.register("verifyPublicBetaReleaseGate") {
    group = "verification"
    description = "Require a private release key and evidence-backed asset clearance for public APK builds."
    doLast {
        check(publicSigningReady && publicSigningStore?.isFile == true) {
            "Public beta signing is not configured. Create the ignored .cxo-release-signing.properties from its example and keep the keystore backed up privately."
        }
        val clearance = publicAssetsClearance.takeIf { it.isFile }?.let {
            JsonSlurper().parse(it) as? Map<*, *>
        }
        val entries = clearance?.get("assets") as? List<*>
        check(clearance?.get("reviewStatus") == "cleared" && !entries.isNullOrEmpty() && entries.all { item ->
            val row = item as? Map<*, *> ?: return@all false
            val evidence = row["evidencePath"]?.toString()?.takeIf(String::isNotBlank)?.let(rootProject::file)
            row["status"] == "cleared" &&
                row["basis"]?.toString()?.isNotBlank() == true &&
                evidence?.isFile == true
        }) {
            "Public beta blocked: release/asset-clearance.json must document a cleared basis and an existing evidence file for every bundled asset group."
        }
        fun verifiedRecord(file: java.io.File, requiredFields: List<String> = emptyList()): Boolean {
            val record = file.takeIf { it.isFile }?.let { JsonSlurper().parse(it) as? Map<*, *> }
            val evidence = record?.get("evidencePath")?.toString()?.takeIf(String::isNotBlank)?.let(rootProject::file)
            return record?.get("reviewStatus") == "cleared" &&
                record?.get("basis")?.toString()?.isNotBlank() == true &&
                requiredFields.all { record?.get(it)?.toString()?.isNotBlank() == true } &&
                evidence?.isFile == true
        }
        check(verifiedRecord(dependencyClearanceFile)) {
            "Public beta blocked: complete release/dependency-clearance.json and retain the license review evidence."
        }
        check(verifiedRecord(publisherInfoFile, listOf("publisherName", "feedbackChannel", "privacyPolicyLocation"))) {
            "Public beta blocked: complete release/publisher-info.json with the public publisher and feedback contact."
        }
    }
}

android {
    namespace = "dev.cxo.filmpack.app"
    buildFeatures { compose = true }
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    defaultConfig {
        applicationId = "dev.cxo.filmpack.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 30
        versionName = "0.7.3-preview"
        ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a","x86","x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        // Single long-term release identity shared by the public-beta and the
        // closed-group stable build. Preview keeps the debug key so existing
        // test installs stay upgrade-compatible until the one-time migration.
        create("cxoRelease") {
            // v2 covers API 26+; v3 adds the key-rotation lineage record so a
            // future key change can still be delivered as an in-place update.
            enableV2Signing = true
            enableV3Signing = true
            if (publicSigningReady) {
                storeFile = publicSigningStore
                storePassword = publicSigning.getProperty("storePassword")
                keyAlias = publicSigning.getProperty("keyAlias")
                keyPassword = publicSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // [SELF-IMPOSED] 早期自定义变体，来源未考证；删除不影响其他构建线。
        create("preview") {
            initWith(getByName("release"))
            // Local performance/acceptance package; upgrades the existing test
            // installation while running without a debugger-enabled runtime.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += "release"
        }
        // [SELF-IMPOSED] 本工程自定义变体。删除不影响 preview / stable。
        create("publicBeta") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("cxoRelease")
        }
        // [SELF-IMPOSED] 2026-11-06 新增的小组正式版变体，删除不影响 preview。
        // 与 preview 打包同一批资产，差别只是签名身份与版本号。
        create("stable") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("cxoRelease")
        }
    }
    testBuildType = providers.gradleProperty("cxoTestBuildType").orElse("debug").get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
}

tasks.matching { it.name == "assemblePublicBeta" }.configureEach {
    dependsOn(verifyPublicBetaReleaseGate)
}
tasks.matching { it.name == "packagePublicBeta" }.configureEach {
    dependsOn(verifyPublicBetaReleaseGate)
}

// The stable build bundles exactly the same DxO-derived assets as preview and is
// only handed to a known group of people, so it needs the long-term signing
// identity but not the public redistribution record in asset-clearance.json.
// That file stays "pending": this build is not offered to an unspecified audience.
// [SELF-IMPOSED] 只检查本机是否配好了签名材料；删掉后 assembleStable 仍可构建，
// 只是缺少签名时会得到一个装不上的未签名包。
val verifyStableSigningGate = tasks.register("verifyStableSigningGate") {
    group = "verification"
    description = "Require the long-term release key for the closed-group stable build."
    doLast {
        check(publicSigningReady && publicSigningStore?.isFile == true) {
            "Stable signing is not configured. Create .cxo-release-signing.properties from its example and keep the keystore backed up privately."
        }
    }
}
tasks.matching { it.name == "assembleStable" || it.name == "packageStable" }.configureEach {
    dependsOn(verifyStableSigningGate)
}

androidComponents.onVariants { variant ->
    if (variant.buildType == "publicBeta") {
        variant.outputs.forEach { output ->
            output.versionCode.set(31)
            output.versionName.set("0.8.0-beta01")
        }
    }
    if (variant.buildType == "stable") {
        variant.outputs.forEach { output ->
            output.versionCode.set(32)
            output.versionName.set("1.0.0")
        }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(stageProductFilmAssets) { it.assetsDirectory }
    variant.sources.assets?.addGeneratedSourceDirectory(stageBundledGrainAsset) { it.assetsDirectory }
}

dependencies {
    implementation(project(":glass-catalog"))
    implementation(project(":core"))
    implementation(project(":android-adapter"))
}
