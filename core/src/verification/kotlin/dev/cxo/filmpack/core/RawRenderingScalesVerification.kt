package dev.cxo.filmpack.core

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** Static original getters versus independent original Prepare matrices at intensity 100. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Expected original oracle TSV and output report" }
    val fixture = File(args[0]); val report = File(args[1])
    val lines = fixture.readLines()
    require(lines.first() == "token\tphoto\tgetter_scales\toptional_present\tnative_matrix")
    val providers = mutableSetOf<String>()
    val optionalProviders = mutableSetOf<String>()
    val contexts = mutableSetOf<Pair<String, String>>()
    var worst = 0f
    for (line in lines.drop(1).filter(String::isNotBlank)) {
        val row = line.split('\t'); require(row.size == 5)
        require(contexts.add(row[0] to row[1])) { "Duplicate native context" }
        providers += row[0]
        val vector = row[2].split(',').map(String::toFloat); require(vector.size == 3)
        val scales = RawRenderingScales(vector[0], vector[1], vector[2])
        val expectedPresent = when (row[3]) { "0" -> false; "1" -> true; else -> error("Invalid native presence flag") }
        check(scales.requiresColorStage == expectedPresent) { "${row[0]} optional presence differs" }
        if (expectedPresent) optionalProviders += row[0]
        val expected = row[4].split(',').map(String::toFloat); require(expected.size == 9)
        val actual = scales.defaultMatrix()
        for (i in actual.indices) {
            val error = abs(actual[i] - expected[i])
            check(error <= 2e-7f) { "${row[0]} ${row[1]} matrix[$i] differs by $error" }
            worst = max(worst, error)
        }
        // The caller owns its matrix; mutating it cannot affect a later bake.
        actual.fill(0f)
        check(scales.defaultMatrix().any { it != 0f })
    }
    check(contexts.size == 136 && providers.size == 68 && optionalProviders.size == 21)
    val sha = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    report.parentFile.mkdirs()
    report.writeText("{\"success\":true,\"providers\":68,\"contexts\":136,\"optional_providers\":21," +
        "\"max_matrix_error\":$worst,\"fixture_sha256\":\"$sha\",\"full_film_pipeline_validated\":false}\n")
    println("RAW rendering scales: 68 getters, 136 original matrices, 21 optional providers; max error $worst")
}
