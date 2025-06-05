#!/bin/bash
script_path="$(readlink -f "$0")"
script_dir="$(realpath "$script_path/..")"

if [ $# -eq 0 ]; then
  # Emit a greeting if the shell is being used interactively
  if [ "$(basename "$script_dir")" = "usr_config" ]; then
    >&2 cat << EOF
Welcome to \`stoic --tool shell\`. Please run \`stoic --tool setup\` to enable shell customization.
EOF
  else
    >&2 cat << EOF
Welcome to \`stoic --tool shell\`. Please modify $script_path to adjust the
command used to start a new interactive shell (and/or remove this banner). Even
if you choose to stick with the default shell you can adjust the configuration
in $script_dir/sync/config/mkshrc. See
https://github.com/square/stoic/USR_CONFIG.md for details on how to change your
shell and more customization tips. 
EOF
  fi
fi

if [ $# -eq 0 ]; then
  SH_ARGS=""
else
  # Double-escape - once for `adb shell`, once for `sh -c`
  SH_ARGS="-c "$(printf " %q" "$(printf " %q" "$@")")""
fi

adb shell $STOIC_TTY_OPTION "STOIC_DEVICE_SYNC_DIR=$STOIC_DEVICE_SYNC_DIR" "ENV=$STOIC_DEVICE_SYNC_DIR/config/mkshrc" sh $SH_ARGS
