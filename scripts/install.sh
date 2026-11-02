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

adb connect "$address" > /dev/null

# One APK per CPU, so pick the first ABI the TV supports that has a build
for abi in $(adb -s "$address" shell getprop ro.product.cpu.abilist | tr -d '\r' | tr ',' ' '); do
    apk=app/build/outputs/apk/$variant/app-$abi-$variant.apk
    [ -f "$apk" ] && break
    apk=
done
if [ -z "$apk" ]; then
    echo "No APK for this TV's CPU: a release build without keystore.properties is unsigned and can't be installed" >&2
    exit 1
fi

adb -s "$address" install -r "$apk"
