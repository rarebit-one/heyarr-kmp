#!/bin/bash
# scripts/install-desktop.sh — build heyarr-desktop and install it as a
# version-named, symlink-selected local app.
#
# Why this exists: nothing in this repo installed the desktop app anywhere —
# the only local launch path was scripts/heyarr-desktop, which execs the raw
# dev build under composeApp/build/. That build directory is silently
# overwritten by the next `./gradlew ... createDistributable`, including one
# run for an unrelated reason (a compile check, a screenshot regen), so
# whatever the .desktop launcher points at can go stale with no signal that it
# did. This script is the fix: each install lands at its own version-named
# directory under ~/.local/lib/heyarr-desktop/, and only a `current` symlink
# flip switches what actually launches — an install of vNext never disturbs
# what vCurrent was already running, and "what does `current` point at" is a
# one-command answer to "which version am I running" instead of a build
# timestamp nobody thought to check.
#
# Usage: scripts/install-desktop.sh [version]
#   version defaults to `git describe --tags --always --dirty` — a clean tag
#   name (e.g. v0.9.0) for a tagged release, or a describe string
#   (v0.9.0-3-gabcdef) for a dev install ahead of the last tag. Pass one
#   explicitly to name a release install by its tag rather than by whatever
#   HEAD happens to be right now.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

VERSION=${1:-$(git describe --tags --always --dirty)}
LIB_DIR="$HOME/.local/lib/heyarr-desktop"
DEST="$LIB_DIR/$VERSION"
BIN_DIR="$HOME/.local/bin"
LAUNCHER="$BIN_DIR/heyarr-desktop"
DESKTOP_FILE="$HOME/.local/share/applications/heyarr-desktop.desktop"

echo "building $VERSION..."
./gradlew :composeApp:createDistributable --console=plain

BUILD_OUT="composeApp/build/compose/binaries/main/app/heyarr-desktop"
[[ -d "$BUILD_OUT" ]] || { echo "ERROR: no distributable at $BUILD_OUT" >&2; exit 1; }

# Copy BUILD_OUT's contents (bin/, lib/) straight into DEST — not BUILD_OUT itself.
# Each version already gets its own directory, so nesting an extra app/heyarr-desktop/
# inside it would just repeat that per version for no reason.
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$BUILD_OUT"/. "$DEST"/

ln -sfn "$VERSION" "$LIB_DIR/current"

mkdir -p "$BIN_DIR"
cat > "$LAUNCHER" <<'EOF'
#!/bin/sh
# _JAVA_AWT_WM_NONREPARENTING: Hyprland / sway / dwm are non-reparenting window
# managers; without this AWT never accepts the compositor's resize and paints
# only its initial 1280x800 (matches heyarr-kmp's scripts/heyarr-desktop).
export _JAVA_AWT_WM_NONREPARENTING=1
exec "$HOME/.local/lib/heyarr-desktop/current/bin/heyarr-desktop" "$@"
EOF
chmod +x "$LAUNCHER"

mkdir -p "$(dirname "$DESKTOP_FILE")"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=Heyarr Desktop
GenericName=Media Library
Comment=Self-hosted media library — movies, series, music, books
Exec=$LAUNCHER
Icon=$LIB_DIR/current/lib/heyarr-desktop.png
Terminal=false
Categories=AudioVideo;Player;
Keywords=heyarr;media;library;movies;series;music;books;
StartupNotify=true
StartupWMClass=heyarr-desktop
EOF

echo "installed $VERSION -> $LIB_DIR/current"
echo "quit and relaunch the app (via $LAUNCHER or the .desktop entry) to pick it up"
