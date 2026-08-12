# DiagnosticSanitizer 处理逻辑说明

> 对象：`src/main/java/com/hjl/ojcodesandbox/sandbox/result/DiagnosticSanitizer.java`
>
> 职责：把编译器/运行时的**原始诊断输出**（stderr 优先、stdout 兜底）清洗为
> 「保留关键信息、剔除噪音、长度受限」的可返回文本。与语言无关——不接收语言枚举，
> 只依赖两个输入：原始诊断文本 + 用户源文件名（如 `main.js`）。

## 1. 设计定位

`DockerExecutionEngine` 捕获容器输出 → `DiagnosticSanitizer.format()` 清洗 →
`SandboxResponseFactory` 放入 `judgeInfo.message` 返回给调用方。

```
DockerExecutionEngine.diagnosticOf()          // 选流：stderr → stdout → 退出码兜底
    └─ DiagnosticSanitizer.format(type, text, sourceFileName)   // 逐行清洗
    └─ DiagnosticSanitizer.exitCodeDiagnostic(exitCode, memKb)  // 零输出兜底
```

**语言无关的实现手段**：不传语言参数，而是通过「源文件名是否出现在行内」判断某一行
是否是用户代码的定位/帧；通过「格式共性」识别错误头（如 `XxxError: msg` 在 JS/Python/
Java/Go 中都是常见形态）；通过「噪音特征」剔除内部实现（`node:internal`、`/usr/` 路径）。

## 2. 处理管线（`format()` 逐行流程）

```
原始文本
  │ 1. 归一化：\r\n / \r → \n，按行拆分
  ▼
逐行处理：
  │ 2. 路径清理：/app/main.js → main.js，./main.go → main.go
  │ 3. trim 后分类：
  │    ├─ 空行          → 重置「续行预算」和「Go 配对上下文」（空行隔断相邻关系）
  │    ├─ 丢弃规则命中  → 跳过（内部栈帧 / 运行库位置 / 版本页脚）
  │    └─ 其余          → 进入保留判定
  │ 4. 保留判定（满足任一即保留）：
  │    ├─ 行内含源文件名（定位行 / 用户栈帧）
  │    ├─ 错误头（isTopLevelError）
  │    ├─ 编译器错误汇总（"N errors"）
  │    ├─ 续行预算（源文件定位后 2 行内，用于源码片段与 ^ 指示符）
  │    └─ Go 帧配对（定位行命中时回补上一行函数名）
  │ 5. 连续重复行去重（栈溢出数百个相同帧 → 折叠为 1~2 行）
  ▼
收尾：
  ├─ 兜底：若一行都没保留，取第一个非空行（避免空结果）
  ├─ 截断：最多 20 行；总长 4096 字符，超长追加 "..." 截断
  └─ 前置固定首行：errorType（"Compile Error" / "Runtime Error"）
```

## 3. 规则明细

### 3.1 丢弃规则（噪音）

| 规则 | 匹配形态 | 语言 |
|---|---|---|
| 内部栈帧 `isInternalStackFrame` | 以 `at ` 开头但行内不含源文件名（trim 后判断） | Java/Node |
| 运行库位置 `isRuntimeLibraryLocation` | 含 `node:internal`、`/usr/`、`\usr\`，且不含源文件名 | 全部（Go runtime 栈、Node 内部模块） |
| 版本页脚 `isFooterNoise` | 以 `Node.js v` 开头 | Node |

### 3.2 保留规则（关键信息）

| 规则 | 匹配形态 | 样例 |
|---|---|---|
| 源文件引用 | 行内含 `sourceFileName` | `main.js:2`、`\tat Main.main(Main.java:3)`、`\tmain.go:5 +0x17`、`File "main.py", line 2`、`main.cpp:3:19: error` |
| 错误头（通用）`ERROR_HEADER_PATTERN` | `[A-Za-z]+(Error\|Exception\|Warning)(?:\[[^\]]*\])?: ` 任意位置 | `RangeError: ...`、`RecursionError: ...`、`TypeError [ERR_XXX]: ...`、`Caused by: java.lang.IllegalArgumentException: ...` |
| 错误头（专属前缀） | `Exception in thread` / `panic:` / `fatal error:` / `error:` / `terminate called` / `what():` | Java/Go/C++ 的头部与消息行 |
| goroutine 上下文 | `^goroutine \d+ \[running\]:$` | Go panic 块 |
| 错误汇总 | `\d+ (error|errors|warning|warnings)` | `1 error`（javac） |
| 续行预算 | 源文件定位行后最多 2 行（`at ` 帧不消耗预算） | Node 定位块中的源码行 + `^`；GCC 行号源码块 |
| Go 帧配对 | 定位行 `^[\w./-]+\.go:\d+( \+0x[0-9a-f]+)?$` 时，回补上一行函数名 `^[A-Za-z_][\w.]*\(.*\)$` | `main.main()` + `main.go:5 +0x17` |

### 3.3 零输出兜底（`exitCodeDiagnostic`）

stderr/stdout 均为空且退出码非 0 时使用（调用方：`DockerExecutionEngine.diagnosticOf()`）：

| 退出码 | 信号 | 输出 |
|---|---|---|
| 137 | SIGKILL | `Process killed (exit 137) - memory usage peaked at 500.0 MB, likely exceeded the memory limit`（有内存峰值数据时；无则去掉峰值句） |
| 139 | SIGSEGV | `Segmentation fault (exit 139)` |
| 134 | SIGABRT | `Aborted (exit 134)` |
| 136 | SIGFPE | `Floating point exception (exit 136)` |
| 132 | SIGILL | `Illegal instruction (exit 132)` |
| 其他 | — | `Process exited with code N` |

## 4. 输出样例（真实容器捕获数据）

### Java — 栈溢出（原始 500+ 重复帧）
```
Runtime Error
Exception in thread "main" java.lang.StackOverflowError
	at Main.test(Main.java:6)
```

### Java — 编译错误
```
Compile Error
Main.java:3: error: ';' expected
        System.out.println(1)
                             ^
1 error
```

### Node — 栈溢出（用户报告的原始问题场景）
```
Runtime Error
main.js:2
    return sortedArrayToBST(nums);  // 无限递归，无终止条件
    ^
RangeError: Maximum call stack size exceeded
    at sortedArrayToBST (main.js:2:5)
    at sortedArrayToBST (main.js:2:12)
```
（V8 原生只打 10 帧，去重后剩 2 帧；`Node.js v24.19.0` 页脚被剔除）

### Node — 运行时错误（ReferenceError）
```
Runtime Error
main.js:1
console.log(missingValue);
            ^
ReferenceError: missingValue is not defined
    at Object.<anonymous> (main.js:1:13)
```
（`node:internal/...` 内部帧与版本页脚被剔除）

### Node — V8 堆 OOM
```
Runtime Error
FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory
```
（GC 日志行被剔除，仅保留错误行）

### Python — 递归溢出（错误头在 traceback 末尾）
```
Runtime Error
  File "main.py", line 3, in <module>
    f(0)
    ~^^^
  File "main.py", line 2, in f
    return f(n + 1)
  File "main.py", line 2, in f
    return f(n + 1)
  File "main.py", line 2, in f
    return f(n + 1)
  [Previous line repeated 996 more times]
RecursionError: maximum recursion depth exceeded
```
（CPython 折叠行 `[Previous line repeated ...]` 与末尾错误行均保留）

### Python — 运行时错误（NameError）
```
Runtime Error
  File "main.py", line 1, in <module>
    print(missing_value)
          ^^^^^^^^^^^^^
NameError: name 'missing_value' is not defined
```

### Go — panic（两行式帧配对）
```
Runtime Error
panic: runtime error: index out of range [10] with length 3
goroutine 1 [running]:
main.main()
	main.go:5 +0x17
```

### Go — 编译错误
```
Compile Error
main.go:4:5: undefined: fmt
```
（`# command-line-arguments` 包头被剔除）

### C++ — 编译错误（GCC 15 行号源码块）
```
Compile Error
main.cpp: In function 'int main()':
main.cpp:3:19: error: expected ';' before 'return'
    3 |     std::cout << 1
      |                   ^
```

### C++ — 未捕获异常
```
Runtime Error
terminate called after throwing an instance of 'std::runtime_error'
  what():  boom
```

### C++ — 段错误（原始输出为空，退出码兜底）
```
Runtime Error
Segmentation fault (exit 139)
```

## 5. 还可以提升的地方

按影响排序，均为「改进候选」而非缺陷：

1. **错误头优先扫描（最重要）**：当前按行正序扫描，`MAX_LINES = 20` 截断是"先到先得"。
   若错误头出现在长输出的末尾（如超长 Python traceback、超长 GCC 输出），可能在到达前
   就被截断。改进：先完整扫描一遍定位错误头行并优先保留，再按顺序填充其余行，保证
   「错误类型」永不丢失。
2. **规则配置化**：错误头/页脚/帧规则目前全局硬编码在一个类里，且混有少量 Go 专属模式
   （`GO_FRAME_LOCATION_PATTERN` 等）。可下沉为按语言配置（如挂在
   `DockerLanguageDefinition` 上），新语言接入时不用改清洗器本体。代价是
   `format()` 签名与所有调用点、测试的联动修改。
3. **Python 交替重复帧折叠**：CPython 已把 996 个相同帧折叠为一行，但保留的 2~3 对
   `File + 源码` 样板帧仍是交替模式，现有"连续相同行去重"对它们无效。可识别
   「File/源码」交替重复对并折叠为 1 对。
4. **Python `Traceback (most recent call last):` 头被剔除**：当前不保留（不影响定位，
   属于取舍）；若想让输出更接近原生 traceback 可加入保留规则。
5. **栈帧数量上限**：当前靠"20 行截断 + 连续去重"间接控制帧数量；可显式限制
   「最多保留 N 个用户帧」（如 3 个），给错误头/源码片段留出更稳定的空间。
6. **超时语义提示**：`DockerExecutionEngine` 运行超时文案已追加
   `(possible infinite loop or recursion)`，但未按语言细分；C++ `-O2` 下递归会被
   GCC 编译为死循环（实测汇编为 `jmp` 自旋），该提示是对此类场景的主要线索。
7. **多文件源码假设**：`sanitizePath` 与 `referencesSource` 都以单一 `sourceFileName`
   为锚点；若用户代码依赖同目录其他文件，相关报错行不会被识别为"源文件引用"。

## 6. 验证与测试

- 单元测试：`DiagnosticSanitizerTest`（17 个用例）覆盖逐条规则。
- 端到端回归：`DiagnosticRealScenarioTest`（8 个用例）内嵌真实容器捕获的完整 stderr
  （与沙箱一致的镜像/命令/512MB 限制），断言关键行保留、噪音剔除。
- `mvn clean test`：46 个测试通过（1 个 Docker 集成测试按配置跳过）。
