package h.Hchat.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块共享的 HTTP 资源。
 *
 * 模块里十几个子功能各自 `OkHttpClient.Builder().build()`，每个实例都自带
 * 一套 Dispatcher（线程池）和 ConnectionPool（空闲连接 + socket 缓冲）。
 * 这些客户端访问的域名高度重叠，独立实例无法复用连接，空闲时也各自占着内存。
 *
 * 统一改成从 [shared] 派生：OkHttp 的 `newBuilder()` 会复用同一个
 * ConnectionPool、Dispatcher 与 RouteDatabase，只覆盖超时等浅层配置，
 * 既有调用方的行为完全不变，但底层只保留一份连接与线程资源。
 */
object HchatHttp {

    /** 共享底座，只设置模块通用的默认超时。 */
    @JvmField
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}