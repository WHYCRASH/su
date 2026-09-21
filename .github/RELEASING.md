# su release process

Release process for `su` (https://github.com/WHYCRASH/su), maintained by `dautist`.

## Configuring signing secrets

The release certificate and its passwords must never be committed to Git. Before first use, add the following in the repository's
`Settings > Secrets and variables > Actions`:

- `ETA_RELEASE_KEYSTORE_BASE64`: Base64 text of the release certificate
- `ETA_RELEASE_STORE_PASSWORD`: KeyStore password
- `ETA_RELEASE_KEY_ALIAS`: Key alias
- `ETA_RELEASE_KEY_PASSWORD`: Key password

On macOS, copy the certificate's Base64 text with:

```bash
base64 < /path/to/eta-release.p12 | tr -d '\n' | pbcopy
```

You can also use the GitHub CLI. Do not pass password-like secrets directly as command arguments; enter them when prompted:

```bash
base64 < /path/to/eta-release.p12 | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## Building and publishing

Do not commit the release certificate to the repository. For local builds, place `keystore.properties` at the repository root, or configure the same PKCS12 file through Actions secrets.

The following events build a signed and verified release APK and keep it as an Actions artifact for 14 days:

- Pushing a commit to `main`
- Pushing a `v*` tag
- Manually running `Actions > su Build` in GitHub

The workflow never creates, modifies, or publishes a GitHub release.

Before an official release, update `versionCode` and `versionName`, then create the tag matching
`versionName`. For example, to release `2.2.2`:

```bash
git tag v2.2.2
git push origin v2.2.2
```

After pushing the tag, wait for the `su Build` workflow to finish, then:

1. Download `app-release.apk` from that workflow run's `Artifacts`.
2. In the repository, go to `Releases > Draft a new release` and select the existing tag.
3. Fill in the release notes and upload the APK.
4. Check the version, notes, and attachments, then have a maintainer publish it manually.
