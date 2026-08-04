package com.hjl.ojcodesandbox.sandbox.result;

import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.ExecuteStatusEnum;
import com.hjl.ojcodesandbox.model.JudgeInfo;

import java.util.ArrayList;

/**
 * 将沙箱内部结果转换为稳定的对外响应结构。
 */
public final class SandboxResponseFactory {

    /**
     * 工具类不允许实例化。
     */
    private SandboxResponseFactory() {
    }

    /**
     * 将内部执行结果映射为接口响应。
     *
     * @param result 沙箱内部执行结果
     * @return 对外代码执行响应
     */
    public static ExecuteCodeResponse from(SandboxExecutionResult result) {
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setOutputList(new ArrayList<>(result.getOutputList()));
        response.setStatus(toStatus(result.getFailureType()));
        response.setMessage(toMessage(result.getFailureType()));

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(result.getDiagnostic());
        judgeInfo.setMemory(result.getMaxMemoryKb());
        judgeInfo.setTime(result.getMaxTimeMillis());
        response.setJudgeInfo(judgeInfo);
        return response;
    }

    /**
     * 创建尚未进入具体语言执行器时的系统错误响应。
     *
     * @param diagnostic 系统错误说明
     * @return 系统错误响应
     */
    public static ExecuteCodeResponse systemError(String diagnostic) {
        return from(SandboxExecutionResult.failure(
                SandboxFailureType.SYSTEM_ERROR,
                new ArrayList<>(),
                "System Error\n" + diagnostic,
                0L,
                0L
        ));
    }

    /**
     * 将内部失败类型映射为接口状态码。
     *
     * @param failureType 内部失败类型
     * @return 接口状态码
     */
    private static int toStatus(SandboxFailureType failureType) {
        switch (failureType) {
            case NONE:
                return ExecuteStatusEnum.ACCEPTED.getValue();
            case COMPILE_ERROR:
                return ExecuteStatusEnum.COMPILE_ERROR.getValue();
            case RUNTIME_ERROR:
                return ExecuteStatusEnum.RUNTIME_ERROR.getValue();
            default:
                return ExecuteStatusEnum.SYSTEM_ERROR.getValue();
        }
    }

    /**
     * 生成只描述沙箱执行流程的接口消息。
     *
     * @param failureType 内部失败类型
     * @return 接口流程消息
     */
    private static String toMessage(SandboxFailureType failureType) {
        switch (failureType) {
            case NONE:
                return "Execution completed";
            case COMPILE_ERROR:
                return "Compilation failed";
            case RUNTIME_ERROR:
                return "Execution failed";
            default:
                return "Sandbox error";
        }
    }
}
