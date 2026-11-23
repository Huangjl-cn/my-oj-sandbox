FROM alpine:3.22.2@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412 AS nlohmann-json-dependency

ARG NLOHMANN_JSON_VERSION=3.12.0
ARG NLOHMANN_JSON_SHA256=aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63

RUN apk add --no-cache curl && \
    mkdir -p /deps/nlohmann && \
    curl -fsSL \
        "https://github.com/nlohmann/json/releases/download/v${NLOHMANN_JSON_VERSION}/json.hpp" \
        -o /deps/nlohmann/json.hpp && \
    echo "${NLOHMANN_JSON_SHA256}  /deps/nlohmann/json.hpp" | sha256sum -c -

FROM gcc:15.3.0-trixie@sha256:486d5379b2d679f18bca957901d628952aa0ebc2d82817047bbef1dbfbc13d70

ARG NLOHMANN_JSON_VERSION=3.12.0

COPY --from=nlohmann-json-dependency /deps/nlohmann /usr/local/include/nlohmann

LABEL org.opencontainers.image.title="GCC with nlohmann/json" \
      org.opencontainers.image.description="GCC 15 sandbox image with nlohmann/json headers" \
      org.opencontainers.image.version="15-nlohmann-json-${NLOHMANN_JSON_VERSION}"
