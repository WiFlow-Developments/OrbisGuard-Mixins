#!/usr/bin/env bash
# Upload a jar to CurseForge.
# usage: curseforge_upload.sh <jar> <display name> <release type: release|beta|alpha> <changelog html file> <server version>
#
# Needs CURSEFORGE_TOKEN (secret) and CURSEFORGE_PROJECT_ID (variable). Skips with a notice when
# either is missing. CURSEFORGE_GAME_VERSIONS picks the CurseForge game versions as comma separated
# names or ids (Hytale on CurseForge only has "1.0" so far); unset means the one named like the
# server version.
set -euo pipefail

jar=$1 display=$2 release_type=$3 changelog=$4 server_version=$5
api="https://${CURSEFORGE_API_HOST:-42.curseforge.com}/api"

if [[ -z "${CURSEFORGE_TOKEN:-}" || -z "${CURSEFORGE_PROJECT_ID:-}" ]]; then
  echo "::notice::CURSEFORGE_TOKEN or CURSEFORGE_PROJECT_ID not set, skipping CurseForge upload"
  exit 0
fi

scripts=$(dirname "$0")
curl -sf -H "X-Api-Token: $CURSEFORGE_TOKEN" "$api/game/versions" > game-versions.json
python3 "$scripts/curseforge_metadata.py" game-versions.json "${CURSEFORGE_GAME_VERSIONS:-$server_version}" \
  "$display" "$release_type" "$changelog" metadata.json

response=$(curl -s -w '\n%{http_code}' -X POST "$api/projects/$CURSEFORGE_PROJECT_ID/upload-file" \
  -H "X-Api-Token: $CURSEFORGE_TOKEN" -F "metadata=<metadata.json" -F "file=@$jar")
status=$(tail -n1 <<<"$response")
body=$(sed '$d' <<<"$response")
rm -f metadata.json game-versions.json

if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
  echo "::error::CurseForge upload failed (HTTP $status): $body"
  exit 1
fi
echo "uploaded to CurseForge: $body"
