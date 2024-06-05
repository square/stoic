#!/bin/bash
#set -x
set -euo pipefail

usage() {
  echo "$0 [--help]"
  echo "Installs stoic to /usr/local/bin and /usr/local/share/stoic"
}

while [ $# -gt 0 ]; do
  arg="$1"
  shift
  case "$arg" in
    --help)
      usage
      exit 0
      ;;
    *)
      >&2 usage
      exit 1
      ;;
  esac
done

script_dir="$(dirname "$(readlink -f "$0")")"
stoic_release_dir="$(realpath "$script_dir/..")"
stoic_usr_config_dir=~/.config/stoic
stoic_usr_sync_dir="$stoic_usr_config_dir/sync"

# Set up usr config dir, but don't overwrite anything (except for stoicAndroid)
mkdir -p "$stoic_usr_sync_dir/"
rsync --archive --ignore-existing "$stoic_release_dir/template/usr_config/" "$stoic_usr_config_dir/"

echo "Initialized ~/.config/stoic"

source "$script_dir/util.sh"

# TODO: I'm not sure these should be truly required - you can use prebuilt plugins without anything
check_required "build-tools;$stoic_build_tools_version" "platforms;android-$stoic_target_api_level"

echo "Verified required Android SDK components"

stoic_version_dir=/usr/local/share/stoic/$stoic_version

printf "\nRequesting sudo permissions to write to /usr/local/bin/stoic and /usr/local/share/stoic\n"
set -x
sudo mkdir -p $stoic_version_dir
sudo rsync --archive --delete "$stoic_release_dir"/ "$stoic_version_dir"/
sudo ln -sF /usr/local/share/stoic/$stoic_version/ /usr/local/share/stoic/current_version
sudo ln -sF $stoic_version_dir/bin/stoic /usr/local/bin/stoic
set +x

if ! which stoic >/dev/null; then
    >&2 echo You may need to add /usr/local/bin to your PATH
fi
set -e
stoic_path="$(readlink -f "$(which stoic)")"
if [ "$?" -ne 0 ]; then 
    >&2 echo "WARNING: stoic is missing from your PATH. Add \`/usr/local/bin\` to your PATH"
elif [ "$stoic_path" != "/usr/local/bin/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just installed is in \`/usr/local/bin\`"
    exit 1
fi

printf "\n\n----- Stoic install completed -----\n\n"
