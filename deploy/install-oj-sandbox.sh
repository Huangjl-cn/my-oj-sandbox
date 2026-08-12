#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

SERVICE_NAME="oj-code-sandbox"
SERVICE_USER="oj-sandbox"
SERVICE_GROUP="oj-sandbox"
DOCKER_GROUP="docker"

SERVICE_APPLICATION_DIRECTORY="/opt/oj-code-sandbox"
RELEASES_DIRECTORY="${SERVICE_APPLICATION_DIRECTORY}/releases"
CURRENT_RELEASE_LINK="${SERVICE_APPLICATION_DIRECTORY}/current"
CONFIG_DIRECTORY="/etc/oj-code-sandbox"
ENVIRONMENT_FILE="${CONFIG_DIRECTORY}/oj-code-sandbox.env"
STATE_DIRECTORY="/var/lib/oj-code-sandbox"
LOG_DIRECTORY="/var/log/oj-code-sandbox"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
SERVER_PORT="${SERVER_PORT:-8100}"
JAVA_XMS="${JAVA_XMS:-128m}"
JAVA_XMX="${JAVA_XMX:-512m}"

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
APPLICATION_DIRECTORY="${APPLICATION_DIRECTORY:-${SCRIPT_DIRECTORY}}"
TEMP_DIRECTORY=""
RELEASE_STAGING_DIRECTORY=""

log() {
    printf '[oj-sandbox] %s\n' "$*"
}

fail() {
    printf '[oj-sandbox] ERROR: %s\n' "$*" >&2
    exit 1
}

cleanup() {
    if [[ -n "${TEMP_DIRECTORY}" && -d "${TEMP_DIRECTORY}" ]]; then
        rm -rf -- "${TEMP_DIRECTORY}"
    fi
    if [[ -n "${RELEASE_STAGING_DIRECTORY}" && -d "${RELEASE_STAGING_DIRECTORY}" ]]; then
        rm -rf -- "${RELEASE_STAGING_DIRECTORY}"
    fi
}

trap cleanup EXIT

usage() {
    cat <<'EOF'
Usage:
  sudo bash install.sh
  sudo bash /absolute/path/to/oj-sandbox/install.sh

The script is offline. The directory containing install.sh must contain:
  exactly one non-empty .jar file
  runtime/java8/bin/java
  SHA256SUMS

Optional environment variables:
  SERVER_ADDRESS  Spring Boot bind address (default: 0.0.0.0)
  SERVER_PORT     Spring Boot port (default: 8100)
  JAVA_XMS        JVM initial heap (default: 128m)
  JAVA_XMX        JVM maximum heap (default: 512m)
EOF
}

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        fail "Run this script as root, for example: sudo bash $0"
    fi
}

require_command() {
    local command_name="$1"
    command -v "${command_name}" >/dev/null 2>&1 || fail "Required command is unavailable: ${command_name}"
}

verify_server_platform() {
    [[ "$(uname -s)" == "Linux" ]] || fail "This deployment bundle requires Linux"
    case "$(uname -m)" in
        x86_64|amd64)
            ;;
        *)
            fail "This deployment bundle requires x86_64; current architecture is $(uname -m)"
            ;;
    esac
}

resolve_application_bundle() {
    local jars=()

    APPLICATION_DIRECTORY="$(cd -- "${APPLICATION_DIRECTORY}" && pwd -P)"
    [[ -f "${APPLICATION_DIRECTORY}/install.sh" ]] || fail "install.sh is not in ${APPLICATION_DIRECTORY}"
    [[ -f "${APPLICATION_DIRECTORY}/SHA256SUMS" ]] || fail "SHA256SUMS is not in ${APPLICATION_DIRECTORY}"
    [[ -x "${APPLICATION_DIRECTORY}/runtime/java8/bin/java" ]] \
        || fail "Bundled Java 8 runtime is missing: ${APPLICATION_DIRECTORY}/runtime/java8/bin/java"

    shopt -s nullglob
    jars=("${APPLICATION_DIRECTORY}"/*.jar)
    shopt -u nullglob
    [[ "${#jars[@]}" -eq 1 ]] \
        || fail "${APPLICATION_DIRECTORY} must contain exactly one .jar file; found ${#jars[@]}"

    APPLICATION_JAR="${jars[0]}"
    [[ -s "${APPLICATION_JAR}" ]] || fail "Application JAR is empty: ${APPLICATION_JAR}"

    log "Verifying offline deployment bundle"
    (cd -- "${APPLICATION_DIRECTORY}" && sha256sum --check --strict SHA256SUMS) \
        || fail "SHA-256 verification failed for the deployment bundle"
    log "Using application JAR: ${APPLICATION_JAR}"
}

verify_java_runtime() {
    local java_version_output

    java_version_output="$(${APPLICATION_DIRECTORY}/runtime/java8/bin/java -version 2>&1)"
    grep -q '1\.8\.0_502' <<<"${java_version_output}" \
        || fail "The bundled Java runtime is not Temurin 1.8.0_502"
    log "Verified bundled Java: $(sed -n '1p' <<<"${java_version_output}")"
}

prepare_docker() {
    require_command systemctl
    require_command docker
    getent group "${DOCKER_GROUP}" >/dev/null 2>&1 \
        || fail "Docker group does not exist; install Docker Engine first"

    log "Enabling Docker Engine"
    systemctl enable --now docker.service
    [[ -S /var/run/docker.sock ]] || fail "Docker socket is unavailable: /var/run/docker.sock"
}

prepare_service_user() {
    local nologin_shell

    if ! getent group "${SERVICE_GROUP}" >/dev/null 2>&1; then
        groupadd --system "${SERVICE_GROUP}"
    fi

    if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
        if [[ -x /usr/sbin/nologin ]]; then
            nologin_shell=/usr/sbin/nologin
        else
            nologin_shell=/sbin/nologin
        fi
        useradd --system --gid "${SERVICE_GROUP}" --home-dir "${STATE_DIRECTORY}" \
            --no-create-home --shell "${nologin_shell}" "${SERVICE_USER}"
    fi
    usermod -aG "${DOCKER_GROUP}" "${SERVICE_USER}"

    install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${STATE_DIRECTORY}"
    install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${STATE_DIRECTORY}/tmpCode"
    install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${LOG_DIRECTORY}"
    install -d -m 0750 -o root -g "${SERVICE_GROUP}" "${CONFIG_DIRECTORY}"
    install -d -m 0755 -o root -g root "${SERVICE_APPLICATION_DIRECTORY}"
    install -d -m 0755 -o root -g root "${RELEASES_DIRECTORY}"
}

stop_existing_service() {
    if systemctl is-active --quiet "${SERVICE_NAME}.service"; then
        log "Stopping existing ${SERVICE_NAME}.service before updating the release"
        systemctl stop "${SERVICE_NAME}.service"
    fi
}

install_release() {
    local release_id
    local release_directory
    local java_directory
    local release_jar

    release_id="$(sha256sum "${APPLICATION_JAR}" | awk '{print substr($1, 1, 16)}')-$(date -u +%Y%m%d%H%M%S)"
    release_directory="${RELEASES_DIRECTORY}/${release_id}"
    RELEASE_STAGING_DIRECTORY="$(mktemp -d "${RELEASES_DIRECTORY}/.staging.XXXXXX")"

    install -d -m 0750 -o root -g "${SERVICE_GROUP}" "${RELEASE_STAGING_DIRECTORY}"
    install -d -m 0750 -o root -g "${SERVICE_GROUP}" "${RELEASE_STAGING_DIRECTORY}/runtime"
    install -d -m 0750 -o root -g "${SERVICE_GROUP}" "${RELEASE_STAGING_DIRECTORY}/runtime/java8"
    install -m 0640 -o root -g "${SERVICE_GROUP}" "${APPLICATION_JAR}" \
        "${RELEASE_STAGING_DIRECTORY}/oj-code-sandbox.jar"
    cp -a "${APPLICATION_DIRECTORY}/runtime/java8/." "${RELEASE_STAGING_DIRECTORY}/runtime/java8/"
    chown -R root:"${SERVICE_GROUP}" "${RELEASE_STAGING_DIRECTORY}/runtime/java8"

    mv -- "${RELEASE_STAGING_DIRECTORY}" "${release_directory}"
    RELEASE_STAGING_DIRECTORY=""

    if [[ -e "${CURRENT_RELEASE_LINK}" && ! -L "${CURRENT_RELEASE_LINK}" ]]; then
        fail "Current release path exists but is not a symlink: ${CURRENT_RELEASE_LINK}"
    fi
    ln -sfn "${release_directory}" "${CURRENT_RELEASE_LINK}"

    java_directory="${CURRENT_RELEASE_LINK}/runtime/java8"
    release_jar="${CURRENT_RELEASE_LINK}/oj-code-sandbox.jar"
    [[ -x "${java_directory}/bin/java" && -r "${release_jar}" ]] \
        || fail "Installed release is incomplete: ${release_directory}"
    log "Installed release: ${release_directory}"
}

write_environment_file() {
    cat >"${ENVIRONMENT_FILE}" <<EOF
JAVA_HOME=${CURRENT_RELEASE_LINK}/runtime/java8
PATH=${CURRENT_RELEASE_LINK}/runtime/java8/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=${STATE_DIRECTORY}
DOCKER_HOST=unix:///var/run/docker.sock
SPRING_PROFILES_ACTIVE=prod
LOGGING_FILE_PATH=${LOG_DIRECTORY}
SERVER_ADDRESS=${SERVER_ADDRESS}
SERVER_PORT=${SERVER_PORT}
JAVA_TOOL_OPTIONS="-Xms${JAVA_XMS} -Xmx${JAVA_XMX} -Dfile.encoding=UTF-8"
EOF
    chown root:"${SERVICE_GROUP}" "${ENVIRONMENT_FILE}"
    chmod 0640 "${ENVIRONMENT_FILE}"
}

write_systemd_unit() {
    local service_type=exec
    local systemd_version

    systemd_version="$(systemctl --version | awk 'NR == 1 { print $2 }')"
    if [[ "${systemd_version}" =~ ^[0-9]+$ ]] && (( systemd_version < 240 )); then
        service_type=simple
    fi

    cat >"${UNIT_FILE}" <<EOF
[Unit]
Description=OJ Code Sandbox
Requires=docker.service
After=docker.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=${service_type}
User=${SERVICE_USER}
Group=${SERVICE_GROUP}
SupplementaryGroups=${DOCKER_GROUP}
WorkingDirectory=${STATE_DIRECTORY}
EnvironmentFile=${ENVIRONMENT_FILE}
ExecStart=${CURRENT_RELEASE_LINK}/runtime/java8/bin/java -jar ${CURRENT_RELEASE_LINK}/oj-code-sandbox.jar
Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}
UMask=0027

NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=read-only
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
LockPersonality=true
RestrictRealtime=true
RestrictSUIDSGID=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
ReadWritePaths=${STATE_DIRECTORY} ${LOG_DIRECTORY}

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "${UNIT_FILE}"
}

warn_about_missing_images() {
    local image
    local missing_images=()
    local required_images=(
        "oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0"
        "oj-code-sandbox/gcc-nlohmann-json:15-3.12.0"
        "golang:1.25-alpine"
        "python:3.14-alpine"
        "node:24-alpine"
    )

    for image in "${required_images[@]}"; do
        if ! docker image inspect "${image}" >/dev/null 2>&1; then
            missing_images+=("${image}")
        fi
    done

    if [[ "${#missing_images[@]}" -gt 0 ]]; then
        printf '[oj-sandbox] WARNING: the following runtime images are not installed:\n' >&2
        printf '  %s\n' "${missing_images[@]}" >&2
        printf '[oj-sandbox] Load them before accepting execution requests.\n' >&2
    fi
}

verify_service_access() {
    if command -v runuser >/dev/null 2>&1; then
        # Ubuntu 26.04 ships the Rust-based coreutils whose /usr/bin/test
        # mishandles -r/-w on unix sockets; use the shell builtin via sh -c.
        runuser -u "${SERVICE_USER}" -- sh -c "test -r \"${CURRENT_RELEASE_LINK}/oj-code-sandbox.jar\"" \
            || fail "Service user cannot read the installed JAR"
        runuser -u "${SERVICE_USER}" -- sh -c "test -x \"${CURRENT_RELEASE_LINK}/runtime/java8/bin/java\"" \
            || fail "Service user cannot execute the bundled Java runtime"
        runuser -u "${SERVICE_USER}" -- sh -c 'test -r /var/run/docker.sock' \
            || fail "Service user cannot read /var/run/docker.sock after joining the Docker group"
        runuser -u "${SERVICE_USER}" -- sh -c 'test -w /var/run/docker.sock' \
            || fail "Service user cannot write /var/run/docker.sock after joining the Docker group"
    fi
}

start_service() {
    log "Installing and starting ${SERVICE_NAME}.service"
    systemctl daemon-reload
    systemctl enable "${SERVICE_NAME}.service"
    systemctl start "${SERVICE_NAME}.service"
}

wait_for_health() {
    local attempt
    local health_host="${SERVER_ADDRESS}"
    local health_url

    if [[ "${health_host}" == "0.0.0.0" || "${health_host}" == "::" ]]; then
        health_host=127.0.0.1
    fi
    health_url="http://${health_host}:${SERVER_PORT}/health"

    for attempt in $(seq 1 30); do
        if [[ "$(curl --fail --silent --max-time 2 "${health_url}" 2>/dev/null || true)" == "ok" ]]; then
            log "Health check passed: ${health_url}"
            return
        fi
        sleep 1
    done

    systemctl status "${SERVICE_NAME}.service" --no-pager || true
    journalctl -u "${SERVICE_NAME}.service" -n 80 --no-pager || true
    fail "Service did not become healthy within 30 seconds"
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi
    [[ "$#" -eq 0 ]] || {
        usage >&2
        exit 2
    }

    require_root
    for command_name in awk chown chmod cp curl date find getent groupadd id install ln mv sha256sum sed seq systemctl uname useradd usermod; do
        require_command "${command_name}"
    done
    require_command docker

    verify_server_platform
    resolve_application_bundle
    verify_java_runtime
    prepare_docker
    prepare_service_user
    install_release
    write_environment_file
    write_systemd_unit
    verify_service_access
    warn_about_missing_images
    stop_existing_service
    start_service
    wait_for_health

    log "Installation completed"
    log "Java home: ${CURRENT_RELEASE_LINK}/runtime/java8"
    log "Application release: ${CURRENT_RELEASE_LINK}"
    log "Environment: ${ENVIRONMENT_FILE}"
    log "Logs: ${LOG_DIRECTORY} and journalctl -u ${SERVICE_NAME}"
}

main "$@"
