#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: write_release_notes.sh <version>}"
repo="${GITHUB_REPOSITORY:-chenrui333/kafka-gitops}"
notes_file="release-notes/${version}.md"
current_ref="HEAD"

if git rev-parse --verify "refs/tags/${version}" >/dev/null 2>&1; then
  current_ref="refs/tags/${version}"
fi

previous_tag="$(git tag --sort=-v:refname | awk -v version="${version}" '$0 != version { print; exit }')"

if [[ -f "${notes_file}" ]]; then
  cat "${notes_file}"
else
  echo "## Included Changes"
  echo
  if [[ -n "${previous_tag}" ]]; then
    git log --reverse --format='- %s' "${previous_tag}..${current_ref}"
  else
    git log --reverse --format='- %s' "${current_ref}"
  fi
fi

echo
echo "## Full Changelog"
echo
if [[ -n "${previous_tag}" ]]; then
  echo "- [${previous_tag}...${version}](https://github.com/${repo}/compare/${previous_tag}...${version})"
else
  echo "- Initial release"
fi
