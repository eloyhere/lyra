package com.eloykim.lyra.amp.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exports canonical strings and digests for every protocol example.
 *
 * `tools/amp-conformance/compare-canonical.mjs` recomputes them with the
 * reference implementation and fails on any difference, so digest drift is
 * caught before it reaches a signed object.
 */
class CanonicalExportTest {

    @Test
    fun `导出协议示例的 canonical 与 digest`() {
        val examples = File("src/test/resources/amp/examples")
        assertTrue("缺少示例目录 ${examples.absolutePath}", examples.isDirectory)
        val entries = examples.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val value = JsonParser.parse(file.readText())
                jsonObj(
                    "file" to JsonValue.Str(file.name),
                    "canonical" to JsonValue.Str(CanonicalJson.encode(value)),
                    "digest" to JsonValue.Str(CanonicalJson.digest(value)),
                )
            }
        val output = File("build/amp-canonical-kotlin.json")
        output.parentFile?.mkdirs()
        output.writeText(CanonicalJson.encode(JsonValue.Arr(entries)))
    }
}
