#!/bin/bash
# scripts/refresh-openapi-spec.sh — vendor heyarr-core's OpenAPI document for the
# parser contract test, or check whether the vendored copy has fallen behind.
#
# Why this exists: every heyarr wire parser in this repo is hand-written on JsonScan (no
# serialization library, by design), so nothing but a test notices when a parser reads a
# field the server does not send. :core's OpenApiContractTest checks the field names each
# parser reads against heyarr-core's `api/openapi.yaml`. Tests never touch the network, so
# the spec is vendored — converted to JSON so the test can read it without a YAML library —
# together with the heyarr-core commit it came from:
#
#   core/src/jvmTest/resources/openapi/heyarr-core-openapi.json    the spec, as JSON
#   core/src/jvmTest/resources/openapi/heyarr-core-openapi.source  where and when it came from
#
# Usage:
#   scripts/refresh-openapi-spec.sh [ref]          vendor the spec at a heyarr-core ref (default: main)
#   scripts/refresh-openapi-spec.sh --check [ref]  exit 1 if the vendored spec differs from that ref
#
# heyarr-core is public, so no token is needed. HEYARR_CORE_DIR=<a local heyarr-core clone>
# reads the spec from that clone's git objects instead of GitHub (fetch it first).
# Needs python3 with PyYAML (preinstalled on GitHub's ubuntu runners).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

REPO=rarebit-one/heyarr-core
SPEC_PATH=api/openapi.yaml
OUT_DIR=core/src/jvmTest/resources/openapi
OUT_JSON=$OUT_DIR/heyarr-core-openapi.json
OUT_SOURCE=$OUT_DIR/heyarr-core-openapi.source

CHECK=0
if [[ "${1:-}" == "--check" ]]; then
    CHECK=1
    shift
fi
REF=${1:-main}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Resolve the ref to a commit and fetch the YAML at exactly that commit.
if [[ -n "${HEYARR_CORE_DIR:-}" ]]; then
    COMMIT=$(git -C "$HEYARR_CORE_DIR" rev-parse --verify "$REF^{commit}" 2>/dev/null ||
        git -C "$HEYARR_CORE_DIR" rev-parse --verify "origin/$REF^{commit}")
    git -C "$HEYARR_CORE_DIR" show "$COMMIT:$SPEC_PATH" >"$TMP/openapi.yaml"
else
    if [[ "$REF" =~ ^[0-9a-f]{40}$ ]]; then
        COMMIT=$REF
    else
        COMMIT=$(git ls-remote "https://github.com/$REPO.git" "$REF" "refs/heads/$REF" "refs/tags/$REF" |
            awk 'NR==1 {print $1}')
        [[ -n "$COMMIT" ]] || { echo "refresh-openapi-spec: cannot resolve '$REF' in $REPO" >&2; exit 2; }
    fi
    curl -fsSL "https://raw.githubusercontent.com/$REPO/$COMMIT/$SPEC_PATH" -o "$TMP/openapi.yaml"
fi
YAML_BLOB=$(git hash-object "$TMP/openapi.yaml")

if [[ "$CHECK" == 1 ]]; then
    VENDORED_BLOB=$(sed -n 's/^yaml_blob=//p' "$OUT_SOURCE")
    VENDORED_COMMIT=$(sed -n 's/^commit=//p' "$OUT_SOURCE")
    if [[ "$VENDORED_BLOB" == "$YAML_BLOB" ]]; then
        echo "openapi: vendored spec matches $REPO@$REF ($COMMIT)."
        exit 0
    fi
    echo "openapi: vendored spec (from $REPO@$VENDORED_COMMIT) differs from $REPO@$REF ($COMMIT)."
    echo "openapi: run scripts/refresh-openapi-spec.sh $REF, then ./gradlew :core:jvmTest."
    exit 1
fi

mkdir -p "$OUT_DIR"

# YAML → JSON. `default=str` keeps an unquoted date in an example from breaking the dump;
# indent=1 keeps the vendored file diffable line by line.
python3 - "$TMP/openapi.yaml" "$OUT_JSON" <<'PY'
import json, sys, yaml
with open(sys.argv[1], encoding="utf-8") as f:
    spec = yaml.safe_load(f)
with open(sys.argv[2], "w", encoding="utf-8") as f:
    json.dump(spec, f, indent=1, ensure_ascii=False, default=str)
    f.write("\n")
PY

cat >"$OUT_SOURCE" <<EOF
# Vendored by scripts/refresh-openapi-spec.sh — do not edit by hand.
repo=$REPO
path=$SPEC_PATH
commit=$COMMIT
yaml_blob=$YAML_BLOB
EOF

echo "openapi: vendored $REPO@$COMMIT:$SPEC_PATH → $OUT_JSON"
