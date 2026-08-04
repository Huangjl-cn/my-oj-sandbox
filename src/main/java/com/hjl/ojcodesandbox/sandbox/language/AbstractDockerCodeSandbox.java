package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import com.hjl.ojcodesandbox.sandbox.result.SandboxExecutionResult;
import com.hjl.ojcodesandbox.sandbox.result.SandboxResponseFactory;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * 连接语言定义、Docker 执行引擎和统一响应模型的基础实现。
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractDockerCodeSandbox implements LanguageCodeSandbox {

    private final DockerExecutionEngine executionEngine;

    private final DockerLanguageDefinition languageDefinition;

    /**
     * 获取语言定义中声明的编程语言。
     *
     * @return 当前沙箱支持的语言
     */
    @Override
    public final QuestionSubmitLanguageEnum getLanguage() {
        return languageDefinition.getLanguage();
    }

    /**
     * 执行代码并将内部执行结果转换为对外响应。
     *
     * @param executeCodeRequest 代码执行请求
     * @return 统一的代码执行响应
     */
    @Override
    public final ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        SandboxExecutionResult result = executionEngine.execute(executeCodeRequest, languageDefinition);
        return SandboxResponseFactory.from(result);
    }
}
