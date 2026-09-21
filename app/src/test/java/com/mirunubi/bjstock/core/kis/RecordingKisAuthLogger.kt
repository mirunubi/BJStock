package com.mirunubi.bjstock.core.kis

class RecordingKisAuthLogger : KisAuthLogger {
    val messages = mutableListOf<String>()

    override fun info(message: String) {
        messages += message
    }

    fun assertNoSecrets(secrets: Collection<String>) {
        secrets.forEach { secret ->
            check(messages.none { it.contains(secret) }) {
                "Logger leaked a secret value"
            }
        }
    }
}
