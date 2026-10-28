package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSkillsUiState(
    val skills: List<SkillItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val isUpdatingSkills: Boolean = false,
    val busySkillId: String? = null,
    val replacement: SkillReplacementUi? = null,
    val pendingUpdates: List<SkillUpdateCandidateUi> = emptyList(),
    val notice: SkillNoticeUi? = null,
)

@Immutable
data class SkillUpdateCandidateUi(
    val skillId: String,
    val name: String,
    val repository: String,
    val trackedRef: String,
    val skillPath: String,
    val currentCommitSha: String,
    val remoteCommitSha: String,
    val remoteTreeSha: String,
    val isLocallyModified: Boolean,
)

@Immutable
data class SkillReplacementUi(
    val id: String,
    val name: String,
)

@Immutable
data class SkillNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)

@Immutable
data class SkillItemUi(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val installed: Boolean,
    val capabilities: List<String>,
    val isOverridden: Boolean = false,
    val canCheckUpdate: Boolean = false,
    val originRepository: String? = null,
)

internal val SkillItemUi.canDeleteUserSkill: Boolean
    get() = installed && source == "user"

internal val SkillItemUi.canResetBuiltin: Boolean
    get() = installed && source == "builtin" && isOverridden
