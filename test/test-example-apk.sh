#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(dirname "$(readlink -f "$0")")"

# Function to exit with line number
abort() {
    >&2 echo "line $1: $2"
    exit 1
}

verify_output() {
    {
       set +x
    } 2>/dev/null  # Don't print set +x

    expected="$1"
    lineno="$2"
    shift
    shift
    output="$("$@")"
    if [ "$output" != "$expected" ]; then
        echo "expected: '$expected'"
        echo "actual  : '$output'"
        abort "$lineno" Failed
    fi
    set -x
}

cd "$script_dir"

# Verify we can install/run the example app automatically
if [ -n "$(adb shell pm list package com.square.stoic.example)" ]; then
    adb uninstall com.square.stoic.example
fi
verify_output 'Hello world []'                                                             $LINENO stoic helloworld
adb shell am force-stop com.example.helloworld
verify_output 'Hello world []'                                                             $LINENO stoic helloworld

# Verify we can uninstall the example app (guard against https://github.com/square/stoic/issues/2) 
adb uninstall com.square.stoic.example
