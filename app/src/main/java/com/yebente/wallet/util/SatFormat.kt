package com.yebente.wallet.util

import java.util.Locale

object SatFormat {
  fun sats(value: Long): String = String.format(Locale.US, "%,d", value)

  fun signedSats(value: Long): String {
    val sign = if (value < 0) "-" else "+"
    return sign + sats(kotlin.math.abs(value))
  }
}
