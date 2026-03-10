package com.aegisone.invariants

import com.aegisone.review.ArtifactState
import com.aegisone.review.ArtifactStore

/**
 * In-memory artifact store for Phase 0 harness tests.
 */
class TestArtifactStore : ArtifactStore {
    private val states = mutableMapOf<String, ArtifactState>()
    private val fields = mutableMapOf<String, MutableMap<String, String>>()  // artifactId → field → value

    override fun getState(artifactId: String): ArtifactState? = states[artifactId]
    override fun setState(artifactId: String, state: ArtifactState) { states[artifactId] = state }
    override fun writeField(artifactId: String, field: String, value: String) {
        fields.getOrPut(artifactId) { mutableMapOf() }[field] = value
    }
    override fun readField(artifactId: String, field: String): String? = fields[artifactId]?.get(field)
}
