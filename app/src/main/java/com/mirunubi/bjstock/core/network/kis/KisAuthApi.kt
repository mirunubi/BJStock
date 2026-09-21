package com.mirunubi.bjstock.core.network.kis

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface KisAuthApi {
    @Headers("Content-Type: application/json")
    @POST
    suspend fun issueToken(
        @Url url: String,
        @Body request: KisTokenRequestDto,
    ): KisTokenResponseDto
}
