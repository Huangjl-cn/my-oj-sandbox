# Repository Guidelines

## Project Structure & Module Organization

This is a Java 8 Spring Boot 2.7 Maven service. Production code lives under `src/main/java/com/hjl/ojcodesandbox`. Keep HTTP endpoints in `controller/`, request and response objects in `model/`, and sandbox code grouped by responsibility: `sandbox/dispatch`, `sandbox/docker`, `sandbox/language`, and `sandbox/result`. The `CodeSandbox` contract remains in the root package. Configuration and sample programs are under `src/main/resources`; `testCode/` contains execution fixtures, not JUnit tests. JUnit tests belong beneath `src/test/java`. `tmpCode/` is runtime scratch space; do not add generated files there.

## Build, Test, and Development Commands

- `mvn clean test` compiles the project and runs the JUnit 5 test suite.
- `mvn clean package` builds the executable JAR in `target/` and runs tests.
- `mvn spring-boot:run` starts the API locally on `127.0.0.1:8100` with the `dev` profile (activated by the Maven plugin; `application.yml` sets no default profile, so IDE runs need `-Dspring.profiles.active=dev`).
- `docker pull <image>` preloads a required sandbox image. Language implementations declare versioned Java, GCC, Go, Python, and Node.js images under `sandbox/language/`.

Use JDK 8 and a reachable Docker daemon for sandbox execution. Set `DOCKER_HOST` when the default local Docker connection is unsuitable.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8 source files, and opening braces on the declaration line. Keep package names lowercase (`com.hjl.ojcodesandbox.sandbox`), classes and enums in PascalCase, and methods and fields in camelCase. Name constants in `UPPER_SNAKE_CASE`. Lombok is used for logging and model boilerplate. No formatter or linter is configured, so match the surrounding file and avoid unrelated formatting changes.

## Testing Guidelines

Tests use JUnit 5 through `spring-boot-starter-test`. Name test classes `*Test` and test methods for the behavior under test, such as `testExecuteCode`. Add focused cases for successful execution, compilation failures, timeouts, and resource limits when changing sandbox behavior. Run `mvn clean test` before submitting. There is currently no enforced coverage threshold.

## Commit & Pull Request Guidelines

History is minimal (`init codesandbox`), so use short, imperative commit subjects such as `handle compile timeout`. Keep each commit scoped to one change. Pull requests should explain behavior changes, list verification commands, link related issues, and include example request/response payloads for API changes. Call out Docker or configuration impacts explicitly.

## Security & Configuration

Never commit API keys, authentication secrets, or machine-specific Docker endpoints. Supply sensitive values through environment-specific configuration and keep generated code and build output out of commits. Treat changes to process execution, mounts, network access, and resource limits as security-sensitive and add regression tests for them.
