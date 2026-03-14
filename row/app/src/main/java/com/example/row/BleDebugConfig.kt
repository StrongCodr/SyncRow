package com.example.row

/**
 * BLE diagnostics flags.
 * - DEBUG_BLE is enabled in debug builds only.
 * - DEBUG_BLE_ACCEPT_ALL bypasses scan filtering to debug "no devices found" incidents.
 * - DEBUG_BLE_FULL_PII must remain false by default.
 * - Only enable full PII temporarily during active troubleshooting.
 * - Revert temporary flag changes before shipping.
 * - Keep this as the single source of BLE debug behavior.
 */
val DEBUG_BLE = BuildConfig.DEBUG
const val DEBUG_BLE_ACCEPT_ALL = false
const val DEBUG_BLE_FULL_PII = false

fun bleMacForLog(mac: String?): String {
    if (mac.isNullOrBlank()) return "<null>"
    if (DEBUG_BLE_FULL_PII) return mac
    val parts = mac.split(":")
    if (parts.size != 6) return "<invalid-mac>"
    return "${parts[0]}:${parts[1]}:${parts[2]}:xx:xx:${parts[5]}"
}

fun bleNameForLog(name: String?): String {
    if (name.isNullOrBlank()) return "<unknown>"
    val clean = name.replace(Regex("[^\\p{Print}]"), "")
    return if (DEBUG_BLE_FULL_PII) clean else clean.take(16)
}
