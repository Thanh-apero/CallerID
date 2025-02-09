package com.apero.commons.sms.extensions

import kotlin.math.roundToInt

/**
 * Returns the closest number divisible by [multipleOf].
 */
fun Int.roundToClosestMultipleOf(multipleOf: Int = 1) = (toDouble() / multipleOf).roundToInt() * multipleOf
