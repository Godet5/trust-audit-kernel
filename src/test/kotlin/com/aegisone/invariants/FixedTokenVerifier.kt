package com.aegisone.invariants

import com.aegisone.review.TokenVerifier
import com.aegisone.review.VerifiedToken

/**
 * Test double: always returns a VerifiedToken with the given claims.
 * Simulates a token whose signature is valid. Used from I4-T2 onward
 * to isolate failures in steps beyond step_1.
 */
class FixedTokenVerifier(
    private val sessionId: String,
    private val certFingerprint: String,
    private val embeddedExpiryTime: Long = Long.MAX_VALUE
) : TokenVerifier {
    override fun verify(token: String): VerifiedToken =
        VerifiedToken(
            sessionId = sessionId,
            certFingerprint = certFingerprint,
            embeddedExpiryTime = embeddedExpiryTime
        )
}
