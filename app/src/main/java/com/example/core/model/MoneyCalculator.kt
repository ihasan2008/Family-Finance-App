package com.example.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * Decimal-safe monetary calculations using [BigDecimal] and integer minor currency units (`Long`).
 * Never accumulates monetary balances with IEEE-754 floating point arithmetic.
 */
object MoneyCalculator {

    private const val MAX_MAJOR_AMOUNT = "1000000000"

    fun fractionDigits(currencyCode: String): Int {
        val code = currencyCode.trim().uppercase(Locale.US).ifBlank { "USD" }
        return try {
            val digits = Currency.getInstance(code).defaultFractionDigits
            if (digits in 0..4) digits else 2
        } catch (_: IllegalArgumentException) {
            2
        }
    }

    fun currencySymbol(currencyCode: String): String {
        val code = currencyCode.trim().uppercase(Locale.US).ifBlank { "USD" }
        if (code == "BDT") return "৳"
        return try {
            Currency.getInstance(code).getSymbol(Locale.US)
        } catch (_: IllegalArgumentException) {
            code
        }
    }

    /**
     * Parses a user-entered decimal string into exact integer minor units (`Long`).
     * Rejects non-positive values, malformed numbers, excessive decimal digits, and overflow.
     */
    fun parseDecimalToMinorUnits(
        rawInput: String,
        currencyCode: String,
        allowZero: Boolean = false
    ): Result<Long> {
        val normalized = rawInput.trim().replace(",", "")
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a valid amount."))
        }
        val decimal = normalized.toBigDecimalOrNull()
            ?: return Result.failure(IllegalArgumentException("Amount must be a valid decimal number."))

        if (decimal < BigDecimal.ZERO || (!allowZero && decimal.compareTo(BigDecimal.ZERO) == 0)) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero."))
        }
        if (decimal > BigDecimal(MAX_MAJOR_AMOUNT)) {
            return Result.failure(IllegalArgumentException("Amount exceeds maximum limit."))
        }

        val scale = fractionDigits(currencyCode)
        val stripped = decimal.stripTrailingZeros()
        val effectiveScale = maxOf(0, stripped.scale())
        if (effectiveScale > scale) {
            return Result.failure(
                IllegalArgumentException(
                    if (scale == 0) "$currencyCode does not support decimal fractions."
                    else "Use at most $scale decimal places for $currencyCode."
                )
            )
        }

        return try {
            val minor = decimal
                .movePointRight(scale)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
            Result.success(minor)
        } catch (e: ArithmeticException) {
            Result.failure(IllegalArgumentException("Amount precision is invalid for $currencyCode.", e))
        }
    }

    fun minorUnitsToBigDecimal(amountMinor: Long, currencyCode: String): BigDecimal {
        val scale = fractionDigits(currencyCode)
        return BigDecimal.valueOf(amountMinor, scale)
    }

    fun minorUnitsToDouble(amountMinor: Long, currencyCode: String): Double =
        minorUnitsToBigDecimal(amountMinor, currencyCode).toDouble()

    fun doubleToMinorUnits(amount: Double, currencyCode: String): Long {
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return 0L
        val scale = fractionDigits(currencyCode)
        return BigDecimal.valueOf(amount)
            .movePointRight(scale)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    fun minorUnitsToInputString(amountMinor: Long, currencyCode: String): String {
        val scale = fractionDigits(currencyCode)
        return BigDecimal.valueOf(abs(amountMinor), scale)
            .setScale(scale, RoundingMode.UNNECESSARY)
            .toPlainString()
    }

    fun formatMinorUnits(
        amountMinor: Long,
        currencyCode: String,
        prefixSign: String = ""
    ): String {
        val cleanCode = currencyCode.trim().uppercase(Locale.US).ifBlank { "USD" }
        val scale = fractionDigits(cleanCode)
        val value = minorUnitsToBigDecimal(amountMinor, cleanCode)
        val formatted = if (cleanCode == "BDT") {
            val absNum = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = scale
                maximumFractionDigits = scale
                isGroupingUsed = true
            }.format(value.abs())
            if (amountMinor < 0L) "-৳$absNum" else "৳$absNum"
        } else {
            try {
                val nf = NumberFormat.getCurrencyInstance(Locale.US)
                nf.currency = Currency.getInstance(cleanCode)
                nf.minimumFractionDigits = scale
                nf.maximumFractionDigits = scale
                nf.format(value)
            } catch (_: Exception) {
                "$cleanCode ${value.setScale(scale, RoundingMode.HALF_UP).toPlainString()}"
            }
        }
        return if (prefixSign.isNotEmpty() && amountMinor > 0L && !formatted.startsWith(prefixSign)) {
            "$prefixSign$formatted"
        } else {
            formatted
        }
    }

    /**
     * Formats monetary values cleanly for PDF canvas rendering: uses `৳` when the device font
     * provides the Taka glyph and falls back cleanly to `BDT ` when unavailable.
     */
    fun formatForPdf(
        amountMinor: Long,
        currencyCode: String,
        supportsTakaGlyph: Boolean = true,
        prefixSign: String = ""
    ): String {
        val formatted = formatMinorUnits(amountMinor, currencyCode, prefixSign)
        val cleanCode = currencyCode.trim().uppercase(Locale.US)
        return if (cleanCode == "BDT" && !supportsTakaGlyph) {
            formatted.replace("৳", "BDT ")
        } else {
            formatted
        }
    }

    fun sumMinorUnits(values: Iterable<Long>): Long {
        var total = 0L
        for (value in values) {
            total += value
        }
        return total
    }

    fun percentageShare(partMinor: Long, totalMinor: Long): Float {
        if (partMinor <= 0L || totalMinor <= 0L) return 0f
        return BigDecimal.valueOf(partMinor)
            .divide(BigDecimal.valueOf(totalMinor), 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
