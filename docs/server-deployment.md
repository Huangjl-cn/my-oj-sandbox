# 代码沙箱服务器部署

代码沙箱以宿主机 Java 进程运行，通过 `/var/run/docker.sock` 调用本机 Docker Engine。用户代码仍然只在受限容器中编译和执行。

## 业务目录

服务器目录保持为：

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
└── docker-compose.xml
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

打包机需要 Maven、JDK 8、`curl`、`tar` 和 `sha256sum`。在项目根目录执行：

```bash
bash deploy/package-oj-sandbox.sh
```

脚本执行 `mvn clean package`，下载并校验 Eclipse Temurin `8u502-b07` x64 JRE，然后生成部署目录：

```text
deploy/oj-sandbox/
```

`deploy/oj-sandbox` 随整个 `my-oj` 目录一起打包和上传，属于本地生成的部署产物，不提交到 Git。

`deploy/package-oj-sandbox.sh` 和 `deploy/install-oj-sandbox.sh` 只在开发机上用于构建部署包，不需要上传到服务器。服务器只需要完整的 `deploy/oj-sandbox` 目录。

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

## 服务器准备 Docker 镜像

首次部署时，在服务器上运行：

```bash
cd /absolute/path/my-oj
sudo bash oj-sandbox/prepare-sandbox-images.sh
```

该脚本会先检查五个目标镜像；全部已存在时直接成功，不重复构建或下载。只有镜像缺失时，它才使用随部署目录提供的 Dockerfile 构建 Java 和 C++ 镜像，并按固定 digest 拉取 Go、Python 和 Node.js 官方镜像。首次处理缺失镜像需要服务器能访问 Docker Hub、GitHub 和 Maven Central；镜像准备完成后，沙箱处理用户请求时不会联网下载依赖。

镜像的固定版本、digest 和手动命令见 [沙箱 Docker 镜像准备](json-dependency-images.md)。

## 服务器安装

要求服务器为 `Linux x86_64`、使用 systemd，并已安装 Docker Engine。确认部署目录位于 `my-oj/oj-sandbox` 后执行：

```bash
cd /absolute/path/my-oj
sudo bash oj-sandbox/prepare-sandbox-images.sh
sudo bash oj-sandbox/install.sh
```

安装脚本会启动服务并设置开机自启。安装完成后检查：

```bash
sudo systemctl status oj-code-sandbox --no-pager
curl http://127.0.0.1:8100/health
```

安装脚本会：

1. 使用 `SHA256SUMS` 校验 JAR、Java 8 和安装脚本。
2. 验证包内 Java 为 Temurin `1.8.0_502`。
3. 创建专用的 `oj-sandbox` 系统账号并加入 Docker 组。
4. 将当前包复制为 `/opt/oj-code-sandbox/releases` 下的不可变版本。
5. 原子切换 `/opt/oj-code-sandbox/current` 软链接。
6. 写入服务专用的 `JAVA_HOME`、`PATH`、Docker 地址和 Spring profile。
7. 创建工作目录、日志目录和 systemd unit。
8. 启用 Docker 与代码沙箱服务并检查 `/health`。
9. 检查五种语言所需的 Docker 镜像并提示缺失项。

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

上传并解压新部署包，再执行新目录中的 `install.sh`。脚本会安装一个新版本、切换 `current` 并重启服务；旧版本仍保留在 `releases` 中。

需要回滚时，将 `current` 重新指向旧版本，然后重启：

```bash
sudo ln -sfn /opt/oj-code-sandbox/releases/<旧版本> /opt/oj-code-sandbox/current
sudo systemctl restart oj-code-sandbox
```

## 安全说明

加入 Docker 组等价于授予服务账号较高的宿主机权限，因此该账号不能用于其他业务，也不能通过未加密的 TCP 端口暴露 Docker daemon。

当前接口鉴权密钥仍硬编码在应用中。正式开放端口前，应先把鉴权密钥改为外部配置，并限制请求并发、用例数量和来源地址。
