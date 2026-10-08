#!/bin/bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: distribution/macos/build.sh [--skip-build]

Build Digital.jar with Maven and package target/macos/Digital.app.
--skip-build packages an existing target/Digital.jar.

JAVA_HOME selects JDK 21 or newer. MAVEN_CMD selects a Maven executable.
The application version is read from the JAR manifest.
EOF
}

skip_build=false
case "${1:-}" in
    '') ;;
    --skip-build) skip_build=true ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
    usage >&2
    exit 2
fi
if [[ "$(uname -s)" != Darwin ]]; then
    printf '%s\n' 'Digital.app must be built on macOS.' >&2
    exit 1
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
jdk_home=${JAVA_HOME:-}
if [[ -z "$jdk_home" ]]; then
    jdk_home=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
if [[ -z "$jdk_home" ]]; then
    for candidate in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
                     /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
        if [[ -x "$candidate/bin/jpackage" ]]; then
            jdk_home=$candidate
            break
        fi
    done
fi
if [[ ! -x "$jdk_home/bin/java" || ! -x "$jdk_home/bin/jpackage" ]]; then
    printf '%s\n' 'Set JAVA_HOME to a JDK 21 or newer with jpackage.' >&2
    exit 1
fi
jdk_major=$("$jdk_home/bin/java" -XshowSettings:properties -version 2>&1 | awk '$1 == "java.specification.version" { print $3 }')
if [[ ! "$jdk_major" =~ ^[0-9]+$ ]] || [[ "$jdk_major" -lt 21 ]]; then
    printf '%s\n' 'Digital requires JDK 21 or newer.' >&2
    exit 1
fi
export JAVA_HOME="$jdk_home"

if [[ "$skip_build" == false ]]; then
    maven_cmd=${MAVEN_CMD:-mvn}
    if ! command -v "$maven_cmd" >/dev/null 2>&1; then
        printf '%s\n' 'Maven was not found. Set MAVEN_CMD, or build Digital.jar and use --skip-build.' >&2
        exit 1
    fi
    (cd "$project_dir" && "$maven_cmd" -B -DskipTests -Djacoco.skip=true package)
fi
if [[ ! -f "$project_dir/target/Digital.jar" ]]; then
    printf '%s\n' 'target/Digital.jar was not found. Run build.sh without --skip-build first.' >&2
    exit 1
fi
app_version=$(unzip -p "$project_dir/target/Digital.jar" META-INF/MANIFEST.MF | tr -d '\r' | awk '$1 == "Implementation-Version:" { print $2 }')
if [[ -z "$app_version" ]]; then
    printf '%s\n' 'Digital.jar is missing Implementation-Version. Rebuild it with Maven.' >&2
    exit 1
fi

mkdir -p "$project_dir/target/macos"
work_dir=$(mktemp -d "$project_dir/target/macos-build.XXXXXX")
trap '/usr/bin/find "$work_dir" -depth -delete' EXIT
input_dir="$work_dir/input"
mkdir -p "$input_dir/examples"
cp "$project_dir/target/Digital.jar" "$project_dir/LICENSE" "$project_dir/README.md" "$input_dir/"
cp "$script_dir/README.md" "$input_dir/README-macos.md"
ditto "$project_dir/src/main/dig/lib" "$input_dir/lib"
for example_dir in "$project_dir/src/main/dig/"*; do
    if [[ -d "$example_dir" && "$(basename "$example_dir")" != lib ]]; then
        ditto "$example_dir" "$input_dir/examples/$(basename "$example_dir")"
    fi
done
ditto "$project_dir/src/main/fsm" "$input_dir/examples/fsm"

for extension in dig fsm; do
    cp "$script_dir/$extension.properties" "$work_dir/$extension.properties"
    printf '\nicon=%s\n' "$script_dir/Digital.icns" >> "$work_dir/$extension.properties"
done

"$jdk_home/bin/jpackage" \
    --type app-image \
    --name Digital \
    --app-version "$app_version" \
    --vendor 'Helmut Neemann and Digital contributors' \
    --description 'Digital logic simulator with a modern Russian interface' \
    --copyright 'Copyright Helmut Neemann and Digital contributors; GPL-3.0' \
    --input "$input_dir" \
    --dest "$work_dir/image" \
    --main-jar Digital.jar \
    --main-class de.neemann.digital.Main \
    --icon "$script_dir/Digital.icns" \
    --mac-package-identifier com.amirtlinov.digital \
    --mac-package-name Digital \
    --mac-app-category public.app-category.education \
    --file-associations "$work_dir/dig.properties" \
    --file-associations "$work_dir/fsm.properties" \
    --java-options '-Duser.language=ru' \
    --java-options '-Duser.country=RU' \
    --java-options '-Dapple.awt.application.name=Digital' \
    --java-options '-Dapple.laf.useScreenMenuBar=true'

app_path="$work_dir/image/Digital.app"
plutil -insert NSDocumentsFolderUsageDescription -string 'Digital читает соседние схемы в папке Документы, чтобы подключать вложенные компоненты и обновлять библиотеку проекта.' "$app_path/Contents/Info.plist"
codesign --force --deep --sign - "$app_path"
codesign --verify --deep --strict "$app_path"
output_app="$project_dir/target/macos/Digital.app"
if [[ -e "$output_app" ]]; then
    /usr/bin/find "$output_app" -depth -delete
fi
mv "$app_path" "$output_app"
printf 'Built %s\n' "$output_app"
