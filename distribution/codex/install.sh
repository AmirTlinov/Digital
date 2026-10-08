#!/bin/bash
set -euo pipefail

case "${1:-}" in
    '') ;;
    -h|--help)
        printf '%s\n' 'Usage: distribution/codex/install.sh' 'Installs the built digital@digital-local plugin using the Codex CLI.' 'Build with distribution/codex/build.sh first. CODEX_CMD selects the Codex executable.'
        exit 0 ;;
    *) printf '%s\n' 'Usage: distribution/codex/install.sh' >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
    printf '%s\n' 'Usage: distribution/codex/install.sh' >&2
    exit 2
fi
script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
if [[ ! -x "$project_dir/target/codex/digital/runtime/node" || ! -f "$project_dir/target/codex/digital/runtime/server.mjs" ]]; then
    printf '%s\n' 'Run distribution/codex/build.sh before installing the plugin.' >&2
    exit 1
fi
codex_cmd=${CODEX_CMD:-codex}
if ! command -v "$codex_cmd" >/dev/null 2>&1; then
    printf '%s\n' 'Codex CLI was not found. Set CODEX_CMD to its executable.' >&2
    exit 1
fi
"$codex_cmd" plugin marketplace add "$project_dir"
"$codex_cmd" plugin add digital@digital-local --json
printf '%s\n' 'Digital plugin installed. Start a new Codex session to load its tools and skill.'
