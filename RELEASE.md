# Release Process

## Versioning

This project follows [Semantic Versioning](https://semver.org/):

- **Patch** (`x.y.Z`) — bug fixes, correctness improvements, no API changes
- **Minor** (`x.Y.0`) — new features, backward-compatible
- **Major** (`X.0.0`) — breaking changes

## Steps to Cut a Release

### 1. Prepare release notes

Create `release-notes/<version>.md`.

If this file exists, the release workflow uses it as the release body and appends a generated `Full Changelog` section. If absent, it auto-generates the whole body from `git log` since the previous tag.

Use an existing file in `release-notes/` as a format reference.

### 2. Update CHANGELOG

Move the `[Unreleased]` entries to `[<version>] - <date>` in `CHANGELOG.md`, recreate an empty `[Unreleased]` section, and update the comparison links at the bottom.

### 3. Validate locally

```bash
./gradlew -PreleaseVersion=<version> buildRelease
./scripts/write_release_notes.sh <version>
```

Confirm the release zip is written to `build/distributions/kafka-gitops-<version>.zip`.

### 4. Commit to main

```bash
git add CHANGELOG.md release-notes/<version>.md
git commit -s -m "chore: release <version>"
git push origin main
```

### 5. Tag and push

```bash
git tag <version>
git push origin <version>
```

Pushing the tag triggers the [Release workflow](.github/workflows/release.yml), which:
- Builds the shadow JAR with `-PreleaseVersion=<version>`
- Generates release notes from `release-notes/<version>.md` (or `git log`)
- Publishes the GitHub Release with the distributable ZIP
- Builds and pushes the Docker image (requires `DOCKER_USERNAME` / `DOCKER_PASSWORD` secrets)

The tag push also triggers the Java CI workflow because that workflow runs on every push.

### 6. Verify

Check the [Actions tab](https://github.com/chenrui333/kafka-gitops/actions) to confirm the Release workflow and tag-triggered Java CI both succeed.

Confirm the GitHub Release is published with `kafka-gitops-<version>.zip`.

## Re-running a Release

If the release job fails after tagging, re-run it via `workflow_dispatch`:

```
Actions → Release → Run workflow → tag: <version>
```

This avoids deleting and re-pushing the tag.

## Notes

- The version is **not stored in any file** — it is injected at build time via `-PreleaseVersion` from the git tag.
- Docker push is conditional on `DOCKER_USERNAME` / `DOCKER_PASSWORD` secrets being set.
