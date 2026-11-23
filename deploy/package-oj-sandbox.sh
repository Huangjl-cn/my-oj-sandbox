#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd -P)"
EXPANDED_BUNDLE_DIRECTORY="${SCRIPT_DIRECTORY}/oj-sandbox"
JAVA_RELEASE="8u502-b07"
JAVA_ARCHIVE_NAME="OpenJDK8U-jre_x64_linux_hotspot_8u502b07.tar.gz"
JAVA_ARCHIVE_SHA256="f1a7bea0804bfa5627dac412fe7a0d751c4228592e356d6a32a30da54a48ed7a"
JAVA_ARCHIVE_URL="https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u502-b07/${JAVA_ARCHIVE_NAME}"
STAGING_DIRECTORY=""
DOWNLOAD_DIRECTORY=""

log() {
    printf '[oj-sandbox-package] %s\n' "$*"
}

fail() {
    printf '[oj-sandbox-package] ERROR: %s\n' "$*" >&2
    exit 1
}

cleanup() {
    if [[ -n "${STAGING_DIRECTORY}" && -d "${STAGING_DIRECTORY}" ]]; then
        rm -rf -- "${STAGING_DIRECTORY}"
    fi
    if [[ -n "${DOWNLOAD_DIRECTORY}" && -d "${DOWNLOAD_DIRECTORY}" ]]; then
        rm -rf -- "${DOWNLOAD_DIRECTORY}"
    fi
}

trap cleanup EXIT

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

verify_build_platform() {
    [[ "$(uname -s)" == "Linux" ]] || fail "The offline bundle can only be built on Linux"
    case "$(uname -m)" in
        x86_64|amd64)
            ;;
        *)
            fail "The offline bundle targets linux-x64; current architecture is $(uname -m)"
            ;;
    esac
}

download_java_archive() {
    if [[ -n "${TEMURIN_JRE_ARCHIVE:-}" ]]; then
        JAVA_ARCHIVE_PATH="$(cd -- "$(dirname -- "${TEMURIN_JRE_ARCHIVE}")" && pwd -P)/$(basename -- "${TEMURIN_JRE_ARCHIVE}")"
    else
        DOWNLOAD_DIRECTORY="$(mktemp -d)"
        JAVA_ARCHIVE_PATH="${DOWNLOAD_DIRECTORY}/${JAVA_ARCHIVE_NAME}"
    fi

    if [[ -s "${JAVA_ARCHIVE_PATH}" ]]; then
        log "Using existing Temurin archive: ${JAVA_ARCHIVE_PATH}"
        return
    fi

    if [[ -n "${TEMURIN_JRE_ARCHIVE:-}" ]]; then
        fail "TEMURIN_JRE_ARCHIVE does not point to a non-empty file: ${JAVA_ARCHIVE_PATH}"
    fi
    log "Downloading Eclipse Temurin ${JAVA_RELEASE} JRE for linux-x64"
    curl --fail --location --retry 3 --connect-timeout 15 \
        --output "${JAVA_ARCHIVE_PATH}" "${JAVA_ARCHIVE_URL}"
}

verify_java_archive() {
    printf '%s  %s\n' "${JAVA_ARCHIVE_SHA256}" "${JAVA_ARCHIVE_PATH}" | sha256sum --check --status \
        || fail "Temurin archive SHA-256 verification failed"
}

resolve_jar() {
    local jars=()

    while IFS= read -r -d '' jar; do
        jars+=("${jar}")
    done < <(find "${REPOSITORY_DIRECTORY}/target" -maxdepth 1 -type f \
        -name '*.jar' ! -name '*.original' -print0)
    [[ "${#jars[@]}" -eq 1 ]] \
        || fail "target must contain exactly one executable JAR; found ${#jars[@]}"
    APPLICATION_JAR="${jars[0]}"
    [[ -s "${APPLICATION_JAR}" ]] || fail "Application JAR is empty: ${APPLICATION_JAR}"
}

main() {
    for command_name in cp curl find install rm sha256sum sort tar uname xargs; do
        require_command "${command_name}"
    done
    require_command mvn
    verify_build_platform

    log "Building executable JAR"
    (cd -- "${REPOSITORY_DIRECTORY}" && mvn clean package)
    resolve_jar
    download_java_archive
    verify_java_archive

    STAGING_DIRECTORY="$(mktemp -d)"
    BUNDLE_DIRECTORY="${STAGING_DIRECTORY}/oj-sandbox"
    install -d -m 0755 "${BUNDLE_DIRECTORY}/runtime/java8"
    install -d -m 0755 "${BUNDLE_DIRECTORY}/docker/java-gson"
    install -d -m 0755 "${BUNDLE_DIRECTORY}/docker/cpp-nlohmann-json"
    install -m 0644 "${APPLICATION_JAR}" "${BUNDLE_DIRECTORY}/$(basename -- "${APPLICATION_JAR}")"
    install -m 0755 "${SCRIPT_DIRECTORY}/install-oj-sandbox.sh" "${BUNDLE_DIRECTORY}/install.sh"
    install -m 0755 "${SCRIPT_DIRECTORY}/prepare-sandbox-images.sh" \
        "${BUNDLE_DIRECTORY}/prepare-sandbox-images.sh"
    install -m 0644 "${SCRIPT_DIRECTORY}/docker/java-gson/Dockerfile" \
        "${BUNDLE_DIRECTORY}/docker/java-gson/Dockerfile"
    install -m 0644 "${SCRIPT_DIRECTORY}/docker/cpp-nlohmann-json/Dockerfile" \
        "${BUNDLE_DIRECTORY}/docker/cpp-nlohmann-json/Dockerfile"
    tar -xzf "${JAVA_ARCHIVE_PATH}" -C "${BUNDLE_DIRECTORY}/runtime/java8" --strip-components=1
    [[ -x "${BUNDLE_DIRECTORY}/runtime/java8/bin/java" ]] \
        || fail "Temurin archive does not contain bin/java"

    (cd -- "${BUNDLE_DIRECTORY}" && \
        find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
    rm -rf -- "${EXPANDED_BUNDLE_DIRECTORY}"
    cp -a "${BUNDLE_DIRECTORY}" "${EXPANDED_BUNDLE_DIRECTORY}"
    log "Created ${EXPANDED_BUNDLE_DIRECTORY}"
}

main "$@"
