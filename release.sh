#!/bin/bash
#set -x
set -euo pipefail

validate_semver() {
    local version=$1
    if [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        return 0
    else
        echo "Version $version does not follow semantic versioning (x.y.z)"
        return 1
    fi
}

# Function to compare two semantic versions
semver_is_larger() {
    local current_version="$1"
    local new_version="$2"

    # Split the versions into their components
    IFS='.' read -r -a current <<< "$current_version"
    IFS='.' read -r -a new <<< "$new_version"

    # Compare major, minor, and patch versions
    for i in 0 1 2; do
        if (( new[i] > current[i] )); then
            return 0
        elif (( new[i] < current[i] )); then
            return 1
        fi
    done

    # If all components are equal, the versions are the same
    return 1
}

increment_semver() {
    local current_version="$1"

    # Split the versions into their components
    IFS='.' read -r -a current <<< "$current_version"
    local last=$((current[2]+1))
    echo "${current[0]}.${current[1]}.$last"
}

stoic_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"
cd "$stoic_dir"
source "$stoic_dir/prebuilt/script/util.sh"

if [ $# != 1 ]; then
    >&2 echo "Expected exactly one argument, the next version (current version is $stoic_version)"
    exit 1
elif [ "$1" = "$stoic_version" ]; then
    >&2 echo "current version is already $stoic_version"
    exit 1
elif ! validate_semver "$1"; then
    >&2 echo "Please provide a valid semver"
    exit 1
elif ! semver_is_larger "$stoic_version" "$1"; then
    >&2 echo "Provide a version larger than the current version: $stoic_version"
    exit 1
fi
old_version="$stoic_version"
stoic_version="$1"

mkdir -p "$stoic_dir/releases"
archive_name="$stoic_dir/releases/stoic-$stoic_version.tar.gz"

if [ -n "$(git status --porcelain)" ]; then
    >&2 echo "The git repository has uncommitted changes or untracked files. Aborting..."
    exit 1
fi

sed -i "" "s/stoic_version=$old_version/stoic_version=$stoic_version/" "$stoic_dir/prebuilt/script/util.sh"

# Remove out/ to get rid of any stale artifacts
rm -r out/
./build.sh
tar -czf "$archive_name" -C out/rel .


new_version="$(increment_semver "$stoic_version")"
sed -i "" "s/stoic_version=$old_version/stoic_version=$stoic_version/" "$stoic_dir/prebuilt/script/util.sh"

echo
echo
echo "Created $archive_name and incremented the version to $new_version"
echo
echo
