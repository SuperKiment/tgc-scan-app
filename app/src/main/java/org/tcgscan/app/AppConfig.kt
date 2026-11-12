package org.tcgscan.app

/**
 * Build-wide constants. Every value the app needs to reach the published data lives here, so there
 * is exactly one place to edit when the GitHub repository is created (D13).
 */
object AppConfig {

    /** Placeholder shipped by the plan; [GITHUB_REPO] must be set to the real `owner/repo`. */
    const val GITHUB_REPO_PLACEHOLDER = "CHANGE-ME-OWNER/tcg-scan-app"

    /** `owner/repo` of the public repository hosting the pack releases. */
    const val GITHUB_REPO = "SuperKiment/tgc-scan-app"

    /**
     * Release asset base. A plain redirect, no GitHub API call, no token and no rate limit (D13);
     * pack files from `manifest.json` are resolved against it.
     */
    const val RELEASE_DOWNLOAD_BASE = "https://github.com/$GITHUB_REPO/releases/latest/download"

    /** Pack manifest, read at launch when the network is available (FR-23). */
    const val MANIFEST_URL = "$RELEASE_DOWNLOAD_BASE/manifest.json"

    /** Highest `pack_meta.pack_schema` this build can read; a newer pack is refused (EC-17). */
    const val PACK_SCHEMA = 1

    /** Room schema version, also the backup envelope `schemaVersion` (NFR-9, EC-18). */
    const val DB_SCHEMA = 1

    /** Hard ceiling for the Coil disk cache holding TCGdex visuals (FR-25). */
    const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024

    /** False while [GITHUB_REPO] is still the placeholder: no pack can be downloaded then. */
    fun isGithubRepoConfigured(): Boolean = GITHUB_REPO != GITHUB_REPO_PLACEHOLDER
}
