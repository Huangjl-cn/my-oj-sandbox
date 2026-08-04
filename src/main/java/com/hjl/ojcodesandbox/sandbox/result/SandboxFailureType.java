package com.hjl.ojcodesandbox.sandbox.result;

/**
 * 沙箱内部执行结果类型，用于映射对外状态码。
 */
public enum SandboxFailureType {
    NONE,
    COMPILE_ERROR,
    RUNTIME_ERROR,
    SYSTEM_ERROR
}
