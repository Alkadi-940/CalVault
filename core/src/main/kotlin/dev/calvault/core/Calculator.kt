package dev.calvault.core

import java.math.BigDecimal
import java.math.MathContext

/** Percent is a postfix /100: 200 + 10% = 200.1. Never evaluates executable code. */
object Calculator {
    private val precision = MathContext(16)
    fun evaluate(expression: String): String {
        require(expression.length in 1..128) { "Expression too long" }
        val parser = Parser(expression.replace('×', '*').replace('÷', '/').replace('−', '-'))
        val result = parser.sum()
        require(parser.atEnd()) { "Incomplete expression" }
        require(result.precision() <= 128 && kotlin.math.abs(result.scale()) <= 128) { "Number too large" }
        val normalized = result.stripTrailingZeros()
        return if (normalized.precision() - normalized.scale() > 16 || normalized.scale() > 12)
            normalized.toEngineeringString() else normalized.toPlainString()
    }

    private class Parser(private val text: String) {
        private var index = 0
        private fun skip() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun take(char: Char): Boolean {
            skip()
            return if (index < text.length && text[index] == char) { index++; true } else false
        }
        fun atEnd(): Boolean { skip(); return index == text.length }
        fun sum(): BigDecimal {
            var value = product()
            while (true) value = when {
                take('+') -> value.add(product(), precision)
                take('-') -> value.subtract(product(), precision)
                else -> return value
            }
        }
        private fun product(): BigDecimal {
            var value = unary()
            while (true) value = when {
                take('*') -> value.multiply(unary(), precision)
                take('/') -> value.divide(unary(), precision)
                else -> return value
            }
        }
        private fun unary(): BigDecimal {
            if (take('+')) return unary()
            if (take('-')) return unary().negate()
            skip()
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            require(index > start && index - start <= 40) { "Enter a number" }
            var value = text.substring(start, index).toBigDecimal()
            // Only generated scientific notation is accepted, with a tightly bounded exponent.
            if (index < text.length && text[index] in "Ee") {
                index++
                val exponentStart = index
                if (index < text.length && text[index] in "+-") index++
                while (index < text.length && text[index].isDigit()) index++
                val exponent = text.substring(exponentStart, index).toInt()
                require(exponent in -100..100) { "Number too large" }
                value = value.scaleByPowerOfTen(exponent)
            }
            while (take('%')) value = value.divide(BigDecimal(100), precision)
            return value
        }
    }
}
