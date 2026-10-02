package com.mirunubi.bjstock.probe.intraday

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level isolation guards for the debug-only probe (12-B2-A static safety). */
class ProbeIsolationStaticTest {
    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/debug").isDirectory }
    private val probeDir = File(moduleDir, "src/debug/java/com/mirunubi/bjstock/probe/intraday")
    private val debugManifest = File(moduleDir, "src/debug/AndroidManifest.xml")
    private val mainManifest = File(moduleDir, "src/main/AndroidManifest.xml")

    private val sources: Map<String, String> by lazy {
        probeDir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
    }

    private fun code(text: String): String =
        text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun probeSourcesExist() {
        assertTrue(sources.keys.containsAll(listOf("IntradayProbeService.kt", "IntradayProbeActivity.kt", "ProbeAllowlist.kt")))
    }

    // 17. no business domain object
    @Test
    fun probeImportsNoBusinessDomainOrPersistence() {
        val forbiddenImports = listOf(
            "com.mirunubi.bjstock.core.paper",
            "com.mirunubi.bjstock.core.analytics",
            "com.mirunubi.bjstock.core.forward",
            "com.mirunubi.bjstock.core.strategy",
            "com.mirunubi.bjstock.core.factor",
            "com.mirunubi.bjstock.core.database",
            "com.mirunubi.bjstock.core.audit",
            "com.mirunubi.bjstock.core.theme",
            "com.mirunubi.bjstock.core.ai",
            "com.mirunubi.bjstock.core.marketdata",
            "com.mirunubi.bjstock.core.instrument",
            "com.mirunubi.bjstock.core.error",
            "com.mirunubi.bjstock.feature.",
            "com.mirunubi.bjstock.ui.",
            "androidx.room",
            "androidx.work",
            "android.app.AlarmManager",
            "android.util.Log",
        )
        val allowedKis = setOf(
            "com.mirunubi.bjstock.core.kis.KisCredentials",
            "com.mirunubi.bjstock.core.kis.KisCredentialStore",
            "com.mirunubi.bjstock.core.kis.KisEnvironment",
            "com.mirunubi.bjstock.core.kis.KisTokenStore",
        )
        sources.forEach { (name, text) ->
            val imports = text.lines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
            imports.forEach { imported ->
                assertFalse("$name imports $imported", forbiddenImports.any { imported.startsWith(it) })
                if (imported.startsWith("com.mirunubi.bjstock.")) {
                    assertTrue("$name imports non-allowlisted app class $imported", imported in allowedKis)
                }
            }
        }
    }

    @Test
    fun probeHasNoTradingOrProductionCallPath() {
        sources.forEach { (name, text) ->
            val body = code(text)
            assertFalse("$name references PRODUCTION", body.contains("KisEnvironment.PRODUCTION"))
            assertFalse("$name reads the selected environment", body.contains("selectedEnvironment"))
            assertFalse("$name references the production REST host", body.contains("openapi.koreainvestment.com"))
            assertFalse("$name references the production WS port", body.contains("21000"))
            assertFalse("$name references the integrated/NXT stream TRs", Regex("H0(NX|UN)CNT0").containsMatchIn(body))
            Regex("(?i)\\b(order|buy|sell|execution|position|cash_?ledger|balance|portfolio)\\w*\\s*\\(").findAll(body).forEach {
                throw AssertionError("$name has trading-domain call-like token '${it.value}'")
            }
            if (name != "ProbeScope.kt") assertFalse("$name contains a /trading/ literal", body.contains("/trading/"))
        }
        assertTrue(code(sources.getValue("ProbeScope.kt")).contains("TRADING_MARKER = \"/trading/\""))
    }

    @Test
    fun probeHasNoLoggingWakeLockAlarmOrAutoStart() {
        sources.forEach { (name, text) ->
            val body = code(text)
            listOf("Log.", "println(", "printStackTrace", "newWakeLock", "AlarmManager", "setExact", "WorkManager",
                "BOOT_COMPLETED", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "START_STICKY ", "START_REDELIVER_INTENT",
                "HttpLoggingInterceptor").forEach { token ->
                assertFalse("$name contains $token", body.contains(token))
            }
        }
        assertTrue(sources.getValue("IntradayProbeService.kt").contains("return START_NOT_STICKY"))
    }

    // §19: the exported debug Activity cannot auto-start the probe or broaden its scope through extras.
    @Test
    fun exportedActivityCannotAutoStartOrBroadenScope() {
        val activity = code(sources.getValue("IntradayProbeActivity.kt"))
        listOf("getIntent", "intent.", "Extra(", "extras", "Credential", "appKey", "appSecret", "Secret", "token").forEach {
            assertFalse("activity references $it", activity.contains(it))
        }
        assertEquals(1, Regex("startForegroundService").findAll(activity).count())
        assertTrue(Regex("onStart = \\{ restMinuteBars ->\\s*ContextCompat\\.startForegroundService").containsMatchIn(activity))

        val service = code(sources.getValue("IntradayProbeService.kt"))
        assertEquals(1, Regex("get\\w*Extra\\(").findAll(service).count())
        assertTrue(service.contains("getBooleanExtra(EXTRA_REST_MINUTE_BARS, false)"))
        assertFalse(service.contains("getStringExtra") || service.contains("getIntExtra") || service.contains("getSerializableExtra"))
    }

    // F-6: every foreground exit removes the notification; nothing detaches it.
    @Test
    fun serviceRemovesNotificationOnEveryForegroundExit() {
        val service = code(sources.getValue("IntradayProbeService.kt"))
        assertTrue(service.contains("ServiceCompat.STOP_FOREGROUND_REMOVE"))
        assertFalse(service.contains("STOP_FOREGROUND_DETACH"))
        assertTrue(service.contains("notifications.shutdown()"))
        assertFalse(service.contains("collectLatest"))
    }

    // F-3: wall-clock and timezone change observation is registered by the platform observer.
    @Test
    fun platformObservesClockAndTimezoneChanges() {
        val platform = code(sources.getValue("AndroidProbePlatform.kt"))
        assertTrue(platform.contains("addAction(Intent.ACTION_TIME_CHANGED)"))
        assertTrue(platform.contains("addAction(Intent.ACTION_TIMEZONE_CHANGED)"))
        assertTrue(platform.contains("SystemClock.currentNetworkTimeClock()"))
        assertFalse(platform.contains("SntpClient") || platform.contains("pool.ntp") || platform.contains("time.google"))
    }

    @Test
    fun probeHasNoHardcodedSecrets() {
        val longOpaqueLiteral = Regex("\"(?=[^\"]*\\d)(?=[^\"]*[A-Za-z])[A-Za-z0-9+/=]{24,}\"")
        val assignment = Regex("(?i)(appkey|appsecret|secretkey|approval_key|access_token)\"?\\s*[:=]\\s*\"[^\"]+\"")
        sources.forEach { (name, text) ->
            assertFalse("$name has an opaque literal", longOpaqueLiteral.containsMatchIn(text))
            assertFalse("$name assigns a literal credential", assignment.containsMatchIn(text))
        }
    }

    @Test
    fun debugManifestDeclaresOnlyTheApprovedProbeSurface() {
        val manifest = debugManifest.readText()
        listOf(
            "android.permission.FOREGROUND_SERVICE\"",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_NETWORK_STATE",
            "android:foregroundServiceType=\"specialUse\"",
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            "BJStock isolated intraday market-data feasibility measurement",
        ).forEach { assertTrue("missing $it", manifest.contains(it)) }
        listOf(
            "WAKE_LOCK", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "RECEIVE_BOOT_COMPLETED", "BOOT_COMPLETED", "intent-filter", "android.intent.category.LAUNCHER",
        ).forEach { assertFalse("debug manifest contains $it", manifest.contains(it)) }
        assertEquals(1, Regex("<service").findAll(manifest).count())
        assertTrue(Regex("<service[^>]*android:exported=\"false\"").containsMatchIn(manifest.replace("\n", " ")))
    }

    @Test
    fun mainManifestHasNoProbeSurface() {
        val manifest = mainManifest.readText()
        listOf("probe", "FOREGROUND_SERVICE", "specialUse", "POST_NOTIFICATIONS", "networkSecurityConfig").forEach {
            assertFalse("main manifest contains $it", manifest.contains(it))
        }
    }

    @Test
    fun cleartextExceptionIsLimitedToTheVirtualWebSocketHost() {
        val config = File(moduleDir, "src/debug/res/xml/probe_network_security_config.xml").readText()
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"false\""))
        val domains = Regex("<domain [^>]*>([^<]+)</domain>").findAll(config).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("ops.koreainvestment.com"), domains)
        assertTrue(config.contains("includeSubdomains=\"false\""))
    }
}
