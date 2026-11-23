# 代码沙箱语言性能分析报告

> 项目：oj-code-sandbox 2.0.1（my-oj 判题系统）
> 日期：2026-08-11（问题分析），2026-08-12（优化实施与验证）
> 背景：部署到阿里云 ECS 后，Go / C++ 提交出现"编译超时"（Compilation timed out after 10000 ms）。本文按「问题描述 → 原因分析 → 优化方案 → 优化效果」四个阶段完整记录，语言编译模型差异（§2.1）是理解全篇的关键。

---

## 1. 问题描述：Go / C++ 提交编译超时（修复前）

### 1.1 现象

- 部署到阿里云 ECS（2 vCPU / 1.6GB 入门实例）后，**Go / C++ 提交出现"编译超时"**（`Compilation timed out after 10000 ms`）。
- **Go 是编译超时重灾区**：同一段简单排序代码，服务器 1 核下编译需 34~57s（10s 超时必挂）；本地 1 核下也要 9.1s（同样贴线）。
- **C++ / Java 处于边缘**：简单题 2.7s / 6.1s，复杂题（模板库、大量 import）可能超时。
- **Python / JS 无编译超时风险**（"编译"仅为毫秒级语法检查），但运行阶段受解释执行性能制约。

### 1.2 沙箱架构与资源限制（修复前配置）

#### 执行流程

```
提交代码 → 保存源文件 → 创建编译容器 → 执行编译命令（10s 超时）
        → 创建运行容器 → 逐用例启动运行容器（每个用例 8s 超时）
        → 采集 cgroup 内存峰值/耗时 → 返回结果
```

编译容器与运行容器为**每次请求新建、用完即删**。两容器共享同一宿主临时目录（`tmpCode/<uuid>`，bind mount 到容器内 `/app`）：编译容器以 rw 挂载、写出产物（`main` / `Main.class`），运行容器以 ro 挂载、只读读入产物或源码；容器删除不影响宿主目录，请求结束由 `deleteWorkingDirectory` 清理。

#### 硬编码资源限制（`DockerExecutionEngine.java` 资源限制常量区，修复前）

| 参数 | 值（修复前） | 说明 |
|---|---|---|
| `MEMORY_LIMIT_BYTES` | 512MB | 编译/运行容器内存上限（含 swap 关闭） |
| `CPU_LIMIT` | 1 核（1e9 nanoCpus） | 编译/运行容器共用 |
| `PID_LIMIT` | 64 | 进程数上限 |
| `COMPILE_TIMEOUT_MILLIS` | **10 000 ms** | 编译超时 |
| `RUN_TIMEOUT_MILLIS` | **8 000 ms** | 单用例运行超时 |
| `OUTPUT_LIMIT_BYTES` | 1MB | 输出截断 |

限制为代码内常量，本地与服务器一致。

#### 五种语言定义（`sandbox/language/` 目录）

| 语言 | 镜像 | 编译命令 | 运行命令 | 特殊环境变量 |
|---|---|---|---|---|
| Go | `golang:1.25-alpine` | `go build -o main main.go` | `./main` | `GOCACHE=/tmp/go-build`, `HOME=/tmp` |
| C++ | `oj-code-sandbox/gcc-nlohmann-json:15-3.12.0` | `g++ main.cpp -O2 -std=c++17 -o main` | `./main` | — |
| Java | `oj-code-sandbox/eclipse-temurin-gson:25-jdk-alpine-2.14.0` | `javac -encoding UTF-8 -cp /opt/libs/gson.jar Main.java` | `java -cp /app:/opt/libs/gson.jar Main` | — |
| Python | `python:3.14-alpine` | `python -m py_compile main.py`（字节码/语法检查） | `python main.py` | `PYTHONPYCACHEPREFIX=/tmp/python-cache` |
| JavaScript | `node:24-alpine` | `node --check main.js`（语法检查） | `node main.js` | — |

> 沙箱容器根文件系统只读（`--read-only`），`/tmp` 以 tmpfs（内存文件系统，128MB）挂载，是容器内唯一通用可写区。上表各语言的特殊环境变量（GOCACHE / HOME / PYTHONPYCACHEPREFIX）均指向 `/tmp`：编译器与解释器的临时文件、缓存默认路径都依赖可写区。

### 1.3 服务器实测数据（修复前）

#### 硬件对比（与本地开发机）

| | 服务器（aliyun ECS） | 本地（WSL） |
|---|---|---|
| CPU | **2 vCPU**（Intel Xeon Platinum，共享型，1 物理核 2 线程） | **16 核**（Core Ultra 9 285H） |
| 内存 | **1.6GB**（编译时可用仅 238~440MB） | **15GB** |
| 单核性能 | 基准频率 + 可能被租户挤占 | 睿频 5.8GHz，强单核 |
| 系统 | Ubuntu 26.04 | WSL (Windows) |

#### 编译实测（服务器，沙箱同款限制：`--cpus=1 --memory=512m --memory-swap=512m`，含容器启动 ~1.5s）

测试代码：标准 OJ 风格排序题（读入 N 个数 → 排序 → 输出）。

| 语言 | 编译/检查命令 | 实测耗时 | 10s 超时判定 |
|---|---|---|---|
| **Go** | `go build` | **34~57s**（user 27.7s） | ❌ 必超时 |
| **C++** | `g++ -O2`（简单题） | **2.7s** | ✅ 通过 |
| **Java** | `javac` | **6.1s** | ⚠️ 边缘（余量 4s） |
| **Python** | `py_compile` | 1.7s（检查本身毫秒级） | ✅ 无风险 |
| **JS** | `node --check` | 1.6s（检查本身毫秒级） | ✅ 无风险 |

#### 本地对照实验（同一段 Go 代码）

| 环境 | 限制 | 耗时 | 结论 |
|---|---|---|---|
| 本地 1 核 | `--cpus=1 --memory=512m` | **9.1s** | 1 核下本地也贴 10s 超时线 |
| 本地 16 核 | 无限制 | **2.6~3.4s** | 多核并行收益 ~3.5 倍 |
| 服务器 1 核 | 同款限制 | **34~57s** | 比本地同限制慢 4~6 倍 |

**单核性能差距**：服务器纯编译（user）27s vs 本地同限制约 7.5s → **服务器单核 ≈ 本地 1/3.5**。

#### 内存压力观测（Go 编译期间，服务器宿主）

```
Mem: 1607  1171 ... available 436   Swap: 2047  0    ← 编译开始
Mem: 1607  1224 ... available 383
Mem: 1607  1273 ... available 334
Mem: 1607  1324 ... available 296
Mem: 1607  1368 ... available 238                              ← 编译结束
```

- 宿主 1.6GB 已被 MySQL（433MB）+ Java 后端（193MB）+ Docker 占用约 1.1GB；
- 编译容器再叠 512MB → 可用内存跌至 **238MB**，内核频繁回收页缓存；
- Go 编译 real（57s）远超 user（27s）的 30s 差距即来自内存等待。

#### GOCACHE 缓存实验（修复前）

| 实验 | 耗时 | 说明 |
|---|---|---|
| A：镜像默认缓存目录 | 42.4s | `golang:1.25-alpine` 的 `GOCACHE=/root/.cache/go-build` **为空**（镜像未预置标准库缓存） |
| B：沙箱配置 `GOCACHE=/tmp/go-build` | 34.2s | 与 A 无实质差异 |
| C：换一段不同代码再编译 | 33.5s | **缓存未跨请求复用**：一次性容器，编译产物随容器销毁 |

结论：缓存未跨请求复用——一次性容器 + 缓存目录不持久化，每次提交都是全量编译。

---

## 2. 原因分析

### 2.1 语言编译模型差异：为什么 Go 首编慢、C++ 简单题快

这是全篇的核心。表面上看两者都是"用到了才编译"，但**"编译的东西"完全不同**：

> **C++ 标准库的"实现"是提前预编译好的二进制库，编译时你只处理"声明文本"；Go 标准库的"实现"没有预编译，第一次用时要把实现源码全部编译成机器码。**

#### C++：引入头文件（声明），实现已预先编译

```cpp
#include <iostream>   // 头文件 = 只有声明！
```

- 预处理阶段将头文件**物理展开**进编译单元，展开的内容是**类声明、函数原型、模板声明**（`std::cout` 长什么样），几十 KB ~ 几万行文本。
- 真正的实现（`operator<<` 的函数体、缓冲区管理）在**预编译的 `libstdc++.so`/`.a` 库里**——GCC 发行时官方已编译好，你的编译只需要 `-lstdc++` 链接过去。
- 所以 C++ 的编译 = **展开声明（轻）+ 链接预编译库（快）**。这就是简单题服务器实测 2.7s 的原因。
- **模板是例外**：模板的**定义必须完整写在头文件里**（实例化时需要看到实现），所以 `#include <nlohmann/json.hpp>` 展开的是整个模板实现（几十万行），编译期要实例化、特化——这是 C++ 慢的场景（可达 20~60s+）。
- **缓存（ccache）按"预处理后整个编译单元"判断命中**：用户代码一变即全量重编，跨提交基本无法复用。实测（本地 1 核，沙箱同限制）：含 nlohmann/json 的简单用例（parse + `get<std::vector<int>>`）编译约 2.4s，仅修改输入字符串后重编约 2.0s——编译时间几乎无下降，模板实例化与用户代码强耦合，没有任何跨提交可复用的部分。

#### Go：import 的是完整实现源码

```go
import ("fmt"; "sort"; "strconv")   // 没有头文件！import 的就是完整实现
```

- Go **没有"声明/实现分离"**：每个包（fmt、sort、strconv…）的源码本身就是实现。第一次 `go build` 必须把 import 链上的所有包**完整编译成机器码**——fmt 传递依赖 io、os、strconv、unicode、reflect……大半个标准库，固定成本极高，且**与用户代码长短无关**（hello world 也要付同样的标准库编译费）。
- 标准库**未预编译**（官方镜像 `GOCACHE` 目录实测为空，见 §1.3 实验 A），首次编译需全量编译标准库。
- 包级编译模型：`import` 的每个包为独立编译单元，按 `GOMAXPROCS` **并行编译**——多核友好，1 核机器上全串行，最吃亏。
- **静态链接**：运行时（GC、调度器、反射）整体打包进二进制，hello world 产物 5~8MB，链接开销大。
- **GOCACHE 内容寻址缓存**（按"包内容 + 编译参数"hash 命中）：标准库包内容永远不变 → 缓存**永远有效**。日常开发"Go 编译快"的体验正是这套缓存机制给的：标准库只编译第一次，之后只编用户代码包并链接；**冷缓存下的首次全量编译并不快**（§1.3 服务器实测 34~57s 的构成大头即标准库全编）。

#### 其余语言

**Java**：JDK 标准库模块（java.base 等）**出厂已预编译**，javac 只编译用户代码，编译量天然小；无跨进程缓存需求（本身快），编译期内存需求低。运行期 JVM 类加载 → 解释执行 → 热点方法 JIT（C1/C2 分层编译）→ GC；**启动开销最大**（数百 ms ~ 1s），短程序享受不到 JIT 预热收益。

**Python**：`py_compile` 仅生成字节码（.pyc），本质是语法检查，**毫秒级**；真正编译发生在运行时首次执行时。CPython 的 `.pyc` 是磁盘字节码缓存设计（`__pycache__` 目录），供下次 import 跳过重新编译直接加载；沙箱内经 `PYTHONPYCACHEPREFIX=/tmp/python-cache` 重定向到 tmpfs，容器销毁即弃。运行期 CPython **解释执行**字节码、**无 JIT**、动态类型、**GIL** 单线程，比原生慢 **10~100 倍**，是五语言中运行性能最低者。

**JavaScript（Node）**：`node --check` 纯语法检查，**毫秒级**；真正编译在运行时。V8 的编译产物（Ignition 字节码、TurboFan JIT 机器码）只存在于进程内存，运行结束即释放；Node 没有 Python 式的字节码磁盘缓存文件机制（服务端常驻场景缓存收益小），因此 `node --check` 连字节码都不产出，纯语法验证。运行性能介于 Python 与 Java 之间（比 CPython 快 3~10 倍）；单线程事件循环。

#### 横向对比

| 维度 | C++ | Go | Java | JS | Python |
|---|---|---|---|---|---|
| 编译耗时 | 中（模板题爆炸） | **慢**（标准库全编） | 中 | 毫秒级 | 毫秒级 |
| 编译期内存 | **高**（模板实例化） | 中 | 低 | 极低 | 极低 |
| 运行性能 | ★★★★★ | ★★★★☆ | ★★★☆（有预热） | ★★☆（JIT） | ★（解释执行） |
| 启动开销 | 零 | 零 | **高**（JVM） | 低 | 低 |
| 跨代码缓存可复用 | 几乎不能 | ✅ 包级可复用 | 不需要 | 不需要 | 不需要 |

### 2.2 四因素叠加：Go 为什么必超时

```
Go 标准库全量重编（语言特性，纯 CPU 27s）
    × 服务器单核性能仅本地 1/3.5（机器）
    × 1 核限制下多核并行设计完全失效（沙箱限制）
    × 宿主内存不足导致额外 10~30s 等待（机器）
    × 每次无 GOCACHE 缓存、标准库全量重编（沙箱架构）
    × 编译超时仅 10s（沙箱配置）
─────────────────────────────────────────────
= 34~57s，远超 10s 超时，必然失败
```

其中**语言特性**（第一项）是根因：Go 编译的固定成本来自"标准库实现源码全量编译"，与提交的代码长短无关，C++ 则没有这项成本（实现已预编译）。服务器性能与沙箱配置是放大因素。

### 2.3 风险矩阵（修复前配置下）

| 语言 | 编译超时风险 | 运行超时风险 | 说明 |
|---|---|---|---|
| Go | 🔴 高 | 🟢 低 | 编译是瓶颈；运行快 |
| C++ | 🟡 中（复杂模板题） | 🟢 低 | 简单题 2.7s，json.hpp 级可达 20~60s+ |
| Java | 🟡 中（6.1s 余量小） | 🟡 中 | JVM 启动 + JIT 预热成本 |
| JS | 🟢 无 | 🟡 中 | 解释+JIT，重算法题慢于 C++/Go |
| Python | 🟢 无 | 🔴 高 | 解释执行慢 10~100 倍，8s 统一时限偏紧 |

---

## 3. 优化方案

### 3.1 约束：调用方 60s 总预算

调用方（oj-backend）对沙箱请求的**总预算为 60s**——超过 60s 未返回直接判定超时。因此编译超时不能简单设为 60s，需在预算内为运行阶段留出空间：

```
60s 总预算 = 编译阶段（容器创建 ~1.5s + 编译）+ 运行阶段（容器创建 ~1.5s + N × 用例耗时）
最坏情况（N 个用例）：1.5 + 编译超时 + 1.5 + N×8s
```

编译超时定为 **30s**（为运行阶段留出 30s），同时优化目标是让**实际编译耗时**远低于 30s，使 30s 只是保险线而非常态消耗。超时语义为硬中断：`awaitCompletion` 超时后立即返回并删除容器，不会继续执行。

### 3.2 四项优化

| 项 | 实施值 | 作用与依据 |
|---|---|---|
| **编译超时** | 10s → **30s** | 60s 总预算约束下为运行阶段留出空间（见 §3.1） |
| **编译容器 CPU** | 1 核 → **2 核**（宿主核数） | Go 包级并行编译收益接近翻倍（GOMAXPROCS 自动跟随 cgroup 配额）；C++/Java 部分受益。**运行容器保持 1 核**：OJ 代码基本单线程，2 核对运行无收益，且恶意死循环代码 2 核可占满宿主全部 CPU、拖垮后端与数据库，安全设计不动 |
| **Go GOCACHE 持久卷** | `oj-gocache:/tmp/go-build`（named volume） | 标准库构建产物跨提交复用（内容寻址缓存，包内容不变即命中）。GOCACHE 环境变量不变，仅将 `/tmp/go-build` 从容器内 tmpfs 换成持久卷挂载；仅编译容器挂载、运行容器不挂载 |
| **C++ 编译 `-pipe`** | 编译命令加 `-pipe` | 预处理中间产物走内存管道而非临时文件，降低 mega 头文件（json.hpp）场景的磁盘 IO |

**关于环境变量**：GOCACHE/HOME 指向 `/tmp` 的原因见 §1.2。其中 `HOME=/tmp` 为防御性设置——实测显式设置 GOCACHE 后 `go build` 标准库并不依赖 HOME 可写，但可避免 `$HOME` 派生路径（go env 配置、模块缓存等）落在只读根上。

### 3.3 评估中的方案（未实施）

| 项 | 说明 | 结论 |
|---|---|---|
| 升级实例到 4C8G | 单核性能差 3.5 倍 + 内存不足拖慢 1.3~2 倍；8GB 下编译容器 512MB 无竞争，4 核下 Go 编译预计进入 10s 内 | 治本方案，需投入，待业务量增长后考虑 |
| Python/JS 运行超时单独放宽 | 8s 统一时限对解释型偏紧 | 可评估 8s → 12~15s；当前先观察线上数据 |
| C++ ccache 持久卷 | 按编译单元命中，用户代码一变即失效，跨代码不命中 | **不推荐**（实测 §2.1：改输入字符串后编译时间无下降） |
| Java 编译优化 | javac 本身无缓存方案，但编译量小 | 无需处理，30s 超时已覆盖 |
| Go 镜像预置标准库缓存 | 把标准库编译产物打进镜像 | GOCACHE 卷已覆盖收益，且改镜像需重推 5 个镜像，不划算 |
| 常驻编译容器池 | 预热/保活/资源管理复杂度高 | 当前并发低不值得 |

### 3.4 改动清单（已实施）

```text
src/main/java/com/hjl/ojcodesandbox/sandbox/docker/DockerExecutionEngine.java
  ├─ COMPILE_TIMEOUT_MILLIS: 10_000L → 30_000L（受调用方 60s 总预算约束，未采用 60s）
  ├─ 新增 COMPILE_CPU_LIMIT（2 核），createContainer() 编译/运行容器分开传参
  └─ 编译容器挂载语言声明的编译缓存卷（named volume）
src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerLanguageDefinition.java
  └─ 新增 compileCacheVolume 配置（"卷名:/容器路径"），Go 声明 oj-gocache:/tmp/go-build
src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerGoCodeSandbox.java
  └─ GOCACHE=/tmp/go-build 不变，该路径由容器内 tmpfs 改为持久卷挂载
src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerCppCodeSandbox.java
  └─ 编译命令加 -pipe（中间产物走内存管道，省磁盘 IO）
```

---

## 4. 优化效果

### 4.1 本地验证（WSL）

- `mvn clean test`：46 通过（1 跳过 = Docker 集成测试默认禁用）。
- `RUN_DOCKER_TESTS=true mvn test -Dtest=DockerSandboxIntegrationTest`：**32 通过**（真实容器端到端，含编译超时 30s 生效、各语言执行）。
- GOCACHE 卷由 Java 代码真实创建并写入 35.4MB 缓存；容器 tmpfs `/tmp`（128m）与卷 `/tmp/go-build` 共存，`mount` 确认子路径独立挂载于卷（缓存不随 tmpfs 丢失）。卷为 Docker named volume（local driver），数据位于宿主 `/var/lib/docker/volumes/oj-gocache/_data`，容器删除不影响卷。
- docker CLI 同参数模拟：冷缓存 6.64s → 新容器热缓存 **0.03s**，跨容器复用成立（每轮均为一次性容器，唯一共享的是卷）。

### 4.2 服务器实测（阿里云 ECS 2 vCPU / 1.6GB）

通过沙箱 HTTP 接口（`POST /executeCode`）真实提交 Go 排序题：

| 轮次 | 总耗时（含容器创建+编译+运行） | 结果 |
|---|---|---|
| 冷缓存（首次，2 核） | **24.3s** | Accepted（修复前 34~57s 必超时） |
| 热缓存（同代码再提交） | **2.3s** | Accepted |
| 热缓存（换代码，标准库命中） | **2.3s** | Accepted |
| C++ 对照（-pipe） | **4.1s** | Accepted，输出正确 |

> 冷缓存从 34~57s 降至 24.3s 的原因：编译 2 核 + MySQL 内存优化（performance_schema 关闭等，宿主可用内存 +240MB）叠加；热缓存 2.3s 完全受 GOCACHE 卷支撑。

### 4.3 遗留观察项

1. **并发编译**：两个编译容器同时 2 核会争抢宿主 2 核（各自变慢，不失败）；宿主内存 1.6G 下双编译可能用 swap，OJ 低并发可接受。
2. **GOCACHE 卷磁盘**：Go cache 自带 LRU 淘汰，当前约 35MB；需观察长期增长，必要时 `docker system prune`。
3. **60s 总预算边界**：多用例（≥4 个）且全部打满 8s 运行超时 + 编译 30s 最坏可能超预算；正常用例远不满 8s，先观察线上数据。

---

## 附录 A：测试方法

所有编译测速均使用与沙箱一致的资源限制：

```bash
# 服务器/本地通用（golang 为例）
docker run --rm --cpus=1 --memory=512m --memory-swap=512m \
  -v /tmp/test:/app -w /app -e GOCACHE=/tmp/go-build \
  golang:1.25-alpine sh -c 'go build -o main main.go'

# 计时：date +%s%N 差值（含 ~1.5s 容器启动开销）
# 内存采样：编译期间循环执行 free -m
```

测试代码为 OJ 风格排序题（读 N 个数 → 排序 → 输出），五种语言实现等价逻辑。

## 附录 B：关键代码位置

| 内容 | 文件 |
|---|---|
| 资源限制常量（512MB / 1核运行 / 2核编译 / 30s / 8s） | `src/main/java/com/hjl/ojcodesandbox/sandbox/docker/DockerExecutionEngine.java` 资源限制常量区 |
| 编译缓存卷配置（compileCacheVolume） | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerLanguageDefinition.java` |
| Go 语言定义（GOCACHE/HOME 覆盖 + 缓存卷） | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerGoCodeSandbox.java` |
| C++ 语言定义（-pipe 编译命令） | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerCppCodeSandbox.java` |
| Java 语言定义 | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerJavaCodeSandbox.java` |
| Python 语言定义 | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerPythonCodeSandbox.java` |
| JS 语言定义 | `src/main/java/com/hjl/ojcodesandbox/sandbox/language/DockerJavaScriptCodeSandbox.java` |
