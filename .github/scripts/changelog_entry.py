"""Print the CHANGELOG.md entry for one version (the HTML between its <h2> and the next <hr>).

usage: changelog_entry.py <version> [CHANGELOG.md]
Fails when the entry is missing, so a tag can't be released without release notes.
"""
import re, sys

version = sys.argv[1]
path = sys.argv[2] if len(sys.argv) > 2 else "CHANGELOG.md"
text = open(path, encoding="utf-8").read().replace("\r\n", "\n")

match = re.search(r"<h2>\[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=\n<hr>|\n<h2>|\Z)", text, re.S)
if not match or not match.group(1).strip():
    sys.exit(f"no CHANGELOG entry for {version} in {path}")
print(match.group(1).strip())
