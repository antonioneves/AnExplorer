#!/bin/sh
# Builds the release APK and installs it over adb. Usage: scripts/install-release.sh <address[:port]>
exec "$(dirname "$0")/install.sh" release "$@"
