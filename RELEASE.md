# Release Process

## Versioning

This project follows [Semantic Versioning](https://semver.org/):

- **Patch** (`x.y.Z`) — bug fixes, correctness improvements, no API changes
- **Minor** (`x.Y.0`) — new features, backward-compatible
- **Major** (`X.0.0`) — breaking changes

## Steps to Cut a Release

### 1. Prepare release notes

Create `release-notes/<version>.md` (e.g., `release-notes/0.5.2.md`).

If this file exists, the release workflow uses it verbatim. If absent, it auto-generates from `git log` since the previous tag.

Use `release-notes/0.4.0.md` as a format reference.

### 2. Update CHANGELOG

Move the `[Unreleased]` section to `[<version>] - <date>` in `CHANGELOG.md` and update the comparison links at the bottom.

### 3. Commit to main

```bash
git add CHANGELOG.md release-notes/<version>.md
git commit -m "chore: release <version>"
git push origin main
```

### 4. Tag and push

```bash
git tag <version>
git push origin <version>
```

Pushing the tag triggers the [Release workflow](.github/workflows/release.yml), which:
- Builds the shadow JAR with `-PreleaseVersion=<version>`
- Generates release notes from `release-notes/<version>.md` (or `git log`)
- Publishes the GitHub Release with the distributable ZIP
- Builds and pushes the Docker image (requires `DOCKER_USERNAME` / `DOCKER_PASSWORD` secrets)

### 5. Verify

Check the [Actions tab](https://github.com/chenrui333/kafka-gitops/actions) to confirm the release job succeeds and the GitHub Release is published.

## Re-running a Release

If the release job fails after tagging, re-run it via `workflow_dispatch`:

```
Actions → Release → Run workflow → tag: <version>
```

This avoids deleting and re-pushing the tag.

## Notes

- The version is **not stored in any file** — it is injected at build time via `-PreleaseVersion` from the git tag.
- Docker push is conditional on `DOCKER_USERNAME` / `DOCKER_PASSWORD` secrets being set.
