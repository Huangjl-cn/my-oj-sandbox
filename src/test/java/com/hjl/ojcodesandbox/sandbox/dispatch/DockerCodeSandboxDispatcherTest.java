package com.hjl.ojcodesandbox.sandbox.dispatch;

import com.hjl.ojcodesandbox.CodeSandbox;
import com.hjl.ojcodesandbox.model.ExecuteCaseRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.ExecuteStatusEnum;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerCodeSandboxDispatcherTest {

    @Test
    void delegatesToRegisteredLanguageSandbox() {
        CodeSandboxRegistry registry = mock(CodeSandboxRegistry.class);
        CodeSandbox javaSandbox = mock(CodeSandbox.class);
        DockerCodeSandboxDispatcher dispatcher = new DockerCodeSandboxDispatcher(registry);
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .language("java")
                .code("public class Main {}")
                .cases(Collections.singletonList(ExecuteCaseRequest.builder()
                        .args(Arrays.asList("1", "2"))
                        .build()))
                .build();
        ExecuteCodeResponse expectedResponse = new ExecuteCodeResponse();
        when(registry.getSandbox(QuestionSubmitLanguageEnum.JAVA)).thenReturn(javaSandbox);
        when(javaSandbox.executeCode(request)).thenReturn(expectedResponse);

        ExecuteCodeResponse actualResponse = dispatcher.executeCode(request);

        assertSame(expectedResponse, actualResponse);
        verify(javaSandbox).executeCode(request);
    }

    @Test
    void rejectsUnsupportedLanguageWithStructuredSystemError() {
        DockerCodeSandboxDispatcher dispatcher = new DockerCodeSandboxDispatcher(mock(CodeSandboxRegistry.class));
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .language("ruby")
                .code("puts 1")
                .cases(Collections.singletonList(ExecuteCaseRequest.builder()
                        .args(Arrays.asList("", "1"))
                        .build()))
                .build();

        ExecuteCodeResponse response = dispatcher.executeCode(request);

        assertEquals(ExecuteStatusEnum.SYSTEM_ERROR.getValue(), response.getStatus());
        assertEquals(0, response.getOutputList().size());
        assertEquals("Sandbox error", response.getMessage());
        assertEquals("System Error\nUnsupported language: ruby", response.getJudgeInfo().getMessage());
    }
}
