# 代码沙箱服务器部署

代码沙箱以宿主机 Java 进程运行，通过 `/var/run/docker.sock` 调用本机 Docker Engine。用户代码仍然只在受限容器中编译和执行。

## 业务目录

服务器上的 `my-oj` 目录由 my-oj-backend 仓库的 `deploy/` 目录（compose、后端、前端）加上本仓库生成的 `oj-sandbox` 部署包组成：

```text
my-oj/
├── oj-backend/
├── oj-frontend/
├── oj-sandbox/
│   ├── oj-code-sandbox-<version>.jar
│   ├── install.sh
│   ├── prepare-sandbox-images.sh
│   ├── SHA256SUMS
│   ├── docker/
│   │   ├── java-gson/Dockerfile
│   │   └── cpp-nlohmann-json/Dockerfile
│   └── runtime/
│       └── java8/
└── docker-compose.yml
```

`oj-sandbox` 是完整的离线部署包，不需要 Makefile。Java 8、JAR、安装脚本和校验清单作为一个版本交付，服务器安装过程不访问 Maven Central、GitHub 或 Adoptium。

运行时使用以下系统目录：

```text
/opt/oj-code-sandbox/releases/      已安装的不可变版本
/opt/oj-code-sandbox/current        当前版本软链接
/etc/oj-code-sandbox/               服务环境变量
/etc/systemd/system/                systemd unit
/var/lib/oj-code-sandbox/tmpCode/   用户代码临时目录
/var/log/oj-code-sandbox/           应用滚动日志
```

## 本地制作离线包

打包机必须是 Linux x86_64（Windows 上用 WSL2），需要 Maven、JDK 8（不能用 JDK 21 及以上，Lombok 1.18.28 不支持）、`curl`、`tar`、`sha256sum`，并能访问 GitHub 下载 JRE。在项目根目录执行：

```bash
bash deploy/package-oj-sandbox.sh
```

脚本执行 `mvn clean package`，下载并校验 Eclipse Temurin `8u502-b07` x64 JRE，然后生成部署目录：

```text
deploy/oj-sandbox/
```

`deploy/oj-sandbox` 是本地生成的部署产物，不提交到 Git。把它整个上传到服务器的 `my-oj/` 下，例如：

```bash
scp -r deploy/oj-sandbox <用户>@<服务器>:/absolute/path/my-oj/
```

部署包里的 `install.sh` 由 `deploy/install-oj-sandbox.sh` 复制而来，`prepare-sandbox-images.sh` 和 `docker/` 下的 Dockerfile 也一并打包，都在服务器上执行。

Temurin 发行文件固定为：

```text
OpenJDK8U-jre_x64_linux_hotspot_8u502b07.tar.gz
SHA-256: f1a7bea0804bfa5627dac412fe7a0d751c4228592e356d6a32a30da54a48ed7a
```

如果本地已经保存该发行包，可以避免再次下载：

```bash
TEMURIN_JRE_ARCHIVE=/absolute/path/OpenJDK8U-jre_x64_linux_hotspot_8u502b07.tar.gz \
  bash deploy/package-oj-sandbox.sh
```

## 服务器安装

要求服务器为 `Linux x86_64`、使用 systemd，并已安装 Docker Engine。确认部署目录位于 `my-oj/oj-sandbox` 后执行：

```bash
cd /absolute/path/my-oj
sudo bash oj-sandbox/prepare-sandbox-images.sh   # 准备五种语言的镜像，已存在时直接跳过
sudo bash oj-sandbox/install.sh                  # 安装并启动服务
```

`prepare-sandbox-images.sh` 会先检查五个目标镜像；全部已存在时直接成功，不重复构建或下载。只有镜像缺失时，它才使用随部署目录提供的 Dockerfile 构建 Java 和 C++ 镜像，并按固定 digest 拉取 Go、Python 和 Node.js 官方镜像。首次处理缺失镜像需要服务器能访问 Docker Hub、GitHub 和 Maven Central；镜像准备完成后，沙箱处理用户请求时不会联网下载依赖。

镜像的固定版本、digest 和手动命令见 [沙箱 Docker 镜像准备](json-dependency-images.md)。

`install.sh` 会启动服务并设置开机自启。安装完成后检查：

```bash
sudo systemctl status oj-code-sandbox --no-pager
curl http://127.0.0.1:8100/health
```

安装脚本依次：

1. 使用 `SHA256SUMS` 校验 JAR、Java 8 和安装脚本，并确认包内 Java 是固定的 Temurin 版本。
2. 启用 Docker 服务。
3. 创建专用的 `oj-sandbox` 系统账号并加入 Docker 组，创建工作目录、日志目录和配置目录。
4. 将当前包复制为 `/opt/oj-code-sandbox/releases` 下的不可变版本，原子切换 `/opt/oj-code-sandbox/current` 软链接。
5. 写入服务专用的环境文件（`JAVA_HOME`、`PATH`、Docker 地址、Spring profile 等）和 systemd unit。
6. 确认服务账号能读写 Docker socket，检查五种语言所需的镜像并提示缺失项。
7. 停止旧服务、启动新服务，等待 `/health` 通过。

Java 环境变量只对 `oj-code-sandbox.service` 生效，不修改服务器默认 Java，也不影响 OJ 后端。

## 安装参数

默认监听 `0.0.0.0:8100`，JVM 堆大小为 `128m` 到 `512m`。可以在安装时覆盖：

```bash
sudo \
  SERVER_ADDRESS=0.0.0.0 \
  SERVER_PORT=8100 \
  JAVA_XMS=128m \
  JAVA_XMX=512m \
  bash oj-sandbox/install.sh
```

通过安全组或防火墙限制只有 OJ 后端能够访问 `8100` 端口。

## 服务管理

```bash
sudo systemctl status oj-code-sandbox
sudo systemctl restart oj-code-sandbox
sudo systemctl stop oj-code-sandbox
sudo journalctl -u oj-code-sandbox -f
```

生产 profile 同时将滚动日志写入 `/var/log/oj-code-sandbox`。

## 更新和回滚

上传并解压新部署包，再执行新目录中的 `install.sh`。脚本会安装一个新版本、切换 `current` 并重启服务；旧版本仍保留在 `releases` 中。旧版本不会自动删除，且每个版本都带一份完整的 JRE，需要定期手动删除不再需要回滚的版本。

需要回滚时，将 `current` 重新指向旧版本，然后重启：

```bash
sudo ln -sfn /opt/oj-code-sandbox/releases/<旧版本> /opt/oj-code-sandbox/current
sudo systemctl restart oj-code-sandbox
```

## 安全说明

加入 Docker 组等价于授予服务账号较高的宿主机权限，因此该账号不能用于其他业务，也不能通过未加密的 TCP 端口暴露 Docker daemon。

接口用 ECDSA 请求签名鉴权，沙箱只持有生产公钥（`application-prod.yml`），不持有秘密；轮换密钥需要重新打包部署，步骤见 my-oj-backend 的 `docs/sandbox-auth/README.md`。

沙箱自身不限制并发请求数和用例数量，目前依赖后端的判题并发上限（每个请求都会启动 512MB 内存上限的容器）。正式对公网开放端口前，应先在沙箱侧补上这些限制，并限制来源地址。
