#!/bin/bash
# set -x
set -euo pipefail

if [ "$1" != "adb" ]; then
  #>&2 echo '$1='"'$1'"
  export ANDROID_SERIAL="$1"
fi
shift

if [ "$1" != "rsync" ]; then
  >&2 echo "Unexpected: $1 (expected rsync)"
  exit 1
fi
shift

adb_rsync_path=/data/local/tmp/.stoic/sync/bin/rsync 
#>&2 echo adb_rsync_path=$adb_rsync_path
#>&2 echo '$@=' "$@"
adb shell "$adb_rsync_path" "$@"
#>&2 echo 'done'
