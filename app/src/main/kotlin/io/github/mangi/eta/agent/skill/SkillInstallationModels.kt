package io.github.mangi.eta.agent.skill

/** A Skill installable from a ZIP. relativePath is relative to the repo root; the repo root itself uses `.`. */
data class SkillArchiveCandidate(
    val id: String,
    val name: String,
    val description: String,
    val relativePath: String,
)

data class InstalledSkill(
    val id: String,
    val name: String,
    val description: String,
)

data class SkillInstallConflict(
    val id: String,
    val name: String,
    val existingSource: String,
    val replaceAllowed: Boolean,
)

enum class SkillInstallErrorCode {
    INVALID_ARCHIVE,
    ARCHIVE_TOO_LARGE,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    EXTRACTED_CONTENT_TOO_LARGE,
    ENTRY_PATH_TOO_DEEP,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY,
    NO_SKILL_FOUND,
    MULTIPLE_SKILLS_FOUND,
    INVALID_SKILL,
    INCOMPATIBLE_SKILL,
    INVALID_SELECTION,
    DUPLICATE_SKILL_ID,
    TARGET_NOT_REPLACEABLE,
    CANCELLED,
    IO_ERROR,
    COMMIT_FAILED,
}

data class SkillInstallError(
    val code: SkillInstallErrorCode,
    val message: String,
)

sealed interface SkillArchiveInspectionResult {
    data class Success(
        val candidates: List<SkillArchiveCandidate>,
    ) : SkillArchiveInspectionResult

    data class Failure(
        val error: SkillInstallError,
    ) : SkillArchiveInspectionResult
}

sealed interface SkillInstallResult {
    data class Success(
        val installed: List<InstalledSkill>,
    ) : SkillInstallResult

    data class Conflict(
        val conflicts: List<SkillInstallConflict>,
        /** Returned on local ZIP conflicts; the same archive bytes the install core actually materialized and inspected. */
        val archiveSha256: String? = null,
    ) : SkillInstallResult

    data class Failure(
        val error: SkillInstallError,
        /** Automatic rollback was incomplete; a backup of the old Skill is kept in the app's private recovery directory. */
        val recoveryRequired: Boolean = false,
    ) : SkillInstallResult
}

data class SkillResourceInfo(
    val relativePath: String,
    val sizeBytes: Long,
)

enum class SkillResourceErrorCode {
    INVALID_SKILL_ROOT,
    INVALID_RELATIVE_PATH,
    RESOURCE_NOT_FOUND,
    RESOURCE_TOO_LARGE,
    BINARY_RESOURCE,
    TOO_MANY_RESOURCES,
    IO_ERROR,
}

data class SkillResourceError(
    val code: SkillResourceErrorCode,
    val message: String,
)

sealed interface SkillResourceListResult {
    data class Success(
        val resources: List<SkillResourceInfo>,
    ) : SkillResourceListResult

    data class Failure(
        val error: SkillResourceError,
    ) : SkillResourceListResult
}

sealed interface SkillResourceReadResult {
    data class Success(
        val relativePath: String,
        val text: String,
    ) : SkillResourceReadResult

    data class Failure(
        val error: SkillResourceError,
    ) : SkillResourceReadResult
}
