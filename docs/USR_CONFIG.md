# Stoic config
Stoic configuration lives in `~/.config/stoic`. This directory will be
automatically created when you run `stoic tool setup`.

You can add stuff to `~/.config/stoic/sync` and it will be automatically synced (via
rsync) to any device you connect to with stoic, in the directory identified by
the `$STOIC_DEVICE_SYNC_DIR` env var.

You can add `bash`/`zsh`/`vim`/`screen`/etc to `~/.config/stoic/sync/bin` and
they will be automatically available on any device you `stoic tool shell` into.
If you sync `bash`/`zsh` you'll probably want to modify the command used to
start a new interactive shell (see `~/.config/stoic/shell.sh`) to use that
instead. And you'll want to sync `.zshrc`/`.bashrc` to customize your
experience.

You can add any prebuilt plugin jars to `~/.config/stoic/sync/plugins`. e.g. If
you have plugin called `plugh` then it goes in
`~/.config/stoic/sync/plugins/plugh.dex.jar`.
