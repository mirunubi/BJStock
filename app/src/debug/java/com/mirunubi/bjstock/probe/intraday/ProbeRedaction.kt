package com.mirunubi.bjstock.probe.intraday

import java.util.concurrent.CopyOnWriteArraySet

/** Holds a credential-derived value. [toString] never reveals it. */
class ProbeSecret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = ProbeRedaction.REDACTED
}

object ProbeRedaction {
    const val REDACTED = "[REDACTED]"

    private val FORBIDDEN_KEYS = setOf(
        "appkey",
        "app_key",
        "appsecret",
        "app_secret",
        "secretkey",
        "secret_key",
        "approval_key",
        "approvalkey",
        "authorization",
        "access_token",
        "accesstoken",
        "token",
        "bearer",
        "headers",
        "request_headers",
    )

    fun isForbiddenKey(key: String): Boolean =
        key.lowercase().replace('-', '_') in FORBIDDEN_KEYS

    /** Free text that names any forbidden key at all is withheld whole, even after scrubbing. */
    fun mentionsForbiddenKey(text: String): Boolean {
        val normalized = text.lowercase().replace('-', '_')
        return FORBIDDEN_KEYS.any { normalized.contains(it) }
    }
}

/**
 * Last line of defence before evidence reaches disk: every registered secret value is replaced in each
 * serialized line, and the number of replacements is counted so the summary can report it.
 */
class SecretScrubber {
    private val secrets = CopyOnWriteArraySet<String>()

    @Volatile
    var replacements: Long = 0
        private set

    fun register(secret: String?) {
        if (!secret.isNullOrEmpty() && secret.length >= MIN_SECRET_LENGTH) secrets.add(secret)
    }

    fun clear() = secrets.clear()

    fun scrub(line: String): String {
        var result = line
        for (secret in secrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, ProbeRedaction.REDACTED)
                synchronized(this) { replacements++ }
            }
        }
        return result
    }

    private companion object {
        const val MIN_SECRET_LENGTH = 8
    }
}
