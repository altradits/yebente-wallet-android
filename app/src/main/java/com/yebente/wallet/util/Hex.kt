package com.yebente.wallet.util

fun ByteArray.toHex(): String {
  val chars = CharArray(size * 2)
  val digits = "0123456789abcdef"
  for (i in indices) {
    val v = this[i].toInt() and 0xFF
    chars[i * 2] = digits[v ushr 4]
    chars[i * 2 + 1] = digits[v and 0x0F]
  }
  return String(chars)
}

fun String.hexToBytes(): ByteArray {
  val clean = trim()
  require(clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
    "Invalid hex string"
  }
  return ByteArray(clean.length / 2) { i ->
    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
  }
}
