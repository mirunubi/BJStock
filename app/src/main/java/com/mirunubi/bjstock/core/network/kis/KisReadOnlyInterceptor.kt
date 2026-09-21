package com.mirunubi.bjstock.core.network.kis

import com.mirunubi.bjstock.core.kis.market.KisReadOnlyGuard
import okhttp3.Interceptor
import okhttp3.Response

class KisReadOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        KisReadOnlyGuard.assertAllowed(chain.request().url.encodedPath)
        return chain.proceed(chain.request())
    }
}
