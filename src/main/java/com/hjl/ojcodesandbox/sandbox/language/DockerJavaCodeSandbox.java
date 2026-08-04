package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import org.springframework.stereotype.Component;

/**
 * 使用 Eclipse Temurin JDK 25 执行 Java 代码。
 */
@Component
public class DockerJavaCodeSandbox extends AbstractDockerCodeSandbox {

    /**
     * 创建 Java Docker 沙箱。
     *
     * @param executionEngine Docker 执行引擎
     */
    public DockerJavaCodeSandbox(DockerExecutionEngine executionEngine) {
        super(executionEngine, new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.JAVA,
                "eclipse-temurin:25-jdk-alpine",
                "Main.java",
                new String[]{"javac", "-encoding", "UTF-8", "Main.java"},
                new String[]{"java", "Main"}
        ));
    }
}
