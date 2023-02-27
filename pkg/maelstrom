#!/usr/bin/env bash

# A small wrapper script for invoking the Maelstrom jar, with arguments.

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

exec java -Djava.awt.headless=true -jar "${SCRIPT_DIR}/lib/maelstrom.jar" "$@"
