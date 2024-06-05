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

mkdir -p "$stoic_core_sync_dir"/{plugins,stoic,bin,apk}
check_required "build-tools;$stoic_build_tools_version" "platforms;android-$stoic_target_api_level" "ndk;$stoic_ndk_version"

cd "$stoic_kotlin_dir"

# exampleapp is the debug app that's used by default. It needs to be debug so
# that stoic can attach to it.
./gradlew :hostMain:assemble :stoicAndroid:assemble :androidServer:dexJar :androidClient:dexJar :plugin_helloworld:dexJar :plugin_appexitinfo:dexJar :plugin_error:dexJar :exampleapp:assembleDebug
cp hostMain/build/libs/hostMain.jar "$stoic_release_dir"/jar/
cp stoicAndroid/build/libs/stoicAndroid.jar "$stoic_release_dir"/jar/
cp stoicAndroid/build/libs/stoicAndroid-sources.jar "$stoic_release_dir"/jar/
cp androidServer/build/libs/androidServer.dex.jar "$stoic_core_sync_dir/stoic/stoic.dex.jar"
cp androidClient/build/libs/androidClient.dex.jar "$stoic_core_sync_dir/stoic/stoic-client.dex.jar"
cp plugin_helloworld/build/libs/plugin_helloworld.dex.jar "$stoic_core_sync_dir/plugins/helloworld.dex.jar"
cp plugin_appexitinfo/build/libs/plugin_appexitinfo.dex.jar "$stoic_core_sync_dir/plugins/appexitinfo.dex.jar"
cp plugin_error/build/libs/plugin_error.dex.jar "$stoic_core_sync_dir/plugins/error.dex.jar"

cd "$stoic_dir/native"
make -j16 all

chmod -R a+rw "$stoic_core_sync_dir"

set +e
stoic_path="$(readlink -f "$(which stoic)")"
set -e

if [ -z "$stoic_path" ]; then 
    >&2 echo "WARNING: stoic is missing from your PATH. Add \`$stoic_dir/out/rel/bin\` to your PATH"
elif [ "$stoic_path" != "$stoic_dir/out/rel/bin/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just built is in \`$stoic_dir/out/rel/bin\`"
fi


echo
echo
echo "----- Stoic build completed -----"
echo
echo
echo "Next run \`$stoic_dir/out/rel/bin/stoic setup\`"
