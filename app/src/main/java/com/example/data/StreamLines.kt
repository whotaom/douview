package com.example.data

/**
 * 直播源默认单线路统一配置
 * - 测活、取流、中控台与远程导入共享此默认主线配置
 */
object StreamLines {
    const val LINE_1 = "https://tv.fuxiaoya.com"
    const val DEFAULT_BASE_URL = LINE_1

    fun resolveBaseUrl(urlOrBase: String?): String {
        val trimmed = urlOrBase?.trim().orEmpty()
        if (trimmed.isBlank()) return DEFAULT_BASE_URL
        if (trimmed.contains("whomao.com", ignoreCase = true) ||
            trimmed.contains("douyin.com", ignoreCase = true) ||
            trimmed.contains(".m3u8", ignoreCase = true) ||
            trimmed.contains("rid=", ignoreCase = true)
        ) {
            return DEFAULT_BASE_URL
        }
        return try {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme
            val authority = uri.authority
            if (scheme.equals("https", ignoreCase = true) && !authority.isNullOrBlank()) {
                "$scheme://$authority"
            } else {
                DEFAULT_BASE_URL
            }
        } catch (_: Exception) {
            DEFAULT_BASE_URL
        }
    }
}
