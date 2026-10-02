package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisEnvironment

/**
 * Proof that the VIRTUAL-only gate passed. The only way to obtain one is [VirtualGatePass.evaluate];
 * every probe network component requires it. There is no fallback to any other environment.
 */
class VirtualGatePass private constructor(val endpoints: ProbeEndpoints) {

    sealed interface Result {
        data class Passed(val pass: VirtualGatePass) : Result
        data class Refused(val error: ProbeErrorCode) : Result
    }

    companion object {
        fun checkEnvironment(environment: KisEnvironment, endpoints: ProbeEndpoints): ProbeErrorCode? {
            if (environment != KisEnvironment.VIRTUAL) return ProbeErrorCode.NON_VIRTUAL_ENVIRONMENT
            if (endpoints != ProbeEndpoints.KIS_VIRTUAL) return ProbeErrorCode.NON_VIRTUAL_ENVIRONMENT
            return null
        }

        suspend fun evaluate(
            environment: KisEnvironment,
            endpoints: ProbeEndpoints,
            hasVirtualCredentials: suspend () -> Boolean,
        ): Result {
            checkEnvironment(environment, endpoints)?.let { return Result.Refused(it) }
            if (!hasVirtualCredentials()) return Result.Refused(ProbeErrorCode.VIRTUAL_CREDENTIAL_MISSING)
            return Result.Passed(VirtualGatePass(endpoints))
        }
    }
}
