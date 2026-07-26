#!/bin/sh
# Launcher for pijota's ACP frontend, for use as a Zed / JetBrains custom agent command.
# Runs the freshly-compiled classes so you don't need a native rebuild after each change.
# IMPORTANT: only JSON-RPC may reach stdout — pijota logs to stderr, so this stays clean.
PIJOTA_HOME="$(cd "$(dirname "$0")" && pwd)"
# To debug the protocol, uncomment to record every JSON-RPC frame (<< in, >> out):
# export PIJOTA_ACP_WIRELOG=/tmp/pijota-acp-wire.log
exec /usr/bin/java -cp "$PIJOTA_HOME/target/classes:$(cat "$PIJOTA_HOME/target/classpath.txt")" org.pijota.Main --acp "$@"
