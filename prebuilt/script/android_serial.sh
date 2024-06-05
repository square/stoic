# This file is designed to be sourced

if [ -n "${ZSH_VERSION:-}" ]; then
    # For Zsh
    _android_serial_script_dir="$(realpath "$(dirname "${(%):-%N}")")"
elif [ -n "${BASH_VERSION:-}" ]; then
    # For Bash
    _android_serial_script_dir="$(realpath "$(dirname "${BASH_SOURCE[0]}")")"
else
    _android_serial_script_dir="-unsupported-shell-$SHELL-"
fi

_adb_pick() {
    # Declare and assign separately to avoid masking return values
    ANDROID_SERIAL="$("$_android_serial_script_dir/adb-pick")"
    export ANDROID_SERIAL
}

_android_serial() {
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        return 0
    fi

    # Check the number of connected devices
    local device_count
    if ! device_count=$(adb devices | grep -cw "device"); then
        >&2 echo "No devices connected. Please connect a device and try again."
        return 1
    elif [ "$device_count" -eq 1 ]; then
        # Set ANDROID_SERIAL to the single connected device
        # Declare and assign separately to avoid masking return values
        ANDROID_SERIAL=$(adb devices | grep -w "device" | cut -f 1)
        export ANDROID_SERIAL
    else
        # Multiple devices found, prompt the user to pick one
        >&2 echo "Multiple devices found. Please pick one:"
        _adb_pick
    fi
}

alias android-serial=_android_serial
alias adb-pick=_adb_pick
