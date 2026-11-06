package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Native getter/provider outputs are the oracle; static endpoints and offsets are inputs. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected JVM-only oracle fixture, product catalog, and audited identity index" }
    val productBytes = File(args[1]).readBytes()
    val product = productBytes.inputStream().use(RawNegativeProviderCatalog::read)
    val identityLines = File(args[2]).readLines()
    check(identityLines.first() == "token\tfilm_id\tphoto\tprovider_prefix\tapply_prefix\tapply_status")
    val identities = identityLines.drop(1).filter(String::isNotBlank).map { it.split('\t') }
        .groupBy { it[0] }.mapValues { (_, rows) -> rows.map { it[1] }.toSet().single() }
    check(identities.size == 68 && identities.values.toSet() == product.ids)
    check(product.ids.count { requireNotNull(product.film(it)).requiresAdditionalColorStage } == 21)
    val input = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val magic = ByteArray(8).also(input::get)
    check(magic.contentEquals("CXOTFID1".toByteArray()) && input.int == 1)
    fun matrix() = FloatArray(9) { input.float }
    fun label(): String {
        val length = input.int
        check(length in 1..256)
        return ByteArray(length).also(input::get).toString(Charsets.UTF_8)
    }
    fun exact(actual: FloatArray, expected: FloatArray, name: String) {
        check(actual.size == expected.size)
        check(actual.indices.all { actual[it].toRawBits() == expected[it].toRawBits() }) { "$name Float32 bits differ" }
    }
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Expected argument rejection" } }

    val profileCount = input.int
    check(profileCount == 3)
    val profiles = LinkedHashMap<Int, RawTargetFidelityProfile>()
    val endpoints = HashMap<Int, Pair<FloatArray, FloatArray>>()
    repeat(profileCount) {
        val id = input.int
        val low = input.float; val high = input.float
        val tungsten = matrix(); val daylight = matrix()
        check(id !in profiles)
        profiles[id] = RawTargetFidelityProfile(tungsten, daylight, low, high)
        endpoints[id] = tungsten.copyOf() to daylight.copyOf()
        // Constructor parameters and returned arrays are caller-owned.
        tungsten.fill(Float.NaN); daylight.fill(Float.NaN)
        profiles.getValue(id).matrixAt(low).fill(Float.NaN)
        exact(profiles.getValue(id).matrixAt(low), endpoints.getValue(id).first, "$id tungsten copy")
        exact(profiles.getValue(id).matrixAt(high), endpoints.getValue(id).second, "$id daylight copy")
    }
    check(profiles.keys == setOf(9009, 2066, 4073))
    data class StaticSpec(val provider: RawNegativeProviderSpec, val m1: FloatArray, val m2: FloatArray)
    val specCount = input.int
    check(specCount == 68)
    val specs = HashMap<String, StaticSpec>()
    repeat(specCount) {
        val token = label(); val body = input.int; val offset = input.float
        check(token !in specs)
        specs[token] = StaticSpec(RawNegativeProviderSpec(profiles.getValue(body), offset), matrix(), matrix())
    }

    val getterCount = input.int
    check(getterCount == 72)
    val getterTemperatures = HashMap<Int, MutableSet<Float>>()
    val getterIdentities = HashSet<String>()
    repeat(getterCount) {
        val body = input.int; val temperature = input.float; val tint = input.float; val preset = input.int
        val expected = matrix()
        check(tint.isFinite())
        check(getterIdentities.add("$body/$temperature/$tint/$preset"))
        exact(profiles.getValue(body).matrixAt(temperature), expected, "$body/$temperature/$tint/$preset native getter")
        getterTemperatures.getOrPut(body) { HashSet() }.add(temperature)
    }
    for (id in profiles.keys) {
        check(getterTemperatures.getValue(id) == setOf(2000f, 3425f, 3500f, 4012.5f, 4199f, 4600f, 5485f, 8000f))
    }

    val providerCount = input.int
    check(providerCount == 136)
    val seen = HashSet<String>()
    val photoCounts = HashMap<String, Int>()
    var maxPreError = 0f
    repeat(providerCount) {
        val name = label(); check(seen.add(name))
        val token = name.substringBefore('/')
        val spec = specs.getValue(token)
        photoCounts[token] = (photoCounts[token] ?: 0) + 1
        val temperature = input.float
        // These S1RII captures have the same source-camera and sensor bias.
        // Other source cameras must provide their own recovered exposure rule.
        val sensorExposureBiasEv = input.float
        val expectedProviderEv = input.float
        val expectedFidelity = matrix()
        val expectedRenderingEv = input.float
        val expectedPre = matrix(); val expectedPost = matrix()
        val providerEv = spec.provider.providerExposureEv(sensorExposureBiasEv)
        val fidelity = spec.provider.targetFidelity(temperature)
        exact(fidelity, expectedFidelity, "$name native provider fidelity")
        check(providerEv.toRawBits() == expectedProviderEv.toRawBits()) { "$name provider exposure differs" }
        val rendering = RawRenderingParameters.fromProvider(
            sensorExposureBiasEv, providerEv, fidelity, spec.m1, spec.m2,
        )
        check(rendering.renderingEv.toRawBits() == expectedRenderingEv.toRawBits()) { "$name rendering exposure differs" }
        exact(rendering.postMatrix(), expectedPost, "$name post matrix")
        val productFilm = requireNotNull(product.film(identities.getValue(token)))
        val productParameters = productFilm.parameters(temperature, sensorExposureBiasEv)
        check(productParameters.renderingEv.toRawBits() == expectedRenderingEv.toRawBits())
        exact(productParameters.postMatrix(), expectedPost, "$name packaged provider post")
        exact(productParameters.preMatrix(), rendering.preMatrix(), "$name packaged provider pre")
        val pre = rendering.preMatrix()
        val error = pre.indices.maxOf { abs(pre[it] - expectedPre[it]) }
        maxPreError = maxOf(maxPreError, error)
        exact(pre, expectedPre, "$name native pre matrix")
    }
    check(!input.hasRemaining())
    check(photoCounts.size == 68 && photoCounts.values.all { it == 2 })
    val example = profiles.getValue(9009)
    val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    rejects { RawTargetFidelityProfile(FloatArray(8), identity, 3425f, 4600f) }
    rejects { RawTargetFidelityProfile(identity, identity.copyOf().also { it[0] = Float.NaN }, 3425f, 4600f) }
    rejects { RawTargetFidelityProfile(identity, identity, 4600f, 3425f) }
    rejects { RawTargetFidelityProfile(identity, identity, 3425f, 3425f) }
    rejects { RawTargetFidelityProfile(identity, identity, 0f, 4600f) }
    rejects { RawTargetFidelityProfile(identity, identity, 3425f, Float.POSITIVE_INFINITY) }
    rejects { example.matrixAt(Float.NaN) }
    rejects { example.matrixAt(Float.POSITIVE_INFINITY) }
    rejects { example.matrixAt(Float.NEGATIVE_INFINITY) }
    rejects { example.matrixAt(-1f) }
    rejects { RawNegativeProviderSpec(example, Float.NaN) }
    rejects { RawNegativeProviderSpec(example, Float.NEGATIVE_INFINITY) }
    rejects { RawNegativeProviderSpec(example, 0f).providerExposureEv(Float.NaN) }
    rejects { RawNegativeProviderSpec(example, 0f).providerExposureEv(Float.POSITIVE_INFINITY) }
    rejects { RawNegativeProviderSpec(example, Float.MAX_VALUE).providerExposureEv(Float.MAX_VALUE) }
    rejects { RawNegativeProviderCatalog.read(productBytes.copyOf(productBytes.size - 1).inputStream()) }
    rejects { RawNegativeProviderCatalog.read((productBytes + byteArrayOf(0)).inputStream()) }
    rejects { RawNegativeProviderCatalog.read(productBytes.copyOf().also { it[8] = 99 }.inputStream()) }
    check(product.film("unknown-film") == null)
    // Offsets are not tied to the S1RII 0.6 bias or one captured exposure.
    val generic = RawNegativeProviderSpec(example, -.7f)
    check(generic.providerExposureEv(1f).toRawBits() == .3f.toRawBits())
    check(generic.providerExposureEv(-1f).toRawBits() == (-1.7f).toRawBits())
    println("RAW target fidelity: $profileCount profiles, $specCount static specs, $getterCount native getter cases and $providerCount native provider contexts; fidelity/provider EV/rendering EV/post exact, pre max=$maxPreError; packaged catalog parameters exact to static construction, 21 extra-stage guards; copying/invalid-input/catalog-boundary checks passed.")
}
