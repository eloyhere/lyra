package com.eloykim.lyra.amp.protocol.database.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseCoreTest {

    @Test
    fun `schema 声明内核表与元数据表`() {
        assertTrue(Schema.contains("CREATE TABLE"))
        assertTrue(Schema.contains("objects"))
        assertTrue(Schema.contains("processes"))
        assertTrue(Schema.contains("threads"))
        assertTrue(MetaSchema.contains("CREATE TABLE IF NOT EXISTS meta"))
        assertTrue(MetaSchema.contains("value TEXT"))
    }

    @Test
    fun `SQL 参数按参考实现归一化`() {
        assertEquals(SqlParameter.Integral(1), toSqlParameter(true))
        assertEquals(SqlParameter.Integral(0), toSqlParameter(false))
        assertEquals(SqlParameter.Text("x"), toSqlParameter("x"))
        assertEquals(SqlParameter.Integral(42), toSqlParameter(42))
        assertEquals(SqlParameter.Real(0.5), toSqlParameter(0.5))
        assertEquals(SqlParameter.Null, toSqlParameter(null))
    }

    @Test
    fun `非法参数与标识符失败关闭`() {
        assertThrows(IllegalArgumentException::class.java) { toSqlParameter(Any()) }
        assertThrows(IllegalArgumentException::class.java) { toSqlParameter(Double.NaN) }
        assertEquals("objects", assertSqlIdentifier("objects"))
        assertThrows(IllegalArgumentException::class.java) { assertSqlIdentifier("objects; DROP TABLE x") }
        assertThrows(IllegalArgumentException::class.java) { assertSqlIdentifier("1objects") }
    }
}
