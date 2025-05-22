#!/bin/bash
#set -x
set -euo pipefail

script_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"
release_dir="$script_dir/.."

source "$release_dir/stoic.properties"
if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ANDROID_HOME env variable not defined. This should be the path to your Android SDK."
    echo "e.g."
    echo "    export ANDROID_HOME=~/Library/Android/sdk"
    exit 1
fi

# Find sdkmanager script (falling back to the old location if the new one
# is missing)
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -e "$sdkmanager" ]; then
    >&2 echo "Failed to find sdkmanager in its usual location."
    >&2 echo "Please update Android SDK Command-line Tools to the latest version."
    exit 1
fi

sdk_packages="$("$sdkmanager" --list_installed 2>/dev/null | awk '{print $1}')"
missing=()
for required in "$@"; do
    if ! echo "$sdk_packages" | grep "$required" >/dev/null; then
        missing+=("$required")
    fi
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "stoic requires Android SDK packages: ${missing[*]}"
    echo "Okay to install? (will run '$sdkmanager ${missing[*]}')"
    read -r -p "Y/n? " choice
    case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
      n*)
        exit 1
        ;;
      *)
        for x in "${missing[@]}"; do
            $sdkmanager "$x"
        done
        ;;
    esac
fi
