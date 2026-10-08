#!/bin/bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: distribution/macos/install.sh [destination.app]

Install target/macos/Digital.app into /Applications/Digital.app by default.
For a user-only installation: distribution/macos/install.sh ~/Applications/Digital.app
Build the application with build.sh first. Quit Digital before replacing it.
EOF
}

case "${1:-}" in
    -h|--help) usage; exit 0 ;;
esac
if [[ $# -gt 1 ]]; then
    usage >&2
    exit 2
fi
if [[ "$(uname -s)" != Darwin ]]; then
    printf '%s\n' 'Digital.app can only be installed on macOS.' >&2
    exit 1
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
source_app="$project_dir/target/macos/Digital.app"
destination=${1:-/Applications/Digital.app}
case "$destination" in
    /*.app) ;;
    *) printf '%s\n' 'The destination must be an absolute .app path.' >&2; exit 2 ;;
esac
if [[ ! -x "$source_app/Contents/MacOS/Digital" ]]; then
    printf '%s\n' 'Digital.app was not found. Run distribution/macos/build.sh first.' >&2
    exit 1
fi
codesign --verify --deep --strict "$source_app"
mkdir -p "$(dirname "$destination")"
destination="$(cd "$(dirname "$destination")" && pwd)/$(basename "$destination")"
if [[ "$destination" == "$source_app" ]]; then
    printf '%s\n' 'Choose an installation path outside target/macos.' >&2
    exit 2
fi
if [[ -e "$destination" ]]; then
    bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$destination/Contents/Info.plist" 2>/dev/null || true)
    if [[ "$bundle_id" != com.amirtlinov.digital ]]; then
        printf 'Refusing to replace another application at %s. Choose a different destination.\n' "$destination" >&2
        exit 1
    fi
    if pgrep -x Digital >/dev/null; then
        printf '%s\n' 'Quit Digital before installing an update.' >&2
        exit 1
    fi
fi

work_dir=$(mktemp -d "$(dirname "$destination")/.digital-install.XXXXXX")
trap '/usr/bin/find "$work_dir" -depth -delete' EXIT
ditto "$source_app" "$work_dir/Digital.app"
codesign --verify --deep --strict "$work_dir/Digital.app"
if [[ -e "$destination" ]]; then
    /usr/bin/find "$destination" -depth -delete
fi
mv "$work_dir/Digital.app" "$destination"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$destination"
printf 'Installed %s\n' "$destination"
