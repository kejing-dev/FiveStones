#!/usr/bin/env bash
# Five Stones dev harness (macOS / Linux). Pinned to one emulator: DEV (default emulator-5562).
#   tools/fs.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.fivestones.DEBUG --es cmd NAME --es a ARG
#   tools/fs.sh shot OUT.png       still capture of the device (pico-cli capture screenshot)
#   tools/fs.sh log [REGEX]        logcat lines of tag FiveStones (optionally filtered)
#   tools/fs.sh install            install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5562}"
PKG=io.embodit.fivestones
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --out "$2" --replace ;;
  log) "$ADB" -s "$DEV" logcat -d -s FiveStones:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
