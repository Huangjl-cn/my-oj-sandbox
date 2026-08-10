FROM maven:3.9.16-eclipse-temurin-25-alpine@sha256:af1e0b9de1a3617dc13eaff61b7ff92118c0051855eac223d8d3d9acb9848d4f AS gson-dependency

ARG GSON_VERSION=2.14.0

RUN mvn -q dependency:get \
        -Dartifact=com.google.code.gson:gson:${GSON_VERSION} && \
    mkdir -p /deps && \
    cp /root/.m2/repository/com/google/code/gson/gson/${GSON_VERSION}/gson-${GSON_VERSION}.jar \
        /deps/gson.jar

FROM eclipse-temurin:25.0.3_9-jdk-alpine-3.23@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151

ARG GSON_VERSION=2.14.0

COPY --from=gson-dependency /deps/gson.jar /opt/libs/gson.jar

LABEL org.opencontainers.image.title="Eclipse Temurin with Gson" \
      org.opencontainers.image.description="Java 25 sandbox image with Gson available at /opt/libs/gson.jar" \
      org.opencontainers.image.version="25-jdk-alpine-gson-${GSON_VERSION}"
