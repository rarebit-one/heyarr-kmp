#!/usr/bin/env bash
# Container provisioning for heyarr-desktop. The image brings JDK 21; the Gradle
# wrapper fetches Gradle 8.9 itself. What the image lacks is the handful of X11 /
# GL / fontconfig libraries Skiko (Compose's Skia binding) dlopens even when
# rendering off-screen, plus Xvfb so the app can be launched headlessly for
# smoke tests and screenshots.
set -euo pipefail
cd "$(dirname "$0")/.."

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  libgl1 libgl1-mesa-dri libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 \
  libfontconfig1 libfreetype6 fonts-dejavu-core xvfb x11-utils imagemagick
sudo rm -rf /var/lib/apt/lists/*

./gradlew --version
echo "provisioned: $(java -version 2>&1 | head -1)"
