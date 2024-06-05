#!/bin/bash
script_path="$(readlink -f "$0")"
script_dir="$(realpath "$script_path/..")"

cat << EOF
Welcome to \`stoic shell\`. Please modify $script_path to adjust the command
used to start a new interactive shell (and/or remove this banner). Even if you
choose to stick with the default shell you can adjust the configuration in
$script_dir/sync/config/mkshrc. See
https://github.com/square/stoic/USR_CONFIG.md for details on how to change your
shell and more customization tips. 
EOF

adb shell -t "STOIC_DEVICE_SYNC_DIR=$STOIC_DEVICE_SYNC_DIR" "ENV=$STOIC_DEVICE_SYNC_DIR/config/mkshrc" sh
