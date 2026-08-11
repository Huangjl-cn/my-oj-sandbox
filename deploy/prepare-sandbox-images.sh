#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DOCKERFILE_DIRECTORY="${SCRIPT_DIRECTORY}/docker"

JAVA_IMAGE="oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0"
CPP_IMAGE="oj-code-sandbox/gcc-nlohmann-json:15-3.12.0"

GO_SOURCE="golang:1.25.12-alpine3.24@sha256:56961d79ea8129efddcc0b8643fd8a5416b4e6228cfd477e3fd61deb2672c587"
GO_DIGEST_REFERENCE="golang@sha256:56961d79ea8129efddcc0b8643fd8a5416b4e6228cfd477e3fd61deb2672c587"
GO_IMAGE="golang:1.25-alpine"

PYTHON_SOURCE="python:3.14.6-alpine3.24@sha256:26730869004e2b9c4b9ad09cab8625e81d256d1ce97e72df5520e806b1709f92"
PYTHON_DIGEST_REFERENCE="python@sha256:26730869004e2b9c4b9ad09cab8625e81d256d1ce97e72df5520e806b1709f92"
PYTHON_IMAGE="python:3.14-alpine"

NODE_SOURCE="node:24.19.0-alpine3.24@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43"
NODE_DIGEST_REFERENCE="node@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43"
NODE_IMAGE="node:24-alpine"

log() {
    printf '[oj-sandbox-images] %s\n' "$*"
}

fail() {
    printf '[oj-sandbox-images] ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

verify_platform() {
    [[ "$(uname -s)" == "Linux" ]] || fail "Sandbox images must be prepared on Linux"
    case "$(uname -m)" in
        x86_64|amd64)
            ;;
        *)
            fail "Sandbox images require x86_64; current architecture is $(uname -m)"
            ;;
    esac
}

pull_and_tag() {
    local source_image="$1"
    local digest_reference="$2"
    local target_image="$3"

    log "Pulling ${source_image}"
    docker pull "${source_image}"
    docker image tag "${digest_reference}" "${target_image}"
}

main() {
    local required_images=(
        "${JAVA_IMAGE}"
        "${CPP_IMAGE}"
        "${GO_IMAGE}"
        "${PYTHON_IMAGE}"
        "${NODE_IMAGE}"
    )
    local missing_images=()

    require_command docker
    require_command uname
    verify_platform
    docker info >/dev/null 2>&1 \
        || fail "Cannot access Docker Engine; run with sudo or grant access to the Docker socket"

    local image
    for image in "${required_images[@]}"; do
        if ! docker image inspect "${image}" >/dev/null 2>&1; then
            missing_images+=("${image}")
        fi
    done

    if [[ "${#missing_images[@]}" -eq 0 ]]; then
        log "All required sandbox images already exist; skipping builds and downloads"
        printf '  %s\n' "${required_images[@]}"
        exit 0
    fi

    printf '[oj-sandbox-images] Missing images:\n' >&2
    printf '  %s\n' "${missing_images[@]}" >&2

    if docker image inspect "${JAVA_IMAGE}" >/dev/null 2>&1; then
        :
    else
        [[ -f "${DOCKERFILE_DIRECTORY}/java-gson/Dockerfile" ]] \
            || fail "Java Dockerfile is missing from ${DOCKERFILE_DIRECTORY}/java-gson"
        log "Building ${JAVA_IMAGE}"
        docker build --pull --tag "${JAVA_IMAGE}" "${DOCKERFILE_DIRECTORY}/java-gson"
    fi

    if docker image inspect "${CPP_IMAGE}" >/dev/null 2>&1; then
        :
    else
        [[ -f "${DOCKERFILE_DIRECTORY}/cpp-nlohmann-json/Dockerfile" ]] \
            || fail "C++ Dockerfile is missing from ${DOCKERFILE_DIRECTORY}/cpp-nlohmann-json"
        log "Building ${CPP_IMAGE}"
        docker build --pull --tag "${CPP_IMAGE}" "${DOCKERFILE_DIRECTORY}/cpp-nlohmann-json"
    fi

    if docker image inspect "${GO_IMAGE}" >/dev/null 2>&1; then
        :
    else
        pull_and_tag "${GO_SOURCE}" "${GO_DIGEST_REFERENCE}" "${GO_IMAGE}"
    fi
    if docker image inspect "${PYTHON_IMAGE}" >/dev/null 2>&1; then
        :
    else
        pull_and_tag "${PYTHON_SOURCE}" "${PYTHON_DIGEST_REFERENCE}" "${PYTHON_IMAGE}"
    fi
    if docker image inspect "${NODE_IMAGE}" >/dev/null 2>&1; then
        :
    else
        pull_and_tag "${NODE_SOURCE}" "${NODE_DIGEST_REFERENCE}" "${NODE_IMAGE}"
    fi

    docker image inspect "${required_images[@]}" >/dev/null
    log "All required sandbox images are ready"
    printf '  %s\n' "${required_images[@]}"
}

main "$@"
