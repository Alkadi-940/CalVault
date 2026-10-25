package dev.calvault.core

import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {
    @Test fun precedenceAndDecimals() {
        assertEquals("14", Calculator.evaluate("2+3×4"))
        assertEquals("0.3", Calculator.evaluate("0.1+0.2"))
        assertEquals("-8", Calculator.evaluate("-2*4"))
        assertEquals("5", Calculator.evaluate("2--3"))
        assertEquals("0", Calculator.evaluate("-0"))
        assertEquals("0.5", Calculator.evaluate(".5"))
        assertEquals("200.1", Calculator.evaluate("200+10%"))
        assertEquals("1000", Calculator.evaluate("1E+3"))
    }
    @Test fun malformedAndUnboundedInputRejected() {
        listOf("", "1/0", "1+", "1.2.3", "hello", "1E9999", "9".repeat(129)).forEach { expression ->
            assertThrows(Exception::class.java) { Calculator.evaluate(expression) }
        }
    }
}
