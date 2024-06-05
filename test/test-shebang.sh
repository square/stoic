#!/bin/bash
set -euxo pipefail
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

verify_output 'args: [hello world, !]'                                                     $LINENO ./TestHostShebang.kt "hello world" "!"
verify_output 'args: []'                                                                   $LINENO ./TestHostShebang.kt
verify_output 'pkg: com.square.stoic.example, args: [hello world, !]'                      $LINENO ./TestXplatShebang.kt "hello world" "!"
verify_output 'pkg: jvm, args: [hello world, !]'                                           $LINENO ./TestXplatShebang.kt --host "hello world" "!"
verify_output 'pkg: com.square.stoic.example, args: []'                                    $LINENO ./TestXplatShebang.kt
verify_output 'pkg: jvm, args: []'                                                         $LINENO ./TestXplatShebang.kt --host
verify_output "pkg: jvm, $script_dir/TestSrcPathRefShebang.kt"                             $LINENO ./TestSrcPathRefShebang.kt --host
verify_output "pkg: com.square.stoic.example, $script_dir/TestSrcPathRefShebang.kt"        $LINENO ./TestSrcPathRefShebang.kt

