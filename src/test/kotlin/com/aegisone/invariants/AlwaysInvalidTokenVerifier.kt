package com.aegisone.invariants

import com.aegisone.review.TokenVerifier
import com.aegisone.review.VerifiedToken

/**
 * Test double: always rejects token signature verification.
 * Used in I4-T1 to simulate a forged or tampered session token.
 */
class AlwaysInvalidTokenVerifier : TokenVerifier {
    override fun verify(token: String): VerifiedToken? = null
}
