# Introduction

> *"Σκόπει εἰς σεαυτόν. Ἐντὸς σοῦ πηγὴ ἀγαθοῦ ἐστιν, ἡ ἀεὶ ἐκβρύειν ἑτοίμη, ἐὰν ἀεὶ σκάπτῃς."*
>
> "Look within. Within is the fountain of good, and it will ever bubble up, if you will ever dig."

*- Marcus Aurelius (121-180 AD), Roman Emperor and Stoic philosopher*

> *"Ignis aurum probat, miseria fortes."*
>
> "Fire tests gold and adversity tests the brave."

*-Seneca the Younger (c. 4 BC - AD 65), Roman statesman and Stoic philosopher*

<br>

Stoic lets you look within your Android processes, giving you the courage to
take on difficult bugs.

Stoic is a tool for
1. running code inside another process - without any modifications its APK,
2. exposing extra capabilities to code, normally only available to a debugger, and
3. blurring the lines between code and debugger

You can write plugins that
1. provide command-line access to APIs normally only available inside the process
2. leverage debugger functionality (e.g. use breakpoints to hook arbitrary methods)
3. examine the internal state of a process without restarting the process

Stoic is fast. The first time you run a Stoic plugin in a process it will take 2-3
seconds to attach. Thereafter, Stoic plugins typically run in less than a second.

When you run Stoic on your laptop it syncs itself (via rsync over adb) to your
Android device. Most of the functionality is actually run directly on your device,
so you can run it directly from `adb shell` if you prefer. Anything inside of
`~/.config/stoic/sync` will also be sync'd, so you can have all your favorite
command-line utilities (bash, vim, etc) available whenever you connect to a new
device or emulator, pre-configured according to your custom bashrc/vimrc/etc.

It's easy to get started using Stoic:
1. Checkout the repo: `git clone https://github.com/square/stoic && cd stoic`
3. Build it: `./build.sh`
4. Setup configuration: `stoic setup` (this initializes `~/.config/stoic`)
5. Run your first Stoic plugin: `stoic --pkg com.square.stoic.example scratch`
   (if you don't specify a package, Stoic will run on `com.square.stoic.example`
   by default - a simple app bundled with Stoic)
7. Open up `~/.config/stoic/plugin` with Android Studio to modify this plugin and explore what Stoic can do.

Stoic works on any device / emulator (that I've tested so far). 

Stoic bundles a few plugins:
1. appexitinfo - command-line access to the ApplicationExitInfo API
2. breakpoint - print when methods get called, optionally with arguments/return-value/stack-trace
3. crasher - crash your app in interesting ways to see how they get handled

Stoic is built on public APIs so it will continue to work far into the future.
The primary technologies powering Stoic are
[JVMTI](https://en.wikipedia.org/wiki/Java_Virtual_Machine_Tools_Interface),
Unix Domain Sockets, `socat`, `rsync`, and `run-as`.


# Architecture

The first time you run Stoic on a process it will attach a jvmti agent which
will start a server running inside the process. We connect to this server
through a unix domain socket (via `run-as pkg socat ...` for permissions reasons).
Each socket connection corresponds to a unique plugin. We multiplex
stdin/stdout/stderr over this connection. See
https://github.com/square/stoic/blob/main/docs/ARCHITECTURE.md for more details.
