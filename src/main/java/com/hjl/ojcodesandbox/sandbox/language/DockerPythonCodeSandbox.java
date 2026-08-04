package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import org.springframework.stereotype.Component;

/**
 * 使用 Python 3.14 检查并执行 Python 代码。
 */
@Component
public class DockerPythonCodeSandbox extends AbstractDockerCodeSandbox {

    /**
     * 创建 Python Docker 沙箱。
     *
     * @param executionEngine Docker 执行引擎
     */
    public DockerPythonCodeSandbox(DockerExecutionEngine executionEngine) {
        super(executionEngine, new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.PYTHON,
                "python:3.14-alpine",
                "main.py",
                new String[]{"python", "-m", "py_compile", "main.py"},
                new String[]{"python", "main.py"}
        ));
    }
}
