package com.aegisone.invariants

import com.aegisone.review.FieldScopePolicy

/**
 * Test double for FieldScopePolicy.
 * Constructed with an explicit permitted field set — nothing permitted by default.
 * Used in I4-T5 to prove the field boundary is system-controlled.
 */
class TestFieldScopePolicy(private val permitted: Set<String>) : FieldScopePolicy {
    override fun isPermitted(field: String): Boolean = field in permitted
}
