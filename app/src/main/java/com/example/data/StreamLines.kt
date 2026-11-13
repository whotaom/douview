package com.example.data

/**
 * 直播源主备双线路统一配置
 * - 测活、取流、中控台线路切换与远程导入全部共享此单一真相来源
 */
object StreamLines {
    const val LINE_1 = "https://tv.fuxiaoya.com"   // 主线
    const val LINE_2 = "https://dy.whomao.com"     // 线路二

    fun baseUrl(line: Int): String =
        if (line == 2) LINE_2 else LINE_1

    fun detectLine(urlOrBase: String?): Int =
        if (urlOrBase?.contains("whomao.com", ignoreCase = true) == true) 2 else 1
}
