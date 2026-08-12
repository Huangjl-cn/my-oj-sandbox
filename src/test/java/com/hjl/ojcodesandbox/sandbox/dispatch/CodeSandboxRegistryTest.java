package com.hjl.ojcodesandbox.sandbox.dispatch;

import com.hjl.ojcodesandbox.CodeSandbox;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import com.hjl.ojcodesandbox.sandbox.language.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeSandboxRegistryTest {

    @ParameterizedTest
    @EnumSource(QuestionSubmitLanguageEnum.class)
    void selectsDockerSandboxForEverySupportedLanguage(QuestionSubmitLanguageEnum language) {
        DockerExecutionEngine executionEngine = mock(DockerExecutionEngine.class);
        Map<QuestionSubmitLanguageEnum, Class<? extends CodeSandbox>> expectedTypes =
                new EnumMap<>(QuestionSubmitLanguageEnum.class);
        expectedTypes.put(QuestionSubmitLanguageEnum.JAVA, DockerJavaCodeSandbox.class);
        expectedTypes.put(QuestionSubmitLanguageEnum.CPLUSPLUS, DockerCppCodeSandbox.class);
        expectedTypes.put(QuestionSubmitLanguageEnum.GOLANG, DockerGoCodeSandbox.class);
        expectedTypes.put(QuestionSubmitLanguageEnum.PYTHON, DockerPythonCodeSandbox.class);
        expectedTypes.put(QuestionSubmitLanguageEnum.JAVASCRIPT, DockerJavaScriptCodeSandbox.class);

        CodeSandboxRegistry registry = new CodeSandboxRegistry(Arrays.asList(
                new DockerJavaCodeSandbox(executionEngine),
                new DockerCppCodeSandbox(executionEngine),
                new DockerGoCodeSandbox(executionEngine),
                new DockerPythonCodeSandbox(executionEngine),
                new DockerJavaScriptCodeSandbox(executionEngine)
        ));

        assertTrue(expectedTypes.get(language).isInstance(registry.getSandbox(language)));
    }
}
