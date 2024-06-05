#!/bin/bash

alias stoic=$stoic_dir/out/rel/bin/stoic

should_be_hello() {
    stoic shell -T <<EOF
        echo "hello"
EOF
}

#
# A common cause of this problem: running an `adb shell` command - that
# shouldn't take input - without redirecting input to /dev/null. `adb shell`
# will swallow input even if its running a command that doesn't use it.
#
if [[ "$(should_be_hello)" != "hello" ]]; then
    echo "shell captured input stream"
    exit 1
fi
