#!/bin/bash
# DRIVEDECK: install the latest release on your phone so Android Auto shows it.
#
# Android Auto only lists apps made with the Car App Library when they come from a trusted store,
# and the "Unknown sources" switch doesn't cover them. This installs the APK over USB and marks it
# as installed by Google Play, so Android Auto accepts it. Your data is kept (it's an update, not a reinstall).
#
# Run it by double-clicking this file in Finder, or in Terminal:
#   bash ~/Documents/GitHub/DRIVEDECK-android/tools/install-to-phone.command
set -e
cd "$(dirname "$0")"
PT="$HOME/Library/Android/platform-tools"
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  if [ ! -x "$PT/adb" ]; then
    echo "→ Downloading Android platform tools (adb) from Google, one time…"
    mkdir -p "$HOME/Library/Android"
    curl -fsSL -o /tmp/platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
    unzip -oq /tmp/platform-tools.zip -d "$HOME/Library/Android"
  fi
  ADB="$PT/adb"
fi

echo "→ Downloading the latest DRIVEDECK release from GitHub…"
URL=$(curl -fsSL https://api.github.com/repos/LAZARZEC18/DRIVEDECK/releases/latest | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')
[ -z "$URL" ] && { echo "✗ Couldn't find a release APK."; exit 1; }
curl -fsSL -o /tmp/DRIVEDECK.apk "$URL"
echo "  $(basename "$URL")"

echo "→ Waiting for your phone (USB cable plugged in, USB debugging on, tap Allow on the phone)…"
"$ADB" start-server >/dev/null
"$ADB" wait-for-device

echo "→ Installing as a Play Store app…"
if ! "$ADB" install -r -i com.android.vending /tmp/DRIVEDECK.apk; then
  echo
  echo "The phone wouldn't switch the installer on an update. It needs a clean install instead."
  echo "Places, music and stats come back from cloud sync (Setup → Cloud sync must be connected first)."
  read -r -p "Uninstall DRIVEDECK and install it fresh? [y/N] " ans
  if [ "$ans" = "y" ] || [ "$ans" = "Y" ]; then
    "$ADB" uninstall com.drivedeck || true
    "$ADB" install -i com.android.vending /tmp/DRIVEDECK.apk
  else
    exit 1
  fi
fi
INSTALLER=$("$ADB" shell pm list packages -i com.drivedeck | tr -d '\r')
echo "  $INSTALLER"
echo
echo "✓ Done. Now: Android Auto → Force stop it (Settings → Apps → Android Auto), then check Customise launcher."
read -n 1 -s -r -p "Press any key to close."
