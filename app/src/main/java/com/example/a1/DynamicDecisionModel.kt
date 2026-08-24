package com.example.a1

import kotlin.math.max

data class DynamicDecisionFeatures(
    val crpDetected: Boolean = false,
    val dummyFilled: Boolean = false,
    val submitAttemptSeen: Boolean = false,
    val credentialPostCount: Int = 0,
    val postAfterSubmitCount: Int = 0,
    val decoyClearedAfterSubmit: Boolean = false,
    val decoyPersistedAfterSubmit: Boolean = false,
    val loginFormRemoved: Boolean = false,
    val credentialFormReappeared: Boolean = false,
    val invalidWarningDetected: Boolean = false,
    val requiredWarningDetected: Boolean = false,
    val captchaOrMfaDetected: Boolean = false,
    val nextCredentialStep: Boolean = false,
    val successLikeTransition: Boolean = false,
    val domTransitionScore: Int = 0,
    val uiAbuseCount: Int = 0,
) {
    val rejectSignal: Boolean
        get() = invalidWarningDetected || requiredWarningDetected || credentialFormReappeared

    val challengeSignal: Boolean
        get() = captchaOrMfaDetected

    val formConsumedCore: Boolean
        get() = decoyClearedAfterSubmit && loginFormRemoved && !rejectSignal
}

data class DynamicDecision(
    val isSafe: Boolean,
    val reason: String,
    val phishScore: Int,
    val safeScore: Int,
    val shouldWait: Boolean = false,
)

object DynamicDecisionModel {
    fun evaluate(f: DynamicDecisionFeatures): DynamicDecision {
        if (!f.crpDetected) {
            return DynamicDecision(
                isSafe = true,
                reason = "no_crp",
                phishScore = 0,
                safeScore = 2,
            )
        }

        if (!f.submitAttemptSeen || !f.dummyFilled) {
            return DynamicDecision(
                isSafe = true,
                reason = "crp_without_probe",
                phishScore = 0,
                safeScore = 0,
                shouldWait = true,
            )
        }

        var phish = 0
        var safe = 0

        if (f.formConsumedCore) phish += 5
        if (f.decoyClearedAfterSubmit && !f.decoyPersistedAfterSubmit) phish += 3
        if (f.loginFormRemoved && !f.rejectSignal) phish += 3
        if (f.successLikeTransition && !f.rejectSignal) phish += 3
        if (f.nextCredentialStep && !f.rejectSignal) phish += 2
        if (f.credentialPostCount > 0 && !f.rejectSignal) phish += 1
        if (f.uiAbuseCount > 0) phish += 1
        phish += max(0, f.domTransitionScore / 4)

        if (f.invalidWarningDetected) safe += 4
        if (f.requiredWarningDetected) safe += 3
        if (f.credentialFormReappeared) safe += 4
        if (f.decoyPersistedAfterSubmit) safe += 3
        if (f.captchaOrMfaDetected) safe += 1

        return when {
            phish >= 6 && phish >= safe + 2 -> DynamicDecision(
                isSafe = false,
                reason = "decoy_consumed_without_reject",
                phishScore = phish,
                safeScore = safe,
            )

            safe >= 5 && safe >= phish -> DynamicDecision(
                isSafe = true,
                reason = "benign_like_reject_or_retry",
                phishScore = phish,
                safeScore = safe,
            )

            f.challengeSignal -> DynamicDecision(
                isSafe = true,
                reason = "challenge_or_mfa_hold",
                phishScore = phish,
                safeScore = safe,
                shouldWait = true,
            )

            else -> DynamicDecision(
                isSafe = true,
                reason = "ambiguous_dynamic_hold",
                phishScore = phish,
                safeScore = safe,
                shouldWait = true,
            )
        }
    }
}
