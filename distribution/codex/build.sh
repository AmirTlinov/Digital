#!/bin/bash
set -euo pipefail

case "${1:-}" in
    '') ;;
    -h|--help)
        printf '%s\n' 'Usage: distribution/codex/build.sh' 'Builds target/codex/digital with a bundled Node runtime and MCP server.' 'NODE_BIN selects the self-contained Node 22+ executable used for the build.'
        exit 0 ;;
    *) printf '%s\n' 'Usage: distribution/codex/build.sh' >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
    printf '%s\n' 'Usage: distribution/codex/build.sh' >&2
    exit 2
fi
script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
node_bin=${NODE_BIN:-$(command -v node || true)}
if [[ ! -x "$node_bin" ]]; then
    printf '%s\n' 'Node 22+ was not found. Set NODE_BIN to a self-contained Node executable.' >&2
    exit 1
fi
node_bin=$("$node_bin" -p 'process.execPath')
npm_cli="$(dirname "$node_bin")/../lib/node_modules/npm/bin/npm-cli.js"
if [[ ! -f "$npm_cli" ]]; then
    printf '%s\n' 'The selected Node distribution must include npm.' >&2
    exit 1
fi
PATH="$(dirname "$node_bin"):$PATH"
export PATH
cd "$project_dir/plugins/digital"
"$node_bin" "$npm_cli" ci --ignore-scripts --no-audit --no-fund
"$node_bin" build.mjs
"$node_bin" verify.mjs
