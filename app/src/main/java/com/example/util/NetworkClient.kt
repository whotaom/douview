package com.example.util

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全局共享网络客户端提供者：
 * 1. 统一复用单套 ConnectionPool 与 Dispatcher，减少 TLS 握手、Socket 与线程开销；
 * 2. 提供带指数退避重试的 okHttpClient（更新检测等关键接口）与快速失败的 fastOkHttpClient（切台取流与频道测活）。
 */
object NetworkClient {

    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 15L
    const val WRITE_TIMEOUT_SECONDS = 10L
    const val MAX_RETRIES = 3
    const val INITIAL_RETRY_INTERVAL_MS = 800L

    private val sharedConnectionPool = ConnectionPool(5, 3, TimeUnit.MINUTES)
    private val sharedDispatcher = Dispatcher()

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

    /** 关键低频接口客户端（含 5xx/IO 重试） */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryInterceptor())
            .build()
    }

    /** 切台取流与批量测活专用客户端（共享连接池但快速失败，避免阻塞切台） */
    val fastOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
