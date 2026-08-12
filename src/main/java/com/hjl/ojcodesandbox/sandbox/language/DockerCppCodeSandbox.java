package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import org.springframework.stereotype.Component;

/**
 * 使用 GCC 15 编译并执行 C++17 代码。
 */
@Component
public class DockerCppCodeSandbox extends AbstractDockerCodeSandbox {

    /**
     * 创建 C++ Docker 沙箱。
     *
     * @param executionEngine Docker 执行引擎
     */
    public DockerCppCodeSandbox(DockerExecutionEngine executionEngine) {
        super(executionEngine, new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.CPLUSPLUS,
                "oj-code-sandbox/gcc-nlohmann-json:15-3.12.0",
                "main.cpp",
                new String[]{"g++", "main.cpp", "-O2", "-std=c++17", "-pipe", "-o", "main"},
                new String[]{"./main"},
                null
        ));
    }
}
