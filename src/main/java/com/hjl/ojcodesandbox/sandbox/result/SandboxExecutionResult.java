package com.hjl.ojcodesandbox.sandbox.result;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保存沙箱内部执行结果，并对输出列表提供只读视图。
 */
@Getter
public final class SandboxExecutionResult {

    private final SandboxFailureType failureType;

    private final List<String> outputList;

    private final String diagnostic;

    private final long maxMemoryKb;

    private final long maxTimeMillis;

    /**
     * 创建不可变的内部执行结果。
     *
     * @param failureType   失败类型
     * @param outputList    已产生的输出
     * @param diagnostic    判题诊断
     * @param maxMemoryKb   最大内存，单位 KB
     * @param maxTimeMillis 最大耗时，单位 ms
     */
    private SandboxExecutionResult(SandboxFailureType failureType, List<String> outputList, String diagnostic,
                                   long maxMemoryKb, long maxTimeMillis) {
        this.failureType = failureType;
        this.outputList = Collections.unmodifiableList(new ArrayList<>(outputList));
        this.diagnostic = diagnostic;
        this.maxMemoryKb = maxMemoryKb;
        this.maxTimeMillis = maxTimeMillis;
    }

    /**
     * 创建执行成功结果。
     *
     * @param outputList    按输入顺序排列的输出
     * @param maxMemoryKb   所有用例中的最大内存，单位 KB
     * @param maxTimeMillis 所有用例中的最大耗时，单位 ms
     * @return 成功结果
     */
    public static SandboxExecutionResult success(List<String> outputList, long maxMemoryKb, long maxTimeMillis) {
        return new SandboxExecutionResult(SandboxFailureType.NONE, outputList, "Accepted", maxMemoryKb, maxTimeMillis);
    }

    /**
     * 创建指定类型的失败结果。
     *
     * @param failureType   失败类型
     * @param outputList    失败前已经产生的输出
     * @param diagnostic    可返回给调用方的诊断
     * @param maxMemoryKb   最大内存，单位 KB
     * @param maxTimeMillis 最大耗时，单位 ms
     * @return 失败结果
     */
    public static SandboxExecutionResult failure(SandboxFailureType failureType, List<String> outputList,
                                                 String diagnostic, long maxMemoryKb, long maxTimeMillis) {
        return new SandboxExecutionResult(failureType, outputList, diagnostic, maxMemoryKb, maxTimeMillis);
    }

}
