#!/bin/sh
# Builds the debug APK and installs it over adb. Usage: scripts/install-debug.sh <address[:port]>
exec "$(dirname "$0")/install.sh" debug "$@"
