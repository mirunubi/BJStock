package com.mirunubi.bjstock.core.network.kis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KisTokenRequestDto(
    @SerialName("grant_type") val grantType: String,
    @SerialName("appkey") val appKey: String,
    @SerialName("appsecret") val appSecret: String,
)

@Serializable
data class KisTokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)
