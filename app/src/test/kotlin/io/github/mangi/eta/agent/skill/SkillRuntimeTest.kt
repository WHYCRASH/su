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
    fun bindSkillsToAssistantRemovesDisabledCopies() {
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
        fun writeSkill(id: String): SkillIndexEntry {
            val dir = File(skillsRoot, id).apply { mkdirs() }
            File(dir, "SKILL.md").writeText(
                """
                ---
                name: $id
                description: Test skill $id.
                ---

                # $id
                """.trimIndent(),
            )
            return SkillIndexEntry(
                id = id,
                name = id,
                description = "Test skill $id.",
                rootPath = dir.absolutePath,
                skillFilePath = File(dir, "SKILL.md").absolutePath,
                hasScripts = false,
                hasReferences = false,
                hasAssets = false,
                hasEvals = false,
                installed = true,
            )
        }
        val alpha = writeSkill("alpha")
        val beta = writeSkill("beta")
        SkillRuntime.bindSkillsToAssistant(context, "asst-1", listOf(alpha, beta))
        val bound = SkillRuntime.visibleSkillsDirectory(context, "asst-1")
        assertTrue(File(bound, "alpha/SKILL.md").isFile)
        assertTrue(File(bound, "beta/SKILL.md").isFile)

        SkillRuntime.bindSkillsToAssistant(context, "asst-1", listOf(alpha))
        assertTrue(File(bound, "alpha/SKILL.md").isFile)
        assertFalse(File(bound, "beta").exists())
    }

    @Test
    fun bindSkillsSkipsPythonCacheAndKeepsScripts() {
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
        val dir = File(skillsRoot, "alpha").apply { mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: alpha
            description: Test skill alpha.
            ---

            # alpha
            """.trimIndent(),
        )
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/client.py").writeText("print('ok')\n")
        File(dir, "scripts/__pycache__").mkdirs()
        File(dir, "scripts/__pycache__/client.cpython-314.pyc").writeBytes(byteArrayOf(1, 2, 3))
        val entry = SkillIndexEntry(
            id = "alpha",
            name = "alpha",
            description = "Test skill alpha.",
            rootPath = dir.absolutePath,
            skillFilePath = File(dir, "SKILL.md").absolutePath,
            hasScripts = true,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            installed = true,
        )

        SkillRuntime.bindSkillsToAssistant(context, "asst-1", listOf(entry))
        SkillRuntime.publishVisibleSkills(context, "asst-1", listOf(entry))

        val bound = SkillRuntime.visibleSkillsDirectory(context, "asst-1")
        val visible = SkillRuntime.visibleSkillsDirectory(context, "asst-1")
        assertTrue(File(bound, "alpha/SKILL.md").isFile)
        assertTrue(File(bound, "alpha/scripts/client.py").isFile)
        assertFalse(File(bound, "alpha/scripts/__pycache__").exists())
        assertTrue(File(visible, "alpha/scripts/client.py").isFile)
        assertFalse(File(visible, "alpha/scripts/__pycache__").exists())
    }

    @Test
    fun publishVisibleSkillsRemovesDisabledCopies() {
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
        fun writeSkill(id: String): SkillIndexEntry {
            val dir = File(skillsRoot, id).apply { mkdirs() }
            File(dir, "SKILL.md").writeText(
                """
                ---
                name: $id
                description: Test skill $id.
                ---

                # $id
                """.trimIndent(),
            )
            return SkillIndexEntry(
                id = id,
                name = id,
                description = "Test skill $id.",
                rootPath = dir.absolutePath,
                skillFilePath = File(dir, "SKILL.md").absolutePath,
                hasScripts = false,
                hasReferences = false,
                hasAssets = false,
                hasEvals = false,
                installed = true,
            )
        }
        val alpha = writeSkill("alpha")
        val beta = writeSkill("beta")
        SkillRuntime.publishVisibleSkills(context, "asst-1", listOf(alpha, beta))
        val visible = SkillRuntime.visibleSkillsDirectory(context, "asst-1")
        assertTrue(File(visible, "alpha/SKILL.md").isFile)
        assertTrue(File(visible, "beta/SKILL.md").isFile)
        assertTrue(File(skillsRoot, "beta/SKILL.md").isFile)

        SkillRuntime.publishVisibleSkills(context, "asst-1", listOf(alpha))
        assertTrue(File(visible, "alpha/SKILL.md").isFile)
        assertFalse(File(visible, "beta").exists())
        assertTrue(File(skillsRoot, "beta/SKILL.md").isFile)
    }


    @Test
    fun managementIndexIgnoresVisibleAndAssistantCopies() {
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
        fun writeSkill(dir: File, id: String) {
            dir.mkdirs()
            File(dir, "SKILL.md").writeText(
                """
                ---
                name: $id
                description: Test skill $id.
                ---

                # $id
                """.trimIndent(),
            )
        }
        writeSkill(File(skillsRoot, "alpha"), "alpha")
        writeSkill(File(skillsRoot, ".visible/alpha"), "alpha")
        writeSkill(File(skillsRoot, ".assistant/asst-1/alpha"), "alpha")
        val service = SkillIndexService(
            context = context,
            skillsRoot = skillsRoot,
        )

        val indexed = service.listSkillsForManagement(forceRefresh = true).filter { it.id == "alpha" }

        assertEquals(1, indexed.size)
        assertEquals(File(skillsRoot, "alpha").canonicalFile.absolutePath, indexed.single().rootPath)
    }

    @Test
    fun reseedingRefreshesStaleBuiltinFilesAndKeepsRuntimeData() {
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = temporaryFolder.newFolder("stale-builtin-skills")
        val shipped = context.assets.open("builtin_skills/skill-installer/SKILL.md").use { it.readBytes() }
        SkillIndexService(context = context, skillsRoot = skillsRoot).seedBuiltinSkillsIfNeeded()
        val installedSkill = File(skillsRoot, "skill-installer/SKILL.md")
        assertTrue(installedSkill.readBytes().contentEquals(shipped))
        // An installed copy left over from an older release: the pre-translation Chinese body.
        installedSkill.writeText(
            "---\nname: skill-installer\ndescription: 从受信任的 curated 目录发现并安装 Skills。\n---\n\n# Skill Installer\n",
        )
        val runtimeData = File(skillsRoot, "skill-installer/data/ERRORS.md")
        runtimeData.parentFile?.mkdirs()
        runtimeData.writeText("preserve existing learning\n")

        val restarted = SkillIndexService(context = context, skillsRoot = skillsRoot)
        restarted.seedBuiltinSkillsIfNeeded()
        val indexed = restarted.listSkillsForManagement().single { it.id == "skill-installer" }

        assertTrue(installedSkill.readBytes().contentEquals(shipped))
        assertEquals("preserve existing learning\n", runtimeData.readText())
        assertEquals(SkillParser.parseSkillFile(installedSkill)?.frontmatter?.get("description"), indexed.description)
        assertTrue(indexed.description.orEmpty().none { it.code in 0x4E00..0x9FFF })
    }

}
