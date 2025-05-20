#!/bin/bash
#set -x
set -euo pipefail

stoic_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"
stoic_kotlin_dir="$stoic_dir/kotlin"
stoic_release_dir="$stoic_dir/out/rel"
stoic_core_sync_dir="$stoic_release_dir/sync"
#stoic_min_api_level=26

source "$stoic_dir/prebuilt/script/util.sh"

mkdir -p "$stoic_release_dir"/jar
rsync --archive "$stoic_dir"/prebuilt/ "$stoic_release_dir"/

# Sets things up so that they are ready to be rsync'd to the device
# Actual rsyncing is done via `install` (and it will happen automatically each
# time a stoic command is run)

for arg in "$@"; do
    case $arg in
        *)
            >&2 echo "Unrecognized arg: $arg"
            exit 1
            ;;
    esac
done

verify_submodules() {
    for x in "$@"; do
        if [ -z "$(2>/dev/null ls "$stoic_dir/$x/")" ]; then
            return 1
        fi
    done

    return 0
}

if ! verify_submodules native/libbase/ native/fmtlib/ native/libnativehelper/; then
    >&2 echo "Submodules are missing. Likely your ran git clone without --recurse-submodules."
    >&2 echo "Okay to update? (Will run \`git submodule update --init --recursive\`)"
    read -r -p "Y/n? " choice
    case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
      n*)
        exit 1
        ;;
      *)
        >/dev/null pushd "$stoic_dir"
        git submodule update --init --recursive
        >/dev/null popd
        ;;
    esac
fi

mkdir -p "$stoic_core_sync_dir"/{plugins,stoic,bin,apk}
check_required "build-tools;$stoic_build_tools_version" "platforms;android-$stoic_target_api_level" "ndk;$stoic_ndk_version"

# Used by native/Makefile.inc
export ANDROID_NDK="$ANDROID_HOME/ndk/$stoic_ndk_version"

cd "$stoic_kotlin_dir"

# :demo-app:without-sdk is the debug app that's used by default. It needs to be debug so
# that stoic can attach to it.
./gradlew --parallel :host:main:assemble :android:plugin-sdk:assemble :android:server:injected:dexJar :android:main:dexJar :demo-plugin:helloworld:dexJar :demo-plugin:appexitinfo:dexJar :demo-plugin:breakpoint:dexJar :demo-plugin:crasher:dexJar :demo-plugin:testsuite:dexJar :demo-app:without-sdk:assembleDebug
cp host/main/build/libs/main.jar "$stoic_release_dir"/jar/host-main.jar
cp android/plugin-sdk/build/libs/plugin-sdk.jar "$stoic_release_dir"/jar/android-plugin-sdk.jar
cp android/plugin-sdk/build/libs/plugin-sdk-sources.jar "$stoic_release_dir"/jar/android-plugin-sdk-sources.jar
cp android/server/injected/build/libs/injected.dex.jar "$stoic_core_sync_dir/stoic/android-server-injected.dex.jar"
cp android/main/build/libs/main.dex.jar "$stoic_core_sync_dir/stoic/android-main.dex.jar"
cp demo-plugin/appexitinfo/build/libs/appexitinfo.dex.jar "$stoic_core_sync_dir/plugins/appexitinfo.dex.jar"
cp demo-plugin/breakpoint/build/libs/breakpoint.dex.jar "$stoic_core_sync_dir/plugins/breakpoint.dex.jar"
cp demo-plugin/crasher/build/libs/crasher.dex.jar "$stoic_core_sync_dir/plugins/crasher.dex.jar"
cp demo-plugin/helloworld/build/libs/helloworld.dex.jar "$stoic_core_sync_dir/plugins/helloworld.dex.jar"
cp demo-plugin/testsuite/build/libs/testsuite.dex.jar "$stoic_core_sync_dir/plugins/testsuite.dex.jar"
cp demo-app/without-sdk/build/outputs/apk/debug/without-sdk-debug.apk "$stoic_core_sync_dir/apk/demo-app-without-sdk-debug.apk"

cd "$stoic_dir/native"
make -j16 all

chmod -R a+rw "$stoic_core_sync_dir"

echo
echo
echo "----- Stoic build completed -----"
echo
echo

set +e
stoic_path="$(readlink -f "$(which stoic)")"
set -e

if [ -z "$stoic_path" ]; then
    case "$SHELL" in
      */bash)
        config_file='~''/.bashrc'
        ;;
      */zsh)
        config_file='~''/.zshrc'
        ;;
      *)
        config_file="<path-to-your-config-file>"
        ;;
    esac

    >&2 echo "WARNING: stoic is missing from your PATH. Next, please run:"
    >&2 echo
    >&2 echo "    echo export PATH=\$PATH:$stoic_dir/out/rel/bin >> $config_file && source $config_file"
    >&2 echo "    stoic setup"
    >&2 echo
elif [ "$stoic_path" != "$stoic_dir/out/rel/bin/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just built is in \`$stoic_dir/out/rel/bin\`"
    >&2 echo "Next, please run: \`$stoic_dir/out/rel/bin/stoic setup\`"
    >&2 echo
else
    >&2 echo "Next, please run:"
    >&2 echo
    >&2 echo "    stoic setup"
    >&2 echo
fi
