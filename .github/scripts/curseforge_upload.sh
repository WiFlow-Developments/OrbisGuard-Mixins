#!/usr/bin/env bash
# Upload a jar to CurseForge.
# usage: curseforge_upload.sh <jar> <display name> <release type: release|beta|alpha> <changelog html file> <server version>
#
# Needs CURSEFORGE_TOKEN (secret) and CURSEFORGE_PROJECT_ID (variable). Skips with a notice when
# either is missing. Game version IDs come from CURSEFORGE_GAME_VERSIONS (comma separated) if set,
# otherwise the CurseForge game version whose name matches the server version.
set -euo pipefail

jar=$1 display=$2 release_type=$3 changelog=$4 server_version=$5
api="https://${CURSEFORGE_API_HOST:-42.curseforge.com}/api"

if [[ -z "${CURSEFORGE_TOKEN:-}" || -z "${CURSEFORGE_PROJECT_ID:-}" ]]; then
  echo "::notice::CURSEFORGE_TOKEN or CURSEFORGE_PROJECT_ID not set, skipping CurseForge upload"
  exit 0
fi

if [[ -n "${CURSEFORGE_GAME_VERSIONS:-}" ]]; then
  game_versions=$(tr ',' '\n' <<<"$CURSEFORGE_GAME_VERSIONS" | jq -R 'tonumber' | jq -s .)
else
  versions=$(curl -sf -H "X-Api-Token: $CURSEFORGE_TOKEN" "$api/game/versions")
  game_versions=$(jq --arg v "$server_version" '[.[] | select(.name == $v) | .id]' <<<"$versions")
  if [[ "$game_versions" == "[]" ]]; then
    echo "::error::no CurseForge game version named $server_version. Set CURSEFORGE_GAME_VERSIONS, known names:"
    jq -r '.[].name' <<<"$versions" | sort -u | tr '\n' ' '
    exit 1
  fi
fi

jq -n --rawfile changelog "$changelog" --arg display "$display" --arg type "$release_type" \
  --argjson versions "$game_versions" \
  '{changelog: $changelog, changelogType: "html", displayName: $display, releaseType: $type, gameVersions: $versions}' \
  > metadata.json

response=$(curl -s -w '\n%{http_code}' -X POST "$api/projects/$CURSEFORGE_PROJECT_ID/upload-file" \
  -H "X-Api-Token: $CURSEFORGE_TOKEN" -F "metadata=<metadata.json" -F "file=@$jar")
status=$(tail -n1 <<<"$response")
body=$(sed '$d' <<<"$response")
rm -f metadata.json

if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
  echo "::error::CurseForge upload failed (HTTP $status): $body"
  exit 1
fi
echo "uploaded to CurseForge: $body"
