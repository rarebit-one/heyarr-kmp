package one.rarebit.heyarr.mobile

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Runs [interceptor] the way OkHttp runs it, inside a real client's chain, with [terminal]
 * standing in for the network: it gets exactly the request [interceptor] proceeded with,
 * and its response is what [interceptor] sees come back. Nothing leaves the JVM.
 *
 * A hand-written `Interceptor.Chain` cannot compile across OkHttp majors (5.5 added the
 * abstract `withDns`, which 4.x does not have), so the tests drive the real chain instead.
 */
internal fun runThrough(interceptor: Interceptor, request: Request, terminal: (Request) -> Response): Response =
    OkHttpClient.Builder()
        .addInterceptor(interceptor)
        .addInterceptor(Interceptor { chain -> terminal(chain.request()) })
        .build()
        .newCall(request)
        .execute()
