# OJ Code Sandbox 在线判题沙箱

> 一个多语言代码执行沙箱服务：接收用户代码与测试用例，在 Docker 容器中隔离编译、运行并采集资源消耗，为在线判题系统 [my-oj-backend](https://github.com/Huangjl-cn/my-oj-backend) 提供安全的代码执行能力。

![Java](https://img.shields.io/badge/Java-8-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.14-green) ![Docker](https://img.shields.io/badge/Docker-required-blue) ![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## 功能特性

- **多语言支持**：Java、C++、Go、Python、JavaScript，每种语言声明自己的镜像和编译、运行命令；资源限制全局统一
- **Docker 容器隔离**：编译和运行都在容器内进行，禁用网络、根文件系统只读、运行阶段代码目录只读挂载，并限制内存、CPU 和进程数
- **结构化用例执行**：请求携带 `cases[].args[]`，一次编译、逐个用例运行，每个参数原样传给程序（不经过 shell）
- **统一结果模型**：返回每个用例的标准输出、执行状态、最大耗时和最大内存，失败时附带诊断信息
- **ECDSA 请求签名鉴权**：请求头携带 key-id、时间戳、nonce、签名，防伪造、防重放（±300s 时间窗 + nonce 去重）；沙箱只持有公钥
- **错误诊断清洗**：`DiagnosticSanitizer` 把编译器和运行时的原始输出清洗为精简诊断，零输出时自动兜底
- **可扩展的调度架构**：`CodeSandboxRegistry` + 分发器按语言路由，新增语言只需增加一个 `@Component` 类

## 架构

```mermaid
flowchart LR
    A["oj-backend<br/>ECDSA 签名请求"] --> B["SandboxAuthFilter<br/>验签 / 时间窗 / nonce 去重"]
    B --> C["DockerCodeSandboxDispatcher<br/>按语言路由"]
    C --> D["DockerExecutionEngine<br/>容器生命周期管理"]
    D --> E["编译阶段<br/>javac / g++ / go build / py_compile / node --check"]
    E --> F["运行阶段<br/>逐用例执行，限资源、无网络"]
    F --> G["SandboxResponseFactory<br/>聚合最大耗时 / 内存"]
    G --> H["响应返回<br/>输出 / 状态 / 耗时内存 / 诊断"]
```

- **语言适配**：`LanguageCodeSandbox` 接口 + `AbstractDockerCodeSandbox` 抽象基类 + 各语言 `DockerXxxCodeSandbox`，声明源码文件名、编译和运行命令、挂载卷与环境变量。
- **结果语义**：`ExecuteStatusEnum` 有四个值：1 执行成功、2 编译错误、3 运行错误（超时、输出超限也归在这里）、4 系统错误；`SandboxFailureType` 是内部的错误分类。
- **接口契约**：请求、响应、单位和错误格式见 my-oj-backend 的 [docs/sandbox-contract.md](https://github.com/Huangjl-cn/my-oj-backend/blob/master/docs/sandbox-contract.md)。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 1.8 | 开发语言 |
| Spring Boot | 2.7.14 | Web 框架 |
| docker-java | 3.3.0 | Docker API 客户端 |
| Hutool | 5.8.8 | 工具库 |
| Lombok | 1.18.28 | 样板代码生成 |
| Docker | - | 容器隔离执行 |

## 支持的镜像

| 语言 | 镜像 | 编译 | 运行 |
|------|------|------|------|
| Java | `oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0`（本地构建） | `javac` | `java -cp` |
| C++ | `oj-code-sandbox/gcc-nlohmann-json:15-3.12.0`（本地构建） | `g++ -O2 -std=c++17` | `./main` |
| Go | `golang:1.25-alpine` | `go build` | `./main` |
| Python | `python:3.14-alpine` | `py_compile` | `python` |
| JavaScript | `node:24-alpine` | `node --check` | `node` |

Java 和 C++ 镜像内置 JSON 库（Gson、nlohmann/json），Dockerfile 在 `deploy/docker/`，不在 Docker Hub 上。Go 使用 `oj-gocache` named volume 持久化构建缓存，跨提交复用标准库构建产物。版本固定方式见 [沙箱 Docker 镜像准备](./docs/json-dependency-images.md)。

## 快速开始

**前置条件**：

- Linux，或 Windows 上的 **WSL2**（原生 Windows 连不上 Docker daemon；推荐用 IntelliJ IDEA 的远程开发连接 WSL2）。
- Docker，当前用户能读写 `/var/run/docker.sock`。`DOCKER_HOST` 只能指向与本进程共享文件系统的 daemon（代码目录是从本机挂载进容器的），远程 Docker 不可用。
- **JDK 8**（不能用 JDK 21 及以上：pom 固定的 Lombok 1.18.28 不支持）、Maven 3.6+。

```bash
# 1. 准备五种语言的镜像（首次执行；本地构建 Java、C++ 镜像，按固定 digest 拉取其余三个，已存在的会跳过）
bash deploy/prepare-sandbox-images.sh

# 2. 启动服务（自动激活 dev profile，监听 127.0.0.1:8100）
mvn spring-boot:run
```

- **在 IDE 里运行**：加 VM 参数 `-Dspring.profiles.active=dev`。`application.yml` 不设默认 profile，不指定环境时服务不会启动。
- **开发配置已内置开发专用的签名公钥**，与 my-oj-backend 的 dev 私钥配对，不需要额外配置。这对开发密钥是公开的，所以 dev 默认只监听本机；后端在另一台机器上联调时设 `SERVER_ADDRESS=0.0.0.0`，并确保只在可信网络内访问。

### 调用示例

请求需要签名。下面用 openssl 和**开发专用私钥**（已公开，与 my-oj-backend 的 `application-dev.yml` 相同）在命令行生成签名：

```bash
DEV_PRIVATE_KEY='MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA3g4tm7fJYlnSAbBjD7pU8exOMaXToOSfNkCuMzfmfDQ=='
BODY='{"code":"import sys\nprint(int(sys.argv[1]) + int(sys.argv[2]))","language":"python","cases":[{"args":["1","2"]}]}'

TS=$(date +%s%3N)                                                    # 毫秒时间戳
NONCE=$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=')           # 每个请求不同
HASH=$(printf '%s' "$BODY" | sha256sum | cut -d' ' -f1)
KEY_FILE=$(mktemp)
printf -- '-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n' "$DEV_PRIVATE_KEY" > "$KEY_FILE"
SIGNATURE=$(printf '%s\n%s\n%s' "$TS" "$NONCE" "$HASH" | openssl dgst -sha256 -sign "$KEY_FILE" | base64 -w0)
rm -f "$KEY_FILE"

curl -X POST http://127.0.0.1:8100/executeCode \
  -H "Content-Type: application/json" \
  -H "X-Sandbox-Key-Id: sandbox-key-dev-2026" \
  -H "X-Sandbox-Timestamp: $TS" \
  -H "X-Sandbox-Nonce: $NONCE" \
  -H "X-Sandbox-Signature: $SIGNATURE" \
  --data-binary "$BODY"
```

返回 `"status":1` 和 `"outputList":["3"]` 即成功。签名协议见 my-oj-backend 的 [docs/sandbox-auth/README.md](https://github.com/Huangjl-cn/my-oj-backend/blob/master/docs/sandbox-auth/README.md)。

只想调试执行逻辑时，可以临时关闭鉴权启动，不要改 yml：`mvn spring-boot:run -Dspring-boot.run.arguments=--codesandbox.auth.enabled=false`。

## 测试

```bash
mvn test   # 单元测试，不需要 Docker

# Docker 集成测试默认跳过，需要 Docker 和上面五个镜像：
RUN_DOCKER_TESTS=true mvn test -Dtest=DockerSandboxIntegrationTest
```

## 生产部署

服务以 systemd 服务运行在宿主机上（`SPRING_PROFILES_ACTIVE=prod`），通过本机 Docker 执行用户代码。部署分三步：

```bash
# 1. 在 Linux x86_64 打包机上（WSL2 也可以）生成离线部署包 deploy/oj-sandbox/（含 jar、Java 8 运行时、安装脚本和 Dockerfile）
bash deploy/package-oj-sandbox.sh

# 2. 上传到服务器的 my-oj 目录
scp -r deploy/oj-sandbox <用户>@<服务器>:/absolute/path/my-oj/

# 3. 在服务器上准备镜像并安装
cd /absolute/path/my-oj
sudo bash oj-sandbox/prepare-sandbox-images.sh
sudo bash oj-sandbox/install.sh
```

运行账户 `oj-sandbox` 只加入 `docker` 组，应用目录 `/opt/oj-code-sandbox`，环境文件在 `/etc/oj-code-sandbox/`，日志在 `/var/log/oj-code-sandbox/`。安装参数、更新回滚和安全说明见 [服务端部署手册](./docs/server-deployment.md)。

## 项目结构

```
src/main/java/com/hjl/ojcodesandbox/
├── controller/          # HTTP 接口层（/health、/executeCode）
├── CodeSandbox.java     # 沙箱契约（根包）
├── auth/                # ECDSA 请求签名验签过滤器
├── sandbox/
│   ├── dispatch/        # 分发器 + 语言注册表（CodeSandboxRegistry）
│   ├── docker/          # Docker 执行引擎、命令结果、限量输出流
│   ├── language/        # 各语言沙箱实现（Java/C++/Go/Python/JS）
│   └── result/          # 结果模型、错误分类、诊断清洗、响应工厂
├── model/               # 请求/响应/枚举（ExecuteCodeRequest 等）
└── OjCodeSandboxApplication.java
deploy/
├── docker/              # Java、C++ 镜像的 Dockerfile
├── package-oj-sandbox.sh       # 生成离线部署包
├── prepare-sandbox-images.sh   # 准备五种语言镜像（开发机和服务器通用）
└── install-oj-sandbox.sh       # 服务器安装脚本（打包后为 install.sh）
```

## 更多文档

- [服务端部署手册](./docs/server-deployment.md)
- [沙箱 Docker 镜像准备](./docs/json-dependency-images.md)（固定版本、digest、更新时要同步修改的位置）
- [错误诊断清洗逻辑](./docs/diagnostic-sanitizer-logic.md)
- 设计记录（写完后不再随代码更新）：[资源统计与用例隔离](./docs/design/resource-isolation.md)、[语言性能分析](./docs/design/language-performance-analysis.md)
- [开发约定](./AGENTS.md)

## License

[MIT](./LICENSE)
