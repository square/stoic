# shellcheck shell=bash
# intended to be sourced

# These are used by scripts that source this one
# shellcheck disable=SC2034
stoic_version=0.0.1
stoic_target_api_level=34
stoic_build_tools_version=34.0.0
stoic_ndk_version=26.3.11579264

if [ -z "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME env variable not defined. This should be the path to your Android SDK."
    echo "e.g."
    echo "    export ANDROID_HOME=~/Library/Android/sdk"
    exit 1
fi

function check_required {
    # Check for required packages
    sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    sdk_packages="$("$sdkmanager" --list_installed 2>/dev/null | awk '{print $1}')"
    missing=()
    for required in "$@"; do
        if ! echo "$sdk_packages" | grep "$required" >/dev/null; then
            missing+=("$required")
        fi
    done
    if [ ${#missing[@]} -gt 0 ]; then
        echo "stoic requires Android SDK package ${missing[*]}"
        echo "Okay to install? (will run '$sdkmanager ${missing[*]}')"
        read -r -p "Y/n: " choice
        case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
          n*)
            exit 1
            ;;
          *)
            $sdkmanager "${missing[@]}"
            ;;
        esac
    fi
}

