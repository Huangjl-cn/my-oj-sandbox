package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import org.springframework.stereotype.Component;

/**
 * 使用 Node.js 24 检查并执行 JavaScript 代码。
 */
@Component
public class DockerJavaScriptCodeSandbox extends AbstractDockerCodeSandbox {

    /**
     * 创建 JavaScript Docker 沙箱。
     *
     * @param executionEngine Docker 执行引擎
     */
    public DockerJavaScriptCodeSandbox(DockerExecutionEngine executionEngine) {
        super(executionEngine, new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.JAVASCRIPT,
                "node:24-alpine",
                "main.js",
                new String[]{"node", "--check", "main.js"},
                new String[]{"node", "main.js"},
                null
        ));
    }
}
