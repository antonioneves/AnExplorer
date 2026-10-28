#!/bin/sh
# Builds the debug or release APK and installs it on a device over adb.
# Usage: scripts/install.sh <debug|release> <address[:port]>   (the port defaults to 5555)
set -e

variant=$1
address=$2
if [ "$variant" != debug ] && [ "$variant" != release ] || [ -z "$address" ]; then
    echo "Usage: $0 <debug|release> <address[:port]>" >&2
    exit 1
fi
case $address in
    *:*) ;;
    *) address=$address:5555 ;;
esac

cd "$(dirname "$0")/.."

if [ "$variant" = debug ]; then
    task=:app:assembleDebug
else
    task=:app:assembleRelease
fi
./gradlew -q "$task"

apk=app/build/outputs/apk/$variant/app-$variant.apk
if [ ! -f "$apk" ]; then
    echo "No $apk: a release build without keystore.properties is unsigned and can't be installed" >&2
    exit 1
fi

adb connect "$address" > /dev/null
adb -s "$address" install -r "$apk"
