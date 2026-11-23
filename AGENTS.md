# AGENTS.md

本文件是 AI 编码助手在本仓库工作的说明，也是项目开发约定的唯一来源（CLAUDE.md 只引用本文件）。面向人的介绍和快速开始见 README.md。

## 项目概述

多语言代码执行沙箱，为 [my-oj-backend](https://github.com/Huangjl-cn/my-oj-backend) 提供隔离的编译和执行能力。Java 8 + Spring Boot 2.7 + Maven，通过 docker-java 调用本机 Docker。接口约定（请求、响应、状态码、单位、401 格式）见 my-oj-backend 的 `docs/sandbox-contract.md`，改接口时两个仓库同时修改。

沙箱只负责编译、执行和收集原始输出：不理解 OJ 的值类型，不接收预期答案，不做判题。

## 目录结构

- `src/main/java/com/hjl/ojcodesandbox/`：
  - `controller/`：HTTP 接口（`/health`、`/executeCode`）；
  - `auth/`：ECDSA 请求签名验签过滤器 `SandboxAuthFilter`；
  - `model/`：请求、响应和枚举；
  - `sandbox/dispatch`：分发器和语言注册表；`sandbox/docker`：执行引擎；`sandbox/language`：各语言实现；`sandbox/result`：结果模型、诊断清洗、响应工厂；
  - 根包的 `CodeSandbox` 是沙箱契约。
- `deploy/`：打包、镜像准备和安装脚本，`deploy/docker/` 是 Java、C++ 镜像的 Dockerfile。
- `docs/`：现行手册；`docs/design/` 是设计记录，写完后不再随代码更新。
- `tmpCode/`：运行时临时目录，已 gitignore，不要往里放文件。

## 常用命令

```bash
bash deploy/prepare-sandbox-images.sh   # 准备五种语言镜像（首次），已存在的跳过
mvn spring-boot:run                     # 本地运行：pom 自动激活 dev profile，127.0.0.1:8100
mvn test                                # 单元测试，不需要 Docker
RUN_DOCKER_TESTS=true mvn test -Dtest=DockerSandboxIntegrationTest   # Docker 集成测试，需要 Docker 和五个镜像
mvn clean package                       # 构建 jar
bash deploy/package-oj-sandbox.sh       # 生成离线部署包 deploy/oj-sandbox/（Linux x86_64）
```

- 需要 JDK 8（不能用 JDK 21 及以上，pom 固定的 Lombok 1.18.28 不支持）、Maven 3.6+、Linux 或 WSL2 上的 Docker。
- IDE 里运行时加 VM 参数 `-Dspring.profiles.active=dev`；`application.yml` 不设默认 profile。
- `DOCKER_HOST` 只能指向与本进程共享文件系统的 daemon：代码目录是从本机挂载进容器的。

## 配置

- `application-dev.yml`：内置**开发专用**公钥（key-id `sandbox-key-dev-2026`，对应私钥已公开，与 my-oj-backend 的 dev 私钥配对），默认只监听 127.0.0.1（`SERVER_ADDRESS` 可覆盖）。
- `application-prod.yml`：生产公钥（key-id `sandbox-key-prod-2026`）。公钥不是秘密，可以入库；轮换需要重新打包部署。
- 服务器上由 systemd 环境文件设置 `SPRING_PROFILES_ACTIVE=prod`（见 `deploy/install-oj-sandbox.sh`）。
- 资源限制（内存、CPU、PID、编译和运行超时、输出大小）是 `DockerExecutionEngine` 中的常量，全语言统一。后端的调用超时必须大于“编译超时 + 用例数 × 运行超时”。
- 镜像名称和版本写在多处，修改时按 `docs/json-dependency-images.md` 的“更新规则”同步。

## 测试

- JUnit 5（`spring-boot-starter-test`）。测试类命名 `*Test`，方法名描述被测行为，例如 `passesDiagnosticTimeAndMemoryThrough`。
- 加载 Spring 上下文的测试加 `@ActiveProfiles("dev")`。
- 依赖 Docker 的测试必须用 `@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")` 控制，保证 `mvn test` 在没有 Docker 的机器上通过。
- 改动进程执行、挂载、网络、资源限制或鉴权时，补充回归测试。
- `SandboxAuthFilterTest` 使用固定的开发密钥对，用来同时验证两个仓库的开发配置配对，不要换成临时生成的密钥。

## 编码约定

- 四空格缩进，UTF-8，左花括号不换行；包名小写，类名 PascalCase，方法和字段 camelCase，常量 UPPER_SNAKE_CASE。
- Lombok 用于日志和模型样板代码。没有配置格式化工具，保持与周围代码一致，不做无关的格式调整。
- 协议常量（签名请求头名、`/health`）和判题相关的执行细节留在代码里；与机器有关的值（端口、监听地址、密钥）放在 profile 配置或环境变量中。

## 安全

- 仓库里只允许出现开发专用的凭据，并注释标明；沙箱本身只持有公钥。
- 不提交机器相关的 Docker 地址；不通过未加密的 TCP 端口暴露 Docker daemon。
- 进程执行、挂载、网络和资源限制的改动都属于安全敏感改动，需要说明影响并补测试。

## 提交与合并请求

- 提交信息用中文约定式提交：`<类型>(<范围>): <说明>`，类型如 feat、fix、docs、test、refactor、build、chore。
- 合并请求说明行为变化和验证命令；接口有变化时附请求和响应示例，并同步更新 my-oj-backend 的 `docs/sandbox-contract.md`；写明对 Docker 镜像或配置的影响。
