package com.hjl.ojcodesandbox.sandbox.docker;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 保存一次 Docker exec 命令的输出、资源用量和结束状态。
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
final class DockerCommandResult {

    private final Long exitCode;

    private final String standardOutput;

    private final String standardError;

    private final long timeMillis;

    private final long memoryKb;

    private final boolean timedOut;

    private final boolean outputTruncated;

}
