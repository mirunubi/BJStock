package com.mirunubi.bjstock.core.kis

class KisAuthException(
    val publicMessage: String,
    val httpCode: Int? = null,
) : Exception(publicMessage)
