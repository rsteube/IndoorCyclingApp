#!/usr/bin/env bash
# Publishes the RPM build.sh made as a release Harpoon can install, on the releases-only Codefloe
# repository. The code stays here; that repository holds the README with the add-to-Harpoon code
# and one release per version.
#
# The release contract Harpoon reads (see the sailfish-harpoon-publishing skill):
#   - the tag is the spec's Version, bare (0.2.0), and every release raises Version or Release;
#   - the .rpm itself is attached, named as sfdk writes it;
#   - the release is published: not a draft, not a prerelease.
#
# The tag must point at a commit no earlier release's tag points at: Codefloe dates a release by
# its tag's commit, Harpoon takes the newest release by that date, and two releases on one commit
# tie, so the new one may never be offered (0.3.0 was, on 2026-10-05). Add the release's notes to
# CHANGELOG.md there and tag that commit. The token, with the
# write:repository scope, is read from a file only its owner can read and is never put on a
# command line.
#
#   ./publish.sh notes.md       release notes in Markdown, shown in Harpoon's "What's new"
#
#   CODEFLOE_REPO   owner/name of the releases repository (default SnappTechnology/harbour-indoorbike)
#   CODEFLOE_TOKEN  the token file (default ~/.config/codefloe/release-token-indoorbike-sailfish)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

notes="${1:?usage: ./publish.sh notes.md}"
repo="${CODEFLOE_REPO:-SnappTechnology/harbour-indoorbike}"
token="${CODEFLOE_TOKEN:-$HOME/.config/codefloe/release-token-indoorbike-sailfish}"
api="https://codefloe.com/api/v1/repos/$repo"

spec=rpm/harbour-indoorbike.spec
name="$(sed -n 's/^Name:[[:space:]]*//p' "$spec")"
version="$(sed -n 's/^Version:[[:space:]]*//p' "$spec")"
release="$(sed -n 's/^Release:[[:space:]]*//p' "$spec")"
tag="$version"

shopt -s nullglob
rpms=(RPMS/"$name-$version-$release".*.rpm)
others=(RPMS/*.rpm)
if [ "${#rpms[@]}" -eq 0 ]; then
    echo "No RPMS/$name-$version-$release.*.rpm: run ./build.sh" >&2
    exit 1
fi
if [ "${#others[@]}" -ne "${#rpms[@]}" ]; then
    echo "RPMS/ holds packages of another version as well; remove them first" >&2
    exit 1
fi
[ -f "$token" ] || { echo "No token file at $token" >&2; exit 1; }

hdr="$(mktemp)"; out="$(mktemp)"
chmod 600 "$hdr"
trap 'rm -f "$hdr" "$out"' EXIT
printf 'Authorization: token %s\n' "$(tr -d '\r\n' < "$token")" > "$hdr"

# The tag's own commit, and no earlier release's: otherwise the two releases share a date.
python3 - "$api" "$tag" <<'EOF'
import json, sys, urllib.request
api, tag = sys.argv[1], sys.argv[2]
def get(path):
    with urllib.request.urlopen(api + path, timeout=30) as r:
        return json.load(r)
try:
    mine = get("/tags/" + tag)["commit"]["sha"]
except Exception:
    sys.exit(f"No tag {tag} in the releases repository: push it first")
for release in get("/releases?limit=50"):
    other = release["tag_name"]
    if other != tag and get("/tags/" + other)["commit"]["sha"] == mine:
        sys.exit(f"Tag {tag} is on the same commit as release {other}, so Harpoon could not tell "
                 f"them apart. Commit this release's notes to CHANGELOG.md and tag that commit.")
EOF

# An earlier run that stopped part way has made the release already: attach what is missing.
status="$(curl -s -o "$out" -w '%{http_code}' -H @"$hdr" "$api/releases/tags/$tag")"
if [ "$status" = 200 ]; then
    echo "Release $tag exists; attaching what is missing"
elif [ "$status" = 404 ]; then
    python3 - "$tag" "$notes" > "$out.json" <<'EOF'
import json, sys
tag, notes = sys.argv[1], sys.argv[2]
print(json.dumps({"tag_name": tag, "name": tag, "body": open(notes).read().strip(),
                  "draft": False, "prerelease": False}))
EOF
    status="$(curl -s -o "$out" -w '%{http_code}' -H @"$hdr" -H 'Content-Type: application/json' \
        --data-binary @"$out.json" -X POST "$api/releases")"
    rm -f "$out.json"
    [ "$status" = 201 ] || { echo "Creating the release failed ($status): $(cat "$out")" >&2; exit 1; }
    echo "Created release $tag"
else
    echo "Reading the release failed ($status): $(cat "$out")" >&2
    exit 1
fi
id="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["id"])' "$out")"
attached="$(python3 -c 'import json, sys; print("\n".join(a["name"] for a in json.load(open(sys.argv[1]))["assets"]))' "$out")"

for rpm in "${rpms[@]}"; do
    file="$(basename "$rpm")"
    if grep -qxF "$file" <<<"$attached"; then
        echo "Already attached: $file"
        continue
    fi
    status="$(curl -s -o "$out" -w '%{http_code}' -H @"$hdr" -X POST \
        -F "attachment=@$rpm;type=application/x-rpm" "$api/releases/$id/assets?name=$file")"
    [ "$status" = 201 ] || { echo "Uploading $file failed ($status): $(cat "$out")" >&2; exit 1; }
    echo "Attached $file"
done

# What Harpoon will see: the public API, asked without the token.
curl -s "$api/releases/tags/$tag" > "$out"
python3 - "$out" "${rpms[@]}" <<'EOF'
import json, os, sys
release = json.load(open(sys.argv[1]))
assets = {a["name"]: a["size"] for a in release.get("assets", [])}
ok = not release.get("draft") and not release.get("prerelease")
for rpm in sys.argv[2:]:
    name = os.path.basename(rpm)
    if assets.get(name) != os.path.getsize(rpm):
        print(f"{name}: {assets.get(name)} bytes published, {os.path.getsize(rpm)} local")
        ok = False
if not ok:
    sys.exit(f"The public release {release.get('tag_name')} is not what Harpoon needs: {release.get('message', '')}")
print(f"Published {release['tag_name']}: {release['html_url']}")
EOF
