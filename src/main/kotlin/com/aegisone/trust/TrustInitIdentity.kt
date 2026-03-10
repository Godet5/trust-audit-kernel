package com.aegisone.trust

/**
 * Opaque identity token for a TrustInit instance. Created at TrustInit construction.
 * The Broker is configured with the identity of the TrustInit it trusts.
 * A BootSignal is valid only if its issuer === the Broker's configured identity.
 *
 * In production this maps to an authenticated IPC channel bound to TrustInit's
 * process credential. In Phase 0, reference equality simulates that binding.
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2.2
 */
class TrustInitIdentity
