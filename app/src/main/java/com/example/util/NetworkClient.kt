package com.example.util

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 军工级 OkHttpClient 网络提供者：
 * 具备连接超时 (ConnectTimeout)、读取超时 (ReadTimeout) 与写入超时设置，
 * 并配置针对网络闪断、丢包与5xx临时故障的智能重试拦截器 (RetryInterceptor)，
 * 全面提升直播流数据解析、接口请求与在线检测的稳定性。
 */
object NetworkClient {

    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 15L
    const val WRITE_TIMEOUT_SECONDS = 10L
    const val MAX_RETRIES = 3
    const val INITIAL_RETRY_INTERVAL_MS = 800L

    /**
     * 智能指数退避重试拦截器：
     * 捕获 IOException 与服务端瞬态故障 (HTTP 5xx)，平滑重试最多 3 次，
     * 遇到网络抖动时不中断直播解析流程。
     */
    class RetryInterceptor(
        private val maxRetries: Int = MAX_RETRIES,
        private val retryIntervalMs: Long = INITIAL_RETRY_INTERVAL_MS
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            var lastException: IOException? = null

            while (attempt < maxRetries) {
                try {
                    val response = chain.proceed(request)
                    if (response.isSuccessful || response.code < 500) {
                        return response
                    }
                    response.close()
                } catch (e: IOException) {
                    lastException = e
                }

                attempt++
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryIntervalMs * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            throw lastException ?: IOException("网络请求重试 $maxRetries 次后依然失败: ${request.url}")
        }
    }

    /**
     * 全局共享单例 OkHttpClient 实例
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryInterceptor())
            .build()
    }
}
