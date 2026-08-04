package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import org.springframework.stereotype.Component;

/**
 * 使用 Go 1.25 编译并执行 Go 代码。
 */
@Component
public class DockerGoCodeSandbox extends AbstractDockerCodeSandbox {

    /**
     * 创建 Go Docker 沙箱。
     *
     * @param executionEngine Docker 执行引擎
     */
    public DockerGoCodeSandbox(DockerExecutionEngine executionEngine) {
        super(executionEngine, new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.GOLANG,
                "golang:1.25-alpine",
                "main.go",
                new String[]{"go", "build", "-o", "main", "main.go"},
                new String[]{"./main"},
                "GOCACHE=/tmp/go-build",
                "HOME=/tmp"
        ));
    }
}
