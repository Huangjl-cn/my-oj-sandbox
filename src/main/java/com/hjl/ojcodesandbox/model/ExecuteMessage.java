package com.hjl.ojcodesandbox.model;

import lombok.Data;

/**
 * 进程执行信息
 */
@Data
public class ExecuteMessage {

    /**
     * 退出码：0-正常运行
     */
    private Integer exitValue;

    /**
     * 正常输出
     */
    private String message;

    /**
     * 异常输出
     */
    private String errorMessage;

    private Long time;

    private Long memory;

    @Override
    public String toString() {
        return String.format(
                "ExecuteMessage(exitValue=%d, message='%s', errorMessage='%s', time=%d, memory=%d)",
                exitValue, message, errorMessage, time, memory
        );
    }
}
