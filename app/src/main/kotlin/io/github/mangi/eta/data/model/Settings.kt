package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val subagentEnabled: Boolean = true,
    val subagentModelId: String? = null,
    val subagentReasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
)
