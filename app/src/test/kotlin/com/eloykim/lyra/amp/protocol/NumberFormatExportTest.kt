package com.eloykim.lyra.amp.protocol

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Test
import java.io.File

/** Exports tricky doubles for comparison with `JSON.stringify`. */
class NumberFormatExportTest {

    @Test
    fun `导出数字格式化样本`() {
        val values = listOf(
            0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.3, 1.0 / 3.0,
            1e-6, 1e-7, 1e-8, 1e-5, 1e20, 1e21, 1e22,
            9_007_199_254_740_991.0, 9_007_199_254_740_992.0,
            123_456_789.123_456, 0.300_000_000_000_000_04,
            1.797_693_134_862_315_7e308, 5e-324, 2.225_073_858_507_201_4e-308,
            1e-323, 1e-322, 1.234_567_890_123_456_7,
            -1.5e-7, -2.5e21, 1.5e-6, 12.0, 1200.0, 0.000_12,
        )
        val entries = values.map { value ->
            JsonValue.Obj(
                linkedMapOf(
                    "value" to JsonValue.Num(value),
                    "formatted" to JsonValue.Str(CanonicalJson.formatNumber(value)),
                ),
            )
        }
        val output = File("build/amp-numbers-kotlin.json")
        output.parentFile?.mkdirs()
        output.writeText(CanonicalJson.encode(JsonValue.Arr(entries)))
    }
}
