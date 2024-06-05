#!/bin/bash
#set -x
set -euo pipefail

source "$(dirname "$(readlink -f "$0")")/../script/common.sh"
android_serial

stoic shell -T << 'EOF'
time sh -c 'for x in 0 1 2 3 4 5 6 7 8 9; do stoic --restart helloworld; done'
EOF
