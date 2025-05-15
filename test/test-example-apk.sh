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
    expected="$1"
    lineno="$2"
    shift
    shift
    >&2 echo verify_output "$@"
    output="$("$@")"
    if [ "$output" != "$expected" ]; then
        echo "expected: '$expected'"
        echo "actual  : '$output'"
        abort "$lineno" Failed
    fi
}

verify_stderr() {
    expected="$1"
    lineno="$2"
    shift
    shift
    >&2 echo verify_stderr "$@"
    output="$("$@" 3>&1 1>/dev/null 2>&3)"
    return_code="$?"
    if [ "$output" != "$expected" ]; then
        echo "expected: '$expected'"
        echo "actual  : '$output'"
        abort "$lineno" Failed
    elif [ "$return_code" != "0" ]; then
      echo "Failed \"$@\" - returned $return_code"
    fi
}

cd "$script_dir"

# Verify we can install/run the example app automatically.
# NOTE: When we don't specify a package, we default to the example app, and the
# example app implies --start-if-needed
if [ -n "$(adb shell pm list package com.square.stoic.example)" ]; then
    adb uninstall com.square.stoic.example
fi
verify_output 'Hello world []'                                                             $LINENO stoic helloworld
adb shell am force-stop com.example.helloworld
verify_output 'Hello world []'                                                             $LINENO stoic helloworld

# Verify we can uninstall the example app (guard against https://github.com/square/stoic/issues/2) 
adb uninstall com.square.stoic.example

# Verify no stderr logs when reinstalling the example app
verify_stderr '' $LINENO stoic helloworld

# Verify no stderr logs when starting a previously stopped example app
adb shell am force-stop com.example.helloworld
verify_stderr '' $LINENO stoic helloworld

# Verify no stderr logs when restarting the example app
verify_stderr '' $LINENO stoic --restart helloworld
