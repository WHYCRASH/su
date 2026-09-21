# Release signing

The release certificate is not stored in the Git repository.

Local builds read `keystore.properties` at the repository root (already gitignored), or the environment variables `ETA_RELEASE_STORE_FILE`, `ETA_RELEASE_STORE_PASSWORD`, `ETA_RELEASE_KEY_ALIAS`, `ETA_RELEASE_KEY_PASSWORD`.

CI restores the same certificate from GitHub Actions secrets so local and CI builds can be installed over each other.
