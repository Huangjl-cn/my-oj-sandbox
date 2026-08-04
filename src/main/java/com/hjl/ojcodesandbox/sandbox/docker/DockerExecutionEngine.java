package com.hjl.ojcodesandbox.sandbox.docker;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.sandbox.language.DockerLanguageDefinition;
import com.hjl.ojcodesandbox.sandbox.result.DiagnosticSanitizer;
import com.hjl.ojcodesandbox.sandbox.result.SandboxExecutionResult;
import com.hjl.ojcodesandbox.sandbox.result.SandboxFailureType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在受限 Docker 容器中完成代码编译、逐用例运行和资源采集。
 */
@Slf4j
@Component
public class DockerExecutionEngine {

    private static final String WORK_DIR_NAME = "tmpCode";

    private static final String CONTAINER_WORK_DIR = "/app";

    private static final long MEMORY_LIMIT_BYTES = 512L * 1024L * 1024L;

    private static final long CPU_LIMIT = 1_000_000_000L;

    private static final long PID_LIMIT = 64L;

    private static final long COMPILE_TIMEOUT_MILLIS = 10_000L;

    private static final long RUN_TIMEOUT_MILLIS = 5_000L;

    private static final long REQUEST_TIMEOUT_MILLIS = 25_000L;

    private static final int OUTPUT_LIMIT_BYTES = 1024 * 1024;

    /**
     * 为一次请求创建容器，编译一次代码并按输入顺序逐个运行。
     *
     * @param request            代码执行请求
     * @param languageDefinition 语言执行定义
     * @return 不依赖 HTTP 模型的沙箱执行结果
     */
    public SandboxExecutionResult execute(ExecuteCodeRequest request,
                                          DockerLanguageDefinition languageDefinition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MILLIS);
        File sourceFile = null;
        DockerClient dockerClient = null;
        String containerId = null;

        try {
            // 每个请求使用独立工作目录和容器，避免不同请求之间共享文件。
            sourceFile = saveSourceCode(request.getCode(), languageDefinition.getSourceFileName());
            dockerClient = createDockerClient();
            containerId = createContainer(dockerClient, sourceFile.getParentFile(), languageDefinition);
            dockerClient.startContainerCmd(containerId).exec();

            long compileTimeout = remainingTimeout(deadlineNanos, COMPILE_TIMEOUT_MILLIS);
            if (compileTimeout <= 0) {
                return compileFailure(new ArrayList<>(), "Compilation timed out", languageDefinition, 0L, 0L);
            }

            DockerCommandResult compileResult = executeCommand(
                    dockerClient,
                    containerId,
                    languageDefinition.getCompileCommand(),
                    compileTimeout
            );
            if (compileResult.isTimedOut()) {
                return compileFailure(new ArrayList<>(), "Compilation timed out after " + compileTimeout + " ms",
                        languageDefinition, compileResult.getMemoryKb(), compileResult.getTimeMillis());
            }
            if (compileResult.getExitCode() == null) {
                return systemFailure(new ArrayList<>(), "Unable to read compiler exit code");
            }
            if (compileResult.getExitCode() != 0L) {
                return compileFailure(new ArrayList<>(), diagnosticOf(compileResult), languageDefinition,
                        compileResult.getMemoryKb(), compileResult.getTimeMillis());
            }

            List<String> outputs = new ArrayList<>();
            long maxMemoryKb = 0L;
            long maxTimeMillis = 0L;
            List<String> inputList = request.getInputList();
            // 编译产物在同一容器内复用，每个输入启动一个独立 exec 进程。
            for (String input : inputList) {
                long runTimeout = remainingTimeout(deadlineNanos, RUN_TIMEOUT_MILLIS);
                if (runTimeout <= 0) {
                    return runtimeFailure(outputs, "Request time limit exceeded", languageDefinition,
                            maxMemoryKb, maxTimeMillis);
                }

                DockerCommandResult runResult = executeCommand(
                        dockerClient,
                        containerId,
                        languageDefinition.getRunCommand(input),
                        runTimeout
                );
                maxMemoryKb = Math.max(maxMemoryKb, runResult.getMemoryKb());
                maxTimeMillis = Math.max(maxTimeMillis, runResult.getTimeMillis());
                outputs.add(normalizeOutput(runResult.getStandardOutput()));

                if (runResult.isTimedOut()) {
                    return runtimeFailure(outputs, "Time Limit Exceeded after " + runTimeout + " ms",
                            languageDefinition, maxMemoryKb, maxTimeMillis);
                }
                if (runResult.isOutputTruncated()) {
                    return runtimeFailure(outputs, "Output Limit Exceeded", languageDefinition,
                            maxMemoryKb, maxTimeMillis);
                }
                if (runResult.getExitCode() == null) {
                    return systemFailure(outputs, "Unable to read program exit code");
                }
                if (runResult.getExitCode() != 0L) {
                    return runtimeFailure(outputs, diagnosticOf(runResult), languageDefinition,
                            maxMemoryKb, maxTimeMillis);
                }
            }
            return SandboxExecutionResult.success(outputs, maxMemoryKb, maxTimeMillis);
        } catch (NotFoundException e) {
            log.error("Docker resource is unavailable for image={}", languageDefinition.getImage(), e);
            return systemFailure(new ArrayList<>(), "Required Docker image is unavailable: "
                    + languageDefinition.getImage());
        } catch (Exception e) {
            log.error("Docker sandbox execution failed for language={}", languageDefinition.getLanguage().getValue(), e);
            return systemFailure(new ArrayList<>(), "Docker sandbox execution failed");
        } finally {
            removeContainer(dockerClient, containerId);
            closeDockerClient(dockerClient);
            deleteWorkingDirectory(sourceFile);
        }
    }

    /**
     * 将源代码写入本次请求的唯一工作目录。
     *
     * @param code           用户源代码
     * @param sourceFileName 语言要求的源文件名
     * @return 已写入的源文件
     */
    private File saveSourceCode(String code, String sourceFileName) {
        String workingRootPath = System.getProperty("user.dir") + File.separator + WORK_DIR_NAME;
        String workingDirectoryPath = workingRootPath + File.separator + java.util.UUID.randomUUID();
        String sourceFilePath = workingDirectoryPath + File.separator + sourceFileName;
        return FileUtil.writeString(code, sourceFilePath, StandardCharsets.UTF_8);
    }

    /**
     * 根据环境变量或本机平台选择 Docker 连接地址。
     *
     * @return Docker 客户端
     */
    private DockerClient createDockerClient() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (StrUtil.isBlank(dockerHost)) {
            File dockerSocket = new File("/var/run/docker.sock");
            if (dockerSocket.exists() && dockerSocket.canRead()) {
                System.setProperty("DOCKER_HOST", "unix:///var/run/docker.sock");
                log.info("Using Docker Unix socket");
            } else {
                String windowsHost = "tcp://host.docker.internal:2375";
                System.setProperty("DOCKER_HOST", windowsHost);
                log.info("Using Windows/WSL Docker endpoint: {}", windowsHost);
            }
        }
        return DockerClientBuilder.getInstance().build();
    }

    /**
     * 创建带资源、网络和文件系统限制的执行容器。
     *
     * @param dockerClient       Docker 客户端
     * @param workingDirectory   本次请求的工作目录
     * @param languageDefinition 语言执行定义
     * @return 容器 ID
     */
    private String createContainer(DockerClient dockerClient, File workingDirectory,
                                   DockerLanguageDefinition languageDefinition) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(MEMORY_LIMIT_BYTES)
                .withMemorySwap(MEMORY_LIMIT_BYTES)
                .withNanoCPUs(CPU_LIMIT)
                .withPidsLimit(PID_LIMIT)
                .withReadonlyRootfs(true)
                .withBinds(new Bind(workingDirectory.getAbsolutePath(), new Volume(CONTAINER_WORK_DIR)))
                .withTmpFs(Collections.singletonMap("/tmp", "rw,nosuid,nodev,size=64m"));

        CreateContainerResponse response = dockerClient.createContainerCmd(languageDefinition.getImage())
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withWorkingDir(CONTAINER_WORK_DIR)
                .withEntrypoint("/bin/sh")
                .withCmd("-c", "while true; do sleep 3600; done")
                .withEnv(languageDefinition.getEnvironment())
                .withTty(false)
                .exec();
        log.debug("Docker container created: {}", response.getId());
        return response.getId();
    }

    /**
     * 在容器中执行单条命令，同时采集输出、耗时和内存峰值。
     *
     * @param dockerClient  Docker 客户端
     * @param containerId   容器 ID
     * @param command       执行命令
     * @param timeoutMillis 当前阶段允许的最长时间
     * @return 命令执行结果
     * @throws InterruptedException 等待命令时线程被中断
     * @throws IOException          关闭输出回调或转换输出失败
     */
    private DockerCommandResult executeCommand(DockerClient dockerClient, String containerId, String[] command,
                                               long timeoutMillis) throws InterruptedException, IOException {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        LimitedByteArrayOutputStream stdout = new LimitedByteArrayOutputStream(OUTPUT_LIMIT_BYTES);
        LimitedByteArrayOutputStream stderr = new LimitedByteArrayOutputStream(OUTPUT_LIMIT_BYTES);
        ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);
        // 命令开始前订阅容器统计流，避免漏掉短进程的内存峰值。
        StatsCollector statsCollector = startStatsCollection(dockerClient, containerId);
        statsCollector.awaitFirstSample();
        long startedAt = System.nanoTime();
        boolean completed;

        try {
            completed = dockerClient.execStartCmd(exec.getId())
                    .exec(callback)
                    .awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            try {
                callback.close();
            } finally {
                statsCollector.close();
            }
        }

        long timeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (!completed) {
            killContainer(dockerClient, containerId);
            return new DockerCommandResult(null, toUtf8(stdout), toUtf8(stderr), timeMillis,
                    statsCollector.getMaxMemoryKb(), true, stdout.isTruncated() || stderr.isTruncated());
        }

        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new DockerCommandResult(exitCode, toUtf8(stdout), toUtf8(stderr), timeMillis,
                statsCollector.getMaxMemoryKb(), false, stdout.isTruncated() || stderr.isTruncated());
    }

    /**
     * 订阅容器统计流并持续记录最大内存占用。
     *
     * @param dockerClient Docker 客户端
     * @param containerId  容器 ID
     * @return 资源统计收集器
     */
    private StatsCollector startStatsCollection(DockerClient dockerClient, String containerId) {
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        AtomicLong maxMemoryBytes = new AtomicLong(0L);
        CountDownLatch firstSample = new CountDownLatch(1);
        ResultCallback<Statistics> callback = new ResultCallback<Statistics>() {
            @Override
            public void onStart(Closeable closeable) {
            }

            @Override
            public void onNext(Statistics statistics) {
                if (statistics.getMemoryStats() != null && statistics.getMemoryStats().getUsage() != null) {
                    maxMemoryBytes.accumulateAndGet(statistics.getMemoryStats().getUsage(), Math::max);
                }
                firstSample.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                firstSample.countDown();
                log.debug("Docker memory statistics stream ended with an error", throwable);
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void close() {
            }
        };
        statsCmd.exec(callback);
        return new StatsCollector(statsCmd, callback, maxMemoryBytes, firstSample);
    }

    /**
     * 计算阶段超时与请求剩余时间中的较小值。
     *
     * @param deadlineNanos      请求截止时间
     * @param phaseTimeoutMillis 当前阶段超时
     * @return 当前阶段实际可用毫秒数
     */
    private long remainingTimeout(long deadlineNanos, long phaseTimeoutMillis) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.min(phaseTimeoutMillis, Math.max(remainingMillis, 0L));
    }

    /**
     * 创建经过诊断清洗的编译失败结果。
     *
     * @param outputs            已产生的输出
     * @param diagnostic         原始诊断
     * @param languageDefinition 语言执行定义
     * @param memoryKb           内存峰值，单位 KB
     * @param timeMillis         耗时，单位 ms
     * @return 编译失败结果
     */
    private SandboxExecutionResult compileFailure(List<String> outputs, String diagnostic,
                                                  DockerLanguageDefinition languageDefinition,
                                                  long memoryKb, long timeMillis) {
        return SandboxExecutionResult.failure(
                SandboxFailureType.COMPILE_ERROR,
                outputs,
                DiagnosticSanitizer.format("Compile Error", diagnostic, languageDefinition.getSourceFileName()),
                memoryKb,
                timeMillis
        );
    }

    /**
     * 创建经过诊断清洗的运行失败结果。
     *
     * @param outputs            已产生的输出
     * @param diagnostic         原始诊断
     * @param languageDefinition 语言执行定义
     * @param memoryKb           内存峰值，单位 KB
     * @param timeMillis         耗时，单位 ms
     * @return 运行失败结果
     */
    private SandboxExecutionResult runtimeFailure(List<String> outputs, String diagnostic,
                                                  DockerLanguageDefinition languageDefinition,
                                                  long memoryKb, long timeMillis) {
        return SandboxExecutionResult.failure(
                SandboxFailureType.RUNTIME_ERROR,
                outputs,
                DiagnosticSanitizer.format("Runtime Error", diagnostic, languageDefinition.getSourceFileName()),
                memoryKb,
                timeMillis
        );
    }

    /**
     * 创建不暴露内部异常细节的沙箱系统错误结果。
     *
     * @param outputs    已产生的输出
     * @param diagnostic 可返回给调用方的稳定错误说明
     * @return 系统错误结果
     */
    private SandboxExecutionResult systemFailure(List<String> outputs, String diagnostic) {
        return SandboxExecutionResult.failure(
                SandboxFailureType.SYSTEM_ERROR,
                outputs,
                "System Error\n" + diagnostic,
                0L,
                0L
        );
    }

    /**
     * 优先选择标准错误，其次选择标准输出作为失败诊断。
     *
     * @param result Docker 命令结果
     * @return 可用于错误响应的原始诊断
     */
    private String diagnosticOf(DockerCommandResult result) {
        if (StrUtil.isNotBlank(result.getStandardError())) {
            return result.getStandardError();
        }
        if (StrUtil.isNotBlank(result.getStandardOutput())) {
            return result.getStandardOutput();
        }
        return "Process exited with code " + result.getExitCode();
    }

    /**
     * 统一换行符并移除输出末尾的空行。
     *
     * @param output 标准输出
     * @return 规范化后的输出
     */
    private String normalizeOutput(String output) {
        return output.replace("\r\n", "\n").replaceAll("[\\r\\n]+$", "");
    }

    /**
     * 按 UTF-8 读取受限输出流。
     *
     * @param outputStream 输出流
     * @return UTF-8 字符串
     * @throws IOException 字符集转换失败
     */
    private String toUtf8(LimitedByteArrayOutputStream outputStream) throws IOException {
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * 终止已经超时的容器。
     *
     * @param dockerClient Docker 客户端
     * @param containerId  容器 ID
     */
    private void killContainer(DockerClient dockerClient, String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.warn("Failed to kill timed-out Docker container: {}", containerId, e);
        }
    }

    /**
     * 强制删除本次请求创建的容器。
     *
     * @param dockerClient Docker 客户端
     * @param containerId  容器 ID
     */
    private void removeContainer(DockerClient dockerClient, String containerId) {
        if (dockerClient == null || containerId == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("Failed to remove Docker container: {}", containerId, e);
        }
    }

    /**
     * 关闭 Docker 客户端连接。
     *
     * @param dockerClient Docker 客户端
     */
    private void closeDockerClient(DockerClient dockerClient) {
        if (dockerClient == null) {
            return;
        }
        try {
            dockerClient.close();
        } catch (IOException e) {
            log.warn("Failed to close Docker client", e);
        }
    }

    /**
     * 删除本次请求产生的源文件和编译产物。
     *
     * @param sourceFile 本次请求的源文件
     */
    private void deleteWorkingDirectory(File sourceFile) {
        if (sourceFile == null || sourceFile.getParentFile() == null) {
            return;
        }
        if (!FileUtil.del(sourceFile.getParentFile())) {
            log.warn("Failed to delete sandbox working directory: {}", sourceFile.getParent());
        }
    }

    /**
     * 管理 Docker 统计订阅，并提供内存峰值。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class StatsCollector implements Closeable {

        private final StatsCmd statsCmd;

        private final ResultCallback<Statistics> callback;

        private final AtomicLong maxMemoryBytes;

        private final CountDownLatch firstSample;

        /**
         * 短暂等待首个统计样本，降低短命令采集不到内存的概率。
         *
         * @throws InterruptedException 等待被中断
         */
        private void awaitFirstSample() throws InterruptedException {
            firstSample.await(1, TimeUnit.SECONDS);
        }

        /**
         * 获取采集到的最大内存占用。
         *
         * @return 内存峰值，单位 KB
         */
        private long getMaxMemoryKb() {
            return maxMemoryBytes.get() / 1024L;
        }

        /**
         * 关闭统计回调和 Docker 统计命令。
         */
        @Override
        public void close() {
            try {
                callback.close();
            } catch (IOException e) {
                log.debug("Failed to close Docker stats callback", e);
            }
            try {
                statsCmd.close();
            } catch (Exception e) {
                log.debug("Failed to close Docker stats command", e);
            }
        }
    }
}
