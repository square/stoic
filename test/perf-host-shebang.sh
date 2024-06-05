#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(dirname "$(readlink -f "$0")")"

# We cd so that we can run ./TestHostShebang.kt in a subshell without any env
# vars
cd "$script_dir"

# warm-up
./TestHostShebang.kt


>&2 echo "Timing how long it takes to run 10 times"
time sh << 'EOF'
for i in {0..9}; do
  ./TestHostShebang.kt
done
EOF
