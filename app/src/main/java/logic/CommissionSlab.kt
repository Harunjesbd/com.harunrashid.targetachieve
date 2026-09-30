package com.harunrashid.targetachieve.logic

data class SlabRow(
    val percent: Double,
    val minAed: Double,
    val maxAed: Double
)

object CommissionSlab {

    val TABLE = listOf(
        SlabRow(5.00, 95.00, 139.99),
        SlabRow(7.50, 140.00, 164.99),
        SlabRow(10.00, 165.00, 189.99),
        SlabRow(12.50, 190.00, 214.99),
        SlabRow(15.00, 215.00, 239.99),
        SlabRow(17.50, 240.00, 264.99),
        SlabRow(20.00, 265.00, 289.99),
        SlabRow(22.50, 290.00, 314.99),
        SlabRow(25.00, 315.00, 339.99),
        SlabRow(27.50, 340.00, 369.99),
        SlabRow(30.00, 370.00, 394.99),
        SlabRow(32.50, 395.00, 419.99),
        SlabRow(35.00, 420.00, 534.99),
        SlabRow(36.00, 535.00, 649.99),
        SlabRow(37.00, 650.00, 799.99)
    )

    fun percentFor(amount: Double): Double {
        if (amount < TABLE.first().minAed) return 0.0
        for (row in TABLE) {
            if (amount in row.minAed..row.maxAed) return row.percent
        }
        return TABLE.last().percent
    }

    fun nextSlab(currentAmount: Double): Pair<Double, Double>? {
        val currentPercent = percentFor(currentAmount)
        val currentIndex = TABLE.indexOfFirst { it.percent == currentPercent }
        val nextIndex = currentIndex + 1
        if (nextIndex >= TABLE.size) return null
        val next = TABLE[nextIndex]
        return Pair(next.percent, next.minAed)
    }
}