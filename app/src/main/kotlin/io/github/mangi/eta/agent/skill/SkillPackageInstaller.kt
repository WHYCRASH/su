package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class SkillPackageLimits(
    val maxArchiveBytes: Long = 32L * 1024L * 1024L,
    val maxExtractedBytes: Long = 128L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxSkillFileBytes: Long = 512L * 1024L,
    val maxEntries: Int = 2_048,
    val maxPathDepth: Int = 16,
)

/**
 * Skill ZIP installer.
 *
 * Everything first lands in a private staging area outside the skills directory and is only moved
 * into the final directory after path, quota, and Skill metadata validation pass. Bulk replacement backs up the old directory first; any failure deletes the new directory and restores the backup.
 */
class SkillPackageInstaller internal constructor(
    skillsRoot: File,
    private val indexService: SkillIndexService,
    private val limits: SkillPackageLimits = SkillPackageLimits(),
    private val directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
    private val builtinIdLookup: (String) -> Boolean = indexService::isBuiltinSkillId,
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val workRoot = File(
        requireNotNull(canonicalSkillsRoot.parentFile) { "Skills directory must have a parent directory" },
        ".eta-skill-installer",
    )

    fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult = withArchive(openStream, isCancelled) { operation, extractedRoot ->
        val candidates = discoverCandidates(extractedRoot, extractedRoot, isCancelled)
        when {
            candidates.isEmpty() -> fail(
                SkillInstallErrorCode.NO_SKILL_FOUND,
                "No SKILL.md found in the ZIP",
            )
            candidates.size > 1 -> fail(
                SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND,
                "A local ZIP must contain exactly one Skill, found ${candidates.size}",
            )
        }
        if (replaceUserSkill) {
            val expectedId = expectedReplacementId?.trim().orEmpty()
            val expectedDigest = expectedArchiveSha256?.trim().orEmpty()
            if (expectedId.isBlank()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "Replacing a user Skill requires the confirmed Skill id",
                )
            }
            if (candidates.single().id != expectedId) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "The re-read ZIP does not match the confirmed replacement Skill",
                )
            }
            if (!SHA_256_REGEX.matches(expectedDigest)) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "Replacing a user Skill requires a valid lowercase SHA-256",
                )
            }
            if (operation.archiveSha256 != expectedDigest) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "The re-read ZIP contents do not match the confirmed archive",
                )
            }
        } else if (expectedReplacementId != null || expectedArchiveSha256 != null) {
            fail(
                SkillInstallErrorCode.INVALID_SELECTION,
                "Replacement identity and archive digest may only be specified when replacing an existing user Skill",
            )
        }
        installCandidates(
            operation = operation,
            candidates = candidates,
            replaceUserSkills = replaceUserSkill,
            isCancelled = isCancelled,
            conflictArchiveSha256 = operation.archiveSha256,
        )
    }

    /** Inspect a repository ZIP. A single top-level directory is treated as a GitHub codeload envelope. */
    fun inspectRepositoryZip(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean = { false },
    ): SkillArchiveInspectionResult = withArchiveInspection(openStream, isCancelled) { extractedRoot ->
        val repositoryRoot = repositoryContentRoot(extractedRoot)
        val candidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
        if (candidates.isEmpty()) {
            fail(SkillInstallErrorCode.NO_SKILL_FOUND, "No SKILL.md found in the repository ZIP")
        }
        SkillArchiveInspectionResult.Success(candidates.map { it.publicModel })
    }

    /** selectedPaths uses the repository-relative paths returned by [inspectRepositoryZip]. */
    fun installRepositoryZip(
        openStream: () -> InputStream,
        selectedPaths: List<String>,
        replaceUserSkills: Boolean = false,
        expectedReplacementIds: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult {
        val selectedPathsSnapshot = selectedPaths.toList()
        val expectedIdsSnapshot = expectedReplacementIds.toSet()
        return withArchive(openStream, isCancelled) { operation, extractedRoot ->
            if (selectedPathsSnapshot.isEmpty()) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "Select at least one Skill path")
            }
            val repositoryRoot = repositoryContentRoot(extractedRoot)
            val allCandidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
            if (allCandidates.isEmpty()) {
                fail(SkillInstallErrorCode.NO_SKILL_FOUND, "No SKILL.md found in the repository ZIP")
            }
            val candidatesByPath = allCandidates.associateBy { it.relativePath }
            val normalizedSelections = selectedPathsSnapshot.map(::normalizeSelectionPath)
            if (normalizedSelections.distinct().size != normalizedSelections.size) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "The selection contains duplicate Skill paths")
            }
            val selected = normalizedSelections.map { relativePath ->
                candidatesByPath[relativePath]
                    ?: fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "The selected path is not a valid Skill root: $relativePath",
                    )
            }
            rejectNestedCandidateSelections(selected, allCandidates)
            val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
            if (replaceUserSkills) {
                if (expectedIdsSnapshot.isEmpty() || selectedIds != expectedIdsSnapshot) {
                    fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "The re-read repository Skills do not match the confirmed replacement Skill set",
                    )
                }
            } else if (expectedIdsSnapshot.isNotEmpty()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "expectedReplacementIds may only be specified when replacing existing user Skills",
                )
            }
            installCandidates(
                operation = operation,
                candidates = selected,
                replaceUserSkills = replaceUserSkills,
                isCancelled = isCancelled,
                conflictArchiveSha256 = null,
            )
        }
    }

    private fun installCandidates(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult = indexService.withMutationLock {
        checkCancelled(isCancelled)
        installCandidatesLocked(
            operation,
            candidates,
            replaceUserSkills,
            isCancelled,
            conflictArchiveSha256,
        )
    }

    private fun installCandidatesLocked(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult {
        val duplicateIds = candidates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            fail(
                SkillInstallErrorCode.DUPLICATE_SKILL_ID,
                "Selected Skills use duplicate names: ${duplicateIds.sorted().joinToString()}",
            )
        }
        candidates.forEach { candidate ->
            val compatibility = SkillCompatibilityChecker.evaluate(
                id = candidate.id,
                name = candidate.name,
                description = candidate.description,
                compatibility = candidate.compatibility,
                metadata = candidate.metadata,
                body = candidate.body,
            )
            if (!compatibility.available) {
                fail(
                    SkillInstallErrorCode.INCOMPATIBLE_SKILL,
                    "${candidate.id}: ${compatibility.reason ?: "This Skill cannot run in the current environment"}",
                )
            }
        }

        val installedEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .associateBy { it.id }
        val conflicts = mutableListOf<SkillInstallConflict>()
        candidates.forEach { candidate ->
            val target = File(canonicalSkillsRoot, candidate.id)
            val existing = installedEntries[candidate.id]
            when {
                builtinIdLookup(candidate.id) -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = BUILTIN_SKILL_SOURCE,
                    replaceAllowed = false,
                )
                existing != null && !replaceUserSkills -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = existing.source,
                    replaceAllowed = existing.source == USER_SKILL_SOURCE &&
                        isReplaceableTarget(existing, target),
                )
                existing != null && !isReplaceableTarget(existing, target) -> conflicts +=
                    SkillInstallConflict(
                        id = candidate.id,
                        name = candidate.name,
                        existingSource = existing.source,
                        replaceAllowed = false,
                    )
                existing == null && target.exists() -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = "unknown",
                    replaceAllowed = false,
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            return SkillInstallResult.Conflict(
                conflicts = conflicts.sortedBy { it.id },
                archiveSha256 = conflictArchiveSha256,
            )
        }

        // A short non-interruptible transaction starts here; cancellation only takes effect before any final file change.
        checkCancelled(isCancelled)
        if (!canonicalSkillsRoot.exists() && !canonicalSkillsRoot.mkdirs()) {
            fail(SkillInstallErrorCode.IO_ERROR, "Cannot create the Skills directory")
        }
        val backupRoot = File(operation.directory, "backup")
        if (!backupRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "Cannot create the install backup directory")
        }
        val registrySnapshots = indexService
            .captureRegistryRecoverySnapshots(candidates.map { it.id })
            .associateBy { it.skillId }
        var recoveryJournal: PendingSkillRecoveryJournal? = null
        try {
            recoveryJournal = PendingSkillRecoveryJournal.begin(
                skillsRoot = canonicalSkillsRoot,
                operationDirectory = operation.directory,
                records = candidates.map { candidate ->
                    SkillRecoveryRecord(
                        id = candidate.id,
                        originalTargetExisted = Files.exists(
                            File(canonicalSkillsRoot, candidate.id).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                        registrySnapshot = checkNotNull(registrySnapshots[candidate.id]),
                    )
                },
            )
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                if (target.exists()) {
                    val backup = File(backupRoot, candidate.id)
                    directoryMover.move(target, backup)
                    recoveryJournal.markBackupCompleted(candidate.id)
                }
            }
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                directoryMover.move(candidate.directory, target)
                recoveryJournal.markNewTargetCommitted(candidate.id)
            }
            indexService.registerInstalledUserSkills(candidates.map { it.id })
            recoveryJournal.clear()
        } catch (error: Exception) {
            val journalExists = Files.exists(
                File(operation.directory, JOURNAL_FILE_NAME).toPath(),
                LinkOption.NOFOLLOW_LINKS,
            )
            val rollbackComplete = !journalExists || runCatching {
                val recovered = recoverPendingSkillOperations(canonicalSkillsRoot, directoryMover)
                indexService.restoreRecoveredRegistry(recovered)
                completeRecoveredSkillOperations(canonicalSkillsRoot, recovered)
            }.isSuccess
            if (!rollbackComplete) operation.preserveForRecovery = true
            val suffix = if (rollbackComplete) ", previous Skills restored" else ", and automatic recovery did not complete fully"
            return SkillInstallResult.Failure(
                SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "Skill commit failed$suffix",
                ),
                recoveryRequired = !rollbackComplete,
            )
        }

        return SkillInstallResult.Success(
            installed = candidates.map {
                InstalledSkill(id = it.id, name = it.name, description = it.description)
            }
        )
    }

    private fun isReplaceableTarget(entry: SkillIndexEntry, expectedTarget: File): Boolean {
        if (entry.source != USER_SKILL_SOURCE || !entry.installed) return false
        val existingRoot = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return false
        if (existingRoot != expectedTarget.canonicalFile || !existingRoot.isDirectory) return false
        return isRecoverableSkillDirectoryTree(canonicalSkillsRoot, existingRoot)
    }

    private fun rejectNestedCandidateSelections(
        selected: List<PreparedCandidate>,
        allCandidates: List<PreparedCandidate>,
    ) {
        selected.forEach { parent ->
            val nested = allCandidates.firstOrNull { candidate ->
                candidate !== parent && candidate.directory.toPath().startsWith(parent.directory.toPath())
            }
            if (nested != null) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "Skill ${parent.relativePath} contains another nested SKILL.md: ${nested.relativePath}",
                )
            }
        }
    }

    private fun discoverCandidates(
        scanRoot: File,
        relativeRoot: File,
        isCancelled: () -> Boolean,
    ): List<PreparedCandidate> {
        val skillFiles = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name == SKILL_FILE_NAME }
            .toList()
        return skillFiles.map { skillFile ->
            checkCancelled(isCancelled)
            val directory = requireNotNull(skillFile.parentFile).canonicalFile
            val relativePath = directory.relativeTo(relativeRoot.canonicalFile)
                .invariantSeparatorsPath
                .ifBlank { "." }
            val metadata = parseAndValidateSkill(skillFile)
            PreparedCandidate(
                id = metadata.name,
                name = metadata.name,
                description = metadata.description,
                relativePath = relativePath,
                directory = directory,
                compatibility = metadata.compatibility,
                metadata = metadata.metadata,
                body = metadata.body,
            )
        }.sortedBy { it.relativePath }
    }

    private fun parseAndValidateSkill(skillFile: File): ValidatedSkillMetadata {
        if (skillFile.length() > limits.maxSkillFileBytes) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "${skillFile.name} exceeds the ${limits.maxSkillFileBytes} byte limit",
            )
        }
        val raw = readStrictUtf8(skillFile, limits.maxSkillFileBytes)
            ?: fail(SkillInstallErrorCode.INVALID_SKILL, "SKILL.md must be UTF-8 text")
        val frontmatter = strictFrontmatter(raw)
            ?: fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "SKILL.md must contain YAML frontmatter wrapped in ---",
            )
        val parsed = SkillParser.parseSimpleFrontmatter(frontmatter)
        val name = parsed["name"]?.trim().orEmpty()
        val description = parsed["description"]?.trim().orEmpty()
        if (name.length !in 1..MAX_SKILL_NAME_LENGTH || !SKILL_NAME_REGEX.matches(name)) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "Skill name must be lowercase letters, digits, and single hyphens of at most $MAX_SKILL_NAME_LENGTH characters",
            )
        }
        if (description.isBlank() || description.length > MAX_SKILL_DESCRIPTION_LENGTH) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "Skill description is required and must not exceed $MAX_SKILL_DESCRIPTION_LENGTH characters",
            )
        }
        val body = raw.substringAfter("---", "").substringAfter("---", "").trim()
        return ValidatedSkillMetadata(
            name = name,
            description = description,
            compatibility = parsed["compatibility"]?.trim(),
            metadata = parsed["metadata"]?.let { SkillParser.parseIndentedBlock(it) } ?: emptyMap(),
            body = body,
        )
    }

    private fun strictFrontmatter(raw: String): String? {
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0 || raw.substring(0, firstLineEnd).trimEnd('\r') != "---") return null
        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trimEnd('\r') == "---") {
                return raw.substring(firstLineEnd + 1, lineStart).trimEnd('\r', '\n')
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun repositoryContentRoot(extractedRoot: File): File {
        val children = extractedRoot.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: extractedRoot
    }

    private fun normalizeSelectionPath(raw: String): String {
        val value = raw.trim().removeSuffix("/")
        if (value == ".") return value
        val segments = validateRelativePath(value, SkillInstallErrorCode.INVALID_SELECTION)
        return segments.joinToString("/")
    }

    private fun extractArchive(
        archiveFile: File,
        targetRoot: File,
        isCancelled: () -> Boolean,
    ) {
        if (!targetRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "Cannot create the ZIP staging directory")
        }
        val pathKinds = linkedMapOf<String, Boolean>()
        var entryCount = 0
        var extractedBytes = 0L
        ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maxEntries) {
                    fail(
                        SkillInstallErrorCode.TOO_MANY_ENTRIES,
                        "ZIP has more than ${limits.maxEntries} entries",
                    )
                }
                val normalizedName = entry.name.removeSuffix("/")
                val segments = validateRelativePath(
                    normalizedName,
                    SkillInstallErrorCode.UNSAFE_ENTRY_PATH,
                )
                if (segments.size > limits.maxPathDepth) {
                    fail(
                        SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
                        "ZIP entry path depth exceeds ${limits.maxPathDepth} levels",
                    )
                }
                val relativePath = segments.joinToString("/")
                val collisionKey = collisionKey(relativePath)
                if (pathKinds.containsKey(collisionKey)) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP contains duplicate entries: $relativePath")
                }
                val ancestorKeys = segments.indices.drop(1).map { index ->
                    collisionKey(segments.take(index).joinToString("/"))
                }
                if (ancestorKeys.any { pathKinds[it] == false }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP entry has a file/directory conflict: $relativePath")
                }
                if (!entry.isDirectory && pathKinds.keys.any { it.startsWith("$collisionKey/") }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP entry has a file/directory conflict: $relativePath")
                }
                pathKinds[collisionKey] = entry.isDirectory

                val target = File(targetRoot, relativePath)
                ensureWithin(targetRoot, target)
                if (entry.isDirectory) {
                    if (!target.mkdirs() && !target.isDirectory) {
                        fail(SkillInstallErrorCode.IO_ERROR, "Cannot create the ZIP directory")
                    }
                    if (zip.read() != -1) {
                        fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP directory entry contains data")
                    }
                } else {
                    if (entry.size > limits.maxSingleFileBytes) {
                        fail(
                            SkillInstallErrorCode.ENTRY_TOO_LARGE,
                            "A single file in the ZIP exceeds the ${limits.maxSingleFileBytes} byte limit",
                        )
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.mkdirs() && !parent.isDirectory) {
                            fail(SkillInstallErrorCode.IO_ERROR, "Cannot create the ZIP file directory")
                        }
                    }
                    var fileBytes = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancelled(isCancelled)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (fileBytes > limits.maxSingleFileBytes) {
                                fail(
                                    SkillInstallErrorCode.ENTRY_TOO_LARGE,
                                    "A single file in the ZIP exceeds the ${limits.maxSingleFileBytes} byte limit",
                                )
                            }
                            if (extractedBytes > limits.maxExtractedBytes) {
                                fail(
                                    SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
                                    "ZIP extracted contents exceed the ${limits.maxExtractedBytes} byte limit",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP is empty or invalid")
        }
    }

    private fun materializeArchive(
        openStream: () -> InputStream,
        target: File,
        isCancelled: () -> Boolean,
    ): String {
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        checkCancelled(isCancelled)
        openStream().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limits.maxArchiveBytes) {
                        fail(
                            SkillInstallErrorCode.ARCHIVE_TOO_LARGE,
                            "ZIP archive exceeds the ${limits.maxArchiveBytes} byte limit",
                        )
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP is empty")
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun validateRelativePath(
        raw: String,
        errorCode: SkillInstallErrorCode,
    ): List<String> {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(raw) || raw.contains('\\') || raw.contains('\u0000') ||
            raw.any { it.isISOControl() }
        ) {
            fail(errorCode, "Path is not a safe relative path")
        }
        val segments = raw.split('/')
        if (
            segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > MAX_PATH_SEGMENT_LENGTH
            }
        ) {
            fail(errorCode, "Path contains an illegal level")
        }
        return segments
    }

    private fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private fun ensureWithin(root: File, target: File) {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            fail(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "ZIP entry tries to write outside the staging directory")
        }
    }

    private fun withArchive(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (operation: ArchiveOperation, extractedRoot: File) -> SkillInstallResult,
    ): SkillInstallResult {
        val operationDir = createOperationDirectory()
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "Cannot create the Skill install staging directory")
            )
        val operation = ArchiveOperation(operationDir)
        return try {
            val archiveFile = File(operationDir, "package.zip")
            operation.archiveSha256 = materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(operation, extractedRoot)
        } catch (error: SkillInstallException) {
            SkillInstallResult.Failure(error.error)
        } catch (_: SkillRecoveryRequiredException) {
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    SkillInstallErrorCode.COMMIT_FAILED,
                    "Detected an unfinished Skill recovery; installation stopped",
                ),
                recoveryRequired = true,
            )
        } catch (_: ZipException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP format is invalid or contents are corrupted")
            )
        } catch (_: IOException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "Failed to read or stage the ZIP")
            )
        } catch (_: SecurityException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "No permission to read this ZIP")
            )
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
            }
        }
    }

    private fun withArchiveInspection(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (extractedRoot: File) -> SkillArchiveInspectionResult,
    ): SkillArchiveInspectionResult {
        val operationDir = createOperationDirectory()
            ?: return SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "Cannot create the Skill inspection staging directory")
            )
        return try {
            val archiveFile = File(operationDir, "package.zip")
            materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(extractedRoot)
        } catch (error: SkillInstallException) {
            SkillArchiveInspectionResult.Failure(error.error)
        } catch (_: ZipException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP format is invalid or contents are corrupted")
            )
        } catch (_: IOException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "Failed to read or stage the ZIP")
            )
        } catch (_: SecurityException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "No permission to read this ZIP")
            )
        } finally {
            deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
        }
    }

    private fun createOperationDirectory(): File? {
        val safeWorkRoot = runCatching {
            prepareSkillInstallerWorkRoot(canonicalSkillsRoot)
        }.getOrNull() ?: return null
        if (safeWorkRoot != workRoot) return null
        repeat(4) {
            val candidate = File(safeWorkRoot, "operation-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        return null
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillInstallException(SkillInstallError(code, message))

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            fail(SkillInstallErrorCode.CANCELLED, "Skill installation was cancelled")
        }
    }

    private data class PreparedCandidate(
        val id: String,
        val name: String,
        val description: String,
        val relativePath: String,
        val directory: File,
        val compatibility: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val body: String = "",
    ) {
        val publicModel: SkillArchiveCandidate
            get() = SkillArchiveCandidate(
                id = id,
                name = name,
                description = description,
                relativePath = relativePath,
            )
    }

    private data class ValidatedSkillMetadata(
        val name: String,
        val description: String,
        val compatibility: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val body: String = "",
    )

    private data class ArchiveOperation(
        val directory: File,
        var archiveSha256: String = "",
        var preserveForRecovery: Boolean = false,
    )

    private class SkillInstallException(
        val error: SkillInstallError,
    ) : RuntimeException(error.message)

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_NAME_LENGTH = 64
        const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024
        const val MAX_PATH_SEGMENT_LENGTH = 255
        val SKILL_NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val SHA_256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}

internal fun interface SkillDirectoryMover {
    fun move(source: File, target: File)
}

internal object AtomicSkillDirectoryMover : SkillDirectoryMover {
    override fun move(source: File, target: File) {
        moveSkillDirectoryAtomically(source, target)
    }
}
