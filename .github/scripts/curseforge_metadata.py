"""Write the CurseForge upload metadata.

usage: curseforge_metadata.py <game versions json> <wanted> <display name> <release type> <changelog html> <out>
<wanted> is a comma separated list of CurseForge game version names or ids.
"""
import json, sys

versions_file, wanted, display, release_type, changelog_file, out_file = sys.argv[1:7]
versions = json.load(open(versions_file, encoding="utf-8"))

ids, missing = [], []
for entry in (e.strip() for e in wanted.split(",")):
    if not entry:
        continue
    if entry.isdigit():
        ids.append(int(entry))
        continue
    found = [v["id"] for v in versions if v.get("name") == entry]
    if found:
        ids.extend(found)
    else:
        missing.append(entry)

if missing or not ids:
    known = ", ".join(f'{v["id"]} ({v["name"]})' for v in versions)
    print(f"::error::no CurseForge game version named {', '.join(missing) or wanted!r}. "
          f"Set CURSEFORGE_GAME_VERSIONS to names or ids, CurseForge has: {known}")
    sys.exit(1)

with open(out_file, "w", encoding="utf-8") as out:
    json.dump({
        "changelog": open(changelog_file, encoding="utf-8").read(),
        "changelogType": "html",
        "displayName": display,
        "releaseType": release_type,
        "gameVersions": sorted(set(ids)),
    }, out)
