package com.harunrashid.targetachieve.logic

/**
 * কমিশন স্ল্যাবের টেবিল। কোম্পানি স্ল্যাব বদলালে শুধু TABLE-এর সারি বদলান।
 * প্রতিটি সারি: SlabRow(কমিশন%, দৈনিক গড়ের সর্বনিম্ন AED, সর্বোচ্চ AED)
 */
data class SlabRow(val percent: Double, val minAed: Double, val maxAed: Double)

object CommissionSlab {

    val TABLE: List<SlabRow> = listOf(
        SlabRow(5.0,   95.0,  139.99),
        SlabRow(7.5,  140.0,  164.99),
        SlabRow(10.0, 165.0,  189.99),
        SlabRow(12.5, 190.0,  214.99),
        SlabRow(15.0, 215.0,  239.99),
        SlabRow(17.5, 240.0,  264.99),
        SlabRow(20.0, 265.0,  289.99),
        SlabRow(22.5, 290.0,  314.99),
        SlabRow(25.0, 315.0,  339.99),
        SlabRow(27.5, 340.0,  369.99),
        SlabRow(30.0, 370.0,  394.99),
        SlabRow(32.5, 395.0,  419.99),
        SlabRow(35.0, 420.0,  534.99),
        SlabRow(36.0, 535.0,  649.99),
        SlabRow(37.0, 650.0,  799.99)
    )

    /** দৈনিক গড় অনুযায়ী কমিশন %। প্রথম স্ল্যাবের নিচে হলে 0 */
    fun percentFor(amount: Double): Double {
        var result = 0.0
        for (row in TABLE) {
            if (amount >= row.minAed) result = row.percent
        }
        return result
    }

    /** পরের স্ল্যাব: Pair(কমিশন%, কত AED গড় লাগবে)। শেষ স্ল্যাবে থাকলে null */
    fun nextSlab(currentAmount: Double): Pair<Double, Double>? {
        val next = TABLE.firstOrNull { it.minAed > currentAmount } ?: return null
        return Pair(next.percent, next.minAed)
    }
}
