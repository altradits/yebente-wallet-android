package com.yebente.wallet.util

object TimeAgo {
  fun of(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - timestampMs).coerceAtLeast(0)
    val seconds = diff / 1000
    if (seconds < 10) return "just now"
    if (seconds < 60) return "$seconds sec ago"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes min ago"
    val hours = minutes / 60
    if (hours < 24) return "$hours hr ago"
    val days = hours / 24
    if (days < 7) return if (days == 1L) "1 day ago" else "$days days ago"
    val weeks = days / 7
    if (weeks < 5) return if (weeks == 1L) "1 week ago" else "$weeks weeks ago"
    val months = days / 30
    if (months < 12) return if (months == 1L) "1 month ago" else "$months months ago"
    val years = days / 365
    return if (years == 1L) "1 year ago" else "$years years ago"
  }
}
