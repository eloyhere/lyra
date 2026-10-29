package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTest {

    @Test
    fun `预算超限在执行前抛出`() {
        val dispatcher = Dispatcher(Budget(tokens = 100))
        var executed = 0

        dispatcher.run(CallCost(tokens = 60)) { executed += 1 }
        assertEquals(1, executed)

        val error = assertThrows(BudgetExceeded::class.java) {
            dispatcher.run(CallCost(tokens = 60)) { executed += 1 }
        }
        assertEquals(BudgetDimension.TOKENS, error.dimension)
        assertEquals(100.0, error.limit, 0.0)
        assertEquals(120.0, error.observed, 0.0)
        assertEquals("budget_exceeded", error.code)
        assertEquals("执行必须发生在扣费之后", 1, executed)

        // 失败的那次扣费不记账。
        assertEquals(Budget(tokens = 60, calls = 1, ms = 0), dispatcher.usage())
        // 参考实现用 (lease ?: 0) 计算剩余：未声明的维度剩余为 0。
        assertEquals(Budget(tokens = 40, calls = 0, ms = 0), dispatcher.remaining())
    }

    @Test
    fun `嵌套调用共享同一计数器`() {
        val dispatcher = Dispatcher(Budget(calls = 2))
        var innermostRan = false

        assertThrows(BudgetExceeded::class.java) {
            dispatcher.run(CallCost(calls = 1)) {
                dispatcher.run(CallCost(calls = 1)) { /* 第二次调用，仍在预算内 */ }
                dispatcher.run(CallCost(calls = 1)) { innermostRan = true }
            }
        }

        assertTrue("超限的内层调用不能执行", !innermostRan)
        assertEquals(2, dispatcher.usage().calls)
        assertEquals(0, dispatcher.remaining().calls)
    }

    @Test
    fun `默认每次 run 计一次调用`() {
        val dispatcher = Dispatcher(Budget(calls = 1))
        dispatcher.run { }
        assertThrows(BudgetExceeded::class.java) { dispatcher.run { } }
        assertEquals(1, dispatcher.usage().calls)
        assertEquals(Budget(tokens = 0, calls = 1, ms = 0), dispatcher.usage())
    }

    @Test
    fun `无上限租约仍然记录用量`() {
        val dispatcher = Dispatcher(Budget())
        dispatcher.run(CallCost(tokens = 999, ms = 250)) { }
        assertEquals(Budget(tokens = 999, calls = 1, ms = 250), dispatcher.usage())
        // 参考实现：缺失维度按 0 计算剩余，不会出现负数。
        assertEquals(Budget(tokens = 0, calls = 0, ms = 0), dispatcher.remaining())
    }

    @Test
    fun `毫秒与调用维度各自独立计费`() {
        val dispatcher = Dispatcher(Budget(tokens = 10, calls = 2, ms = 500))
        dispatcher.run(CallCost(calls = 2, ms = 200)) { }
        assertEquals(Budget(tokens = 0, calls = 2, ms = 200), dispatcher.usage())

        val error = assertThrows(BudgetExceeded::class.java) {
            dispatcher.run(CallCost(calls = 0, ms = 400)) { }
        }
        assertEquals(BudgetDimension.MS, error.dimension)
        assertEquals(500.0, error.limit, 0.0)
        assertEquals(600.0, error.observed, 0.0)
    }
}
