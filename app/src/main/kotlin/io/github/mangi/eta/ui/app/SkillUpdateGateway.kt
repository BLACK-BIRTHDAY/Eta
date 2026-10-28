package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillOrigin
import io.github.mangi.eta.agent.skill.SkillOriginStore
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.model.SkillUpdateCandidateUi
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

sealed interface SkillUpdateCheckOutcome {
    data class Success(
        val candidates: List<SkillUpdateCandidateUi>,
        val checkedCount: Int,
    ) : SkillUpdateCheckOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SkillUpdateCheckOutcome
}

sealed interface SkillUpdateApplyOutcome {
    data class Success(
        val updatedSkillNames: List<String>,
    ) : SkillUpdateApplyOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : SkillUpdateApplyOutcome
}

/**
 * 技能在线更新适配层：负责检查 GitHub 技能是否有新版本，并下载安装所选更新。
 */
interface SkillUpdateGateway {
    suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome
    suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome
}

internal class CoreSkillUpdateGateway(
    context: Context,
    private val gitHubSourceFactory: () -> PublicGitHubSkillSource = {
        val appContext = context.applicationContext
        PublicGitHubSkillSource(
            cacheRoot = appContext.cacheDir,
            baseClient = AgentHttpClient.client,
            tokenProvider = { runBlocking { SettingsDataStore.githubToken() } },
        )
    },
    private val installerFactory: () -> SkillPackageInstaller = {
        SkillRuntime.createPackageInstaller(context.applicationContext)
    },
    private val skillsRoot: File = SkillRuntime.skillsRoot(context.applicationContext),
) : SkillUpdateGateway {

    override suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome =
        withContext(Dispatchers.IO) {
            val eligibleEntries = entries.filter {
                it.installed && it.source == "user" && it.origin != null
            }
            if (eligibleEntries.isEmpty()) {
                return@withContext SkillUpdateCheckOutcome.Success(
                    candidates = emptyList(),
                    checkedCount = 0,
                )
            }

            val candidates = mutableListOf<SkillUpdateCandidateUi>()
            val gitHubSource = gitHubSourceFactory()
            try {
                val groups = eligibleEntries.groupBy { entry ->
                    val origin = entry.origin!!
                    origin.repository to origin.trackedRef
                }

                for ((repoKey, groupEntries) in groups) {
                    val (repoSlug, trackedRef) = repoKey
                    val repo = GitHubSkillRepositoryParser.resolve(
                        repository = repoSlug,
                        explicitRef = trackedRef.takeIf { it.isNotBlank() },
                        explicitPath = null,
                    )
                    val inspection = gitHubSource.inspect(repo)
                    val candidatesByPath = inspection.candidates.associateBy { candidate ->
                        runCatching {
                            GitHubSkillRepositoryParser.normalizeRelativePath(candidate.path)
                        }.getOrDefault(candidate.path)
                    }

                    for (entry in groupEntries) {
                        val origin = entry.origin!!
                        val normalizedSkillPath = runCatching {
                            GitHubSkillRepositoryParser.normalizeRelativePath(origin.skillPath)
                        }.getOrDefault(origin.skillPath)

                        val matchedCandidate = candidatesByPath[normalizedSkillPath]
                            ?: inspection.candidates.firstOrNull { it.path == origin.skillPath }

                        if (matchedCandidate != null) {
                            val hasUpdate = if (origin.treeSha.isNotBlank() && matchedCandidate.treeSha.isNotBlank()) {
                                origin.treeSha != matchedCandidate.treeSha
                            } else {
                                origin.commitSha != inspection.commitSha
                            }
                            if (hasUpdate) {
                                val isLocallyModified = entry.isLocallyModified ||
                                    SkillOriginStore.isLocallyModified(File(entry.rootPath), origin)
                                candidates.add(
                                    SkillUpdateCandidateUi(
                                        skillId = entry.id,
                                        name = entry.name,
                                        repository = origin.repository,
                                        trackedRef = origin.trackedRef,
                                        skillPath = origin.skillPath,
                                        currentCommitSha = origin.commitSha,
                                        remoteCommitSha = inspection.commitSha,
                                        remoteTreeSha = matchedCandidate.treeSha,
                                        isLocallyModified = isLocallyModified,
                                    ),
                                )
                            }
                        }
                    }
                }
                SkillUpdateCheckOutcome.Success(
                    candidates = candidates,
                    checkedCount = eligibleEntries.size,
                )
            } catch (e: Exception) {
                SkillUpdateCheckOutcome.Failure(
                    message = e.message ?: "检查技能更新失败",
                    cause = e,
                )
            } finally {
                gitHubSource.close()
            }
        }

    override suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome =
        withContext(Dispatchers.IO) {
            if (updates.isEmpty()) {
                return@withContext SkillUpdateApplyOutcome.Success(emptyList())
            }

            val installer = installerFactory()
            val gitHubSource = gitHubSourceFactory()
            val updatedNames = mutableListOf<String>()

            try {
                val groups = updates.groupBy { it.repository to it.trackedRef }
                for ((repoKey, groupUpdates) in groups) {
                    val (repoSlug, trackedRef) = repoKey
                    val repo = GitHubSkillRepositoryParser.resolve(
                        repository = repoSlug,
                        explicitRef = trackedRef.takeIf { it.isNotBlank() },
                        explicitPath = null,
                    )
                    val selectedPaths = groupUpdates.map { it.skillPath }.distinct()
                    val expectedIds = groupUpdates.map { it.skillId }.toSet()

                    val archive = gitHubSource.downloadSkillSubtreeArchive(
                        repository = repo,
                        selectedPaths = selectedPaths,
                    )
                    try {
                        val installResult = installer.installRepositoryZip(
                            openStream = { archive.file.inputStream() },
                            selectedPaths = selectedPaths,
                            replaceUserSkills = true,
                            expectedReplacementIds = expectedIds,
                            isCancelled = { !coroutineContext.isActive },
                        )
                        when (installResult) {
                            is SkillInstallResult.Success -> {
                                for (update in groupUpdates) {
                                    val skillDir = File(skillsRoot, update.skillId)
                                    val normalizedPath = runCatching {
                                        GitHubSkillRepositoryParser.normalizeRelativePath(update.skillPath)
                                    }.getOrDefault(update.skillPath)

                                    val newTreeSha = archive.treeShasByPath[normalizedPath]
                                        ?: archive.treeShasByPath[update.skillPath]
                                        ?: update.remoteTreeSha

                                    val freshSkillMdSha =
                                        SkillOriginStore.computeSkillMdSha256(skillDir).orEmpty()

                                    SkillOriginStore.write(
                                        skillDir = skillDir,
                                        origin = SkillOrigin(
                                            repository = update.repository,
                                            trackedRef = update.trackedRef,
                                            skillPath = update.skillPath,
                                            commitSha = archive.commitSha,
                                            treeSha = newTreeSha,
                                            skillMdSha256 = freshSkillMdSha,
                                        ),
                                    )
                                    updatedNames.add(update.name)
                                }
                            }

                            is SkillInstallResult.Conflict -> {
                                return@withContext SkillUpdateApplyOutcome.Failure(
                                    message = "安装更新发生冲突：${installResult.conflicts.joinToString { it.name }}",
                                )
                            }

                            is SkillInstallResult.Failure -> {
                                return@withContext SkillUpdateApplyOutcome.Failure(
                                    message = installResult.error.message,
                                )
                            }
                        }
                    } finally {
                        archive.close()
                    }
                }
                SkillUpdateApplyOutcome.Success(updatedNames)
            } catch (e: Exception) {
                SkillUpdateApplyOutcome.Failure(
                    message = e.message ?: "更新技能失败",
                    cause = e,
                )
            } finally {
                gitHubSource.close()
            }
        }
}
