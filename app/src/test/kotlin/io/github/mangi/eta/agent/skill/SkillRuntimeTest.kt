package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun seedBuiltinSkillsDoesNotOverwriteExistingBuiltinData() {
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = temporaryFolder.newFolder("skills"),
        )
        service.seedBuiltinSkillsIfNeeded()
        val errorsFile = File(
            temporaryFolder.root,
            "skills/self-improving-agent/data/ERRORS.md",
        )
        errorsFile.parentFile?.mkdirs()
        errorsFile.writeText("preserve existing learning\n")

        service.seedBuiltinSkillsIfNeeded()

        assertEquals("preserve existing learning\n", errorsFile.readText())

        service.listSkillsForManagement()
        val userSkill = File(temporaryFolder.root, "skills/cache-probe/SKILL.md")
        userSkill.parentFile?.mkdirs()
        userSkill.writeText(
            """
            ---
            name: cache-probe
            description: Verify explicit index refresh.
            ---

            # Cache probe
            """.trimIndent()
        )

        assertFalse(service.listSkillsForManagement().any { it.id == "cache-probe" })
        assertTrue(
            service.listSkillsForManagement(forceRefresh = true).any { it.id == "cache-probe" }
        )
    }

    @Test
    fun builtinSkillInstallerManifestAndFileHaveValidMetadata() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val assetsRoot = listOf(
            File(workingDirectory, "app/src/main/assets"),
            File(workingDirectory, "src/main/assets"),
        ).first { it.isDirectory }
        val manifest = JSONObject(File(assetsRoot, "builtin_skills/manifest.json").readText())
        val skills = manifest.getJSONArray("skills")
        val installer = (0 until skills.length())
            .map { skills.getJSONObject(it) }
            .single { it.getString("id") == "skill-installer" }

        assertTrue(installer.getString("description").isNotBlank())
        val parsed = SkillParser.parseSkillFile(File(assetsRoot, installer.getString("assetPath") + "/SKILL.md"))
        assertEquals("skill-installer", parsed?.frontmatter?.get("name"))
        assertTrue(parsed?.frontmatter?.get("description").orEmpty().isNotBlank())
    }

    @Test
    fun scanAndDeleteNeverFollowSkillDirectorySymlinkOutsideRoot() {
        val skillsRoot = temporaryFolder.newFolder("symlink-skills")
        val externalRoot = temporaryFolder.newFolder("external-skill")
        val externalSkill = File(externalRoot, "SKILL.md")
        externalSkill.writeText(
            "---\nname: external-skill\ndescription: Must remain outside.\n---\n"
        )
        val link = File(skillsRoot, "linked-skill")
        try {
            Files.createSymbolicLink(link.toPath(), externalRoot.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        val indexed = service.listSkillsForManagement(forceRefresh = true)

        assertFalse(indexed.any { it.id == "external-skill" })
        assertFalse(service.deleteSkill("external-skill"))
        assertTrue(externalSkill.isFile)
        assertTrue(externalSkill.readText().contains("Must remain outside"))
    }

    @Test
    fun scanRejectsSymlinkedSkillFileAndNormalDeleteUsesPrivateBackupFlow() {
        val skillsRoot = temporaryFolder.newFolder("delete-skills")
        val externalFile = File(temporaryFolder.newFolder("external-file"), "SKILL.md")
        externalFile.writeText(
            "---\nname: linked-file\ndescription: Must not be indexed.\n---\n"
        )
        val linkedRoot = File(skillsRoot, "linked-file").also { it.mkdirs() }
        try {
            Files.createSymbolicLink(File(linkedRoot, "SKILL.md").toPath(), externalFile.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val normalRoot = File(skillsRoot, "normal-user").also { it.mkdirs() }
        File(normalRoot, "SKILL.md").writeText(
            "---\nname: normal-user\ndescription: Delete this user Skill.\n---\n"
        )
        val externalDirectory = temporaryFolder.newFolder("delete-external-directory")
        val externalMarker = File(externalDirectory, "keep.txt").also {
            it.writeText("nested symlink target must survive")
        }
        try {
            Files.createSymbolicLink(
                File(normalRoot, "linked-external").toPath(),
                externalDirectory.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        val indexed = service.listSkillsForManagement(forceRefresh = true)

        assertFalse(indexed.any { it.id == "linked-file" })
        assertTrue(indexed.any { it.id == "normal-user" })
        assertTrue(service.deleteSkill("normal-user"))
        assertFalse(normalRoot.exists())
        assertTrue(externalFile.exists())
        assertTrue(externalMarker.isFile)
        assertEquals("nested symlink target must survive", externalMarker.readText())
        assertTrue(
            service.listSkillsForManagement(forceRefresh = true).none { it.id == "normal-user" }
        )
    }

    @Test
    fun builtinCleanupUnlinksDirectorySymlinkWithoutDeletingExternalContent() {
        val skillsRoot = temporaryFolder.newFolder("builtin-link-skills")
        val externalRoot = temporaryFolder.newFolder("builtin-link-external")
        val externalMarker = File(externalRoot, "SKILL.md").also {
            it.writeText("external content must survive")
        }
        val targetLink = File(skillsRoot, "self-improving-agent")
        try {
            Files.createSymbolicLink(targetLink.toPath(), externalRoot.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertFalse(isSafeBuiltinSkillInstallation(targetLink))
        val deleted = deleteSkillPathWithoutFollowingLinks(skillsRoot, targetLink)

        assertTrue(deleted)
        assertFalse(Files.exists(targetLink.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(externalMarker.isFile)
        assertEquals("external content must survive", externalMarker.readText())
    }

    @Test
    fun saveSkillContentOverridesBuiltinAndMarksOverriddenThenResets() {
        val skillsRoot = temporaryFolder.newFolder("override-skills")
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )
        service.seedBuiltinSkillsIfNeeded()

        val initial = service.listSkillsForManagement(forceRefresh = true)
            .first { it.id == "self-improving-agent" }
        assertFalse(initial.isOverridden)

        val modifiedContent = """
            ---
            name: self-improving-agent
            description: Custom overridden description.
            ---

            # Overridden Body
        """.trimIndent()

        val saveSuccess = service.saveSkillContent("self-improving-agent", modifiedContent)
        assertTrue(saveSuccess)

        val overridden = service.listSkillsForManagement(forceRefresh = true)
            .first { it.id == "self-improving-agent" }
        assertTrue(overridden.isOverridden)
        assertEquals("Custom overridden description.", overridden.description)

        val defaultContent = service.getBuiltinSkillContent("self-improving-agent")
        assertTrue(defaultContent != null && defaultContent.isNotBlank())
        assertTrue(service.isBuiltinSkillOverridden("self-improving-agent"))

        // Reset back to builtin
        service.installBuiltinSkill("self-improving-agent")
        val reset = service.listSkillsForManagement(forceRefresh = true)
            .first { it.id == "self-improving-agent" }
        assertFalse(reset.isOverridden)
        assertFalse(service.isBuiltinSkillOverridden("self-improving-agent"))
    }

    @Test
    fun saveSkillContentRejectsInvalidFormatOrMissingName() {
        val skillsRoot = temporaryFolder.newFolder("invalid-save-skills")
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        // Missing frontmatter name
        val noName = """
            ---
            description: Missing name.
            ---
            Content
        """.trimIndent()
        assertFalse(service.saveSkillContent("no-name-skill", noName))

        // Blank name
        val blankName = """
            ---
            name:
            description: Blank name.
            ---
        """.trimIndent()
        assertFalse(service.saveSkillContent("blank-name-skill", blankName))
    }

    @Test
    fun saveUserSkillContentCreatesAndIndexesNewSkill() {
        val skillsRoot = temporaryFolder.newFolder("user-save-skills")
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        val userSkillContent = """
            ---
            name: user-custom-skill
            description: A brand new user skill.
            ---

            # User Skill
        """.trimIndent()

        val saved = service.saveSkillContent("user-custom-skill", userSkillContent)
        assertTrue(saved)

        val found = service.listSkillsForManagement(forceRefresh = true)
            .firstOrNull { it.id == "user-custom-skill" }
        assertTrue(found != null)
        assertEquals("user-custom-skill", found?.name)
        assertEquals("A brand new user skill.", found?.description)
        assertEquals("user", found?.source)
        assertFalse(found?.isOverridden ?: true)
    }
}
