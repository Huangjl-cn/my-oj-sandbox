# 沙箱 Docker 镜像准备

本项目固定官方镜像的完整版本和多架构清单 digest，避免同一标签在不同时间指向不同内容。Java 与 C++ 使用本地派生镜像；Go、Python 和 JavaScript 使用官方镜像，并重新标记为代码当前引用的名称。完成下面的准备后，不需要修改应用代码。

## 运行时镜像名称

| 语言 | 代码使用的镜像名称 | 固定来源 |
| --- | --- | --- |
| Java | `oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0` | Temurin `25.0.3_9` + Gson `2.14.0` |
| C++ | `oj-code-sandbox/gcc-nlohmann-json:15-3.12.0` | GCC `15.3.0` + nlohmann/json `3.12.0` |
| Go | `golang:1.25-alpine` | `golang:1.25.12-alpine3.24` |
| Python | `python:3.14-alpine` | `python:3.14.6-alpine3.24` |
| JavaScript | `node:24-alpine` | `node:24.19.0-alpine3.24` |

以下 digest 于 2026-08-10 通过 `docker buildx imagetools inspect` 核对，均为官方镜像的多架构清单 digest。

## 拉取构建基础镜像

Java 和 C++ Dockerfile 已使用相同的完整标签与 digest。`docker build` 会自动拉取缺失镜像；也可以在部署服务器上提前执行：

```bash
docker pull maven:3.9.16-eclipse-temurin-25-alpine@sha256:af1e0b9de1a3617dc13eaff61b7ff92118c0051855eac223d8d3d9acb9848d4f

docker pull eclipse-temurin:25.0.3_9-jdk-alpine-3.23@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151

docker pull alpine:3.22.2@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412

docker pull gcc:15.3.0-trixie@sha256:486d5379b2d679f18bca957901d628952aa0ebc2d82817047bbef1dbfbc13d70
```

## 构建 Java 和 C++ 镜像

在项目根目录执行。输出镜像名称与应用代码当前配置保持一致：

```bash
docker build \
  --tag oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0 \
  docs/docker/java-gson

docker build \
  --tag oj-code-sandbox/gcc-nlohmann-json:15-3.12.0 \
  docs/docker/cpp-nlohmann-json
```

Java 镜像将 Gson 安装到 `/opt/libs/gson.jar`。C++ 镜像将 nlohmann/json 安装到 `/usr/local/include/nlohmann/json.hpp`，并在构建阶段校验 `json.hpp` 的 SHA-256。运行用户代码时不需要联网下载依赖。

## 拉取并注入其他运行时名称

先按 digest 拉取确定的镜像内容，再标记为代码已经使用的名称：

```bash
docker pull golang:1.25.12-alpine3.24@sha256:56961d79ea8129efddcc0b8643fd8a5416b4e6228cfd477e3fd61deb2672c587
docker image tag \
  golang@sha256:56961d79ea8129efddcc0b8643fd8a5416b4e6228cfd477e3fd61deb2672c587 \
  golang:1.25-alpine

docker pull python:3.14.6-alpine3.24@sha256:26730869004e2b9c4b9ad09cab8625e81d256d1ce97e72df5520e806b1709f92
docker image tag \
  python@sha256:26730869004e2b9c4b9ad09cab8625e81d256d1ce97e72df5520e806b1709f92 \
  python:3.14-alpine

docker pull node:24.19.0-alpine3.24@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
docker image tag \
  node@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 \
  node:24-alpine
```

重新标记不会复制镜像层，也不会改变镜像内容，只是让确定的镜像内容能够通过应用现有名称访问。

## 验证

确认应用需要的五个镜像名称都存在：

```bash
docker image inspect \
  oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0 \
  oj-code-sandbox/gcc-nlohmann-json:15-3.12.0 \
  golang:1.25-alpine \
  python:3.14-alpine \
  node:24-alpine
```

执行真实 Docker 集成测试：

```bash
RUN_DOCKER_TESTS=true mvn -Dtest=DockerSandboxIntegrationTest test
```

该测试通过真实 `/executeCode` 请求验证五种语言，并对 Java、C++、Go、Python 和 JavaScript 分别覆盖整数数组、二维整数数组、字符串数组和字符串 JSON 输入输出。

## 更新规则

完整标签仍可能被上游重新发布，digest 才是最终约束。升级任一官方镜像时，应同时更新 Dockerfile、本文中的完整标签和 digest，然后重新构建并运行集成测试。不要把一个版本标签与另一个版本的 digest 混用。

参考资料：

- [Docker 构建最佳实践：固定基础镜像](https://docs.docker.com/build/building/best-practices/#pin-base-image-versions)
- [Docker 镜像 digest](https://docs.docker.com/dhi/core-concepts/digests/)
- [Docker Official Images](https://hub.docker.com/search?image_filter=official)
