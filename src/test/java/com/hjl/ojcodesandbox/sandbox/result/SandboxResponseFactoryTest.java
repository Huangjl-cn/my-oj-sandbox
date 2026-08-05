package com.hjl.ojcodesandbox.sandbox.result;

import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.ExecuteStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxResponseFactoryTest {

    @Test
    void mapsSuccessfulOutputsAndResourceUnits() {
        SandboxExecutionResult result = SandboxExecutionResult.success(Arrays.asList("3", "7"), 1024L, 20L);

        ExecuteCodeResponse response = SandboxResponseFactory.from(result);

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Arrays.asList("3", "7"), response.getOutputList());
        assertEquals("Execution completed", response.getMessage());
        assertEquals("Accepted", response.getJudgeInfo().getMessage());
        assertEquals(1024L, response.getJudgeInfo().getMemory());
        assertEquals(20L, response.getJudgeInfo().getTime());
    }

    @Test
    void mapsCompileDiagnosticWithoutAddingOutputPlaceholders() {
        SandboxExecutionResult result = SandboxExecutionResult.failure(
                SandboxFailureType.COMPILE_ERROR,
                Collections.emptyList(),
                "Compile Error\nMain.java:3: error: ';' expected",
                512L,
                10L
        );

        ExecuteCodeResponse response = SandboxResponseFactory.from(result);

        assertEquals(ExecuteStatusEnum.COMPILE_ERROR.getValue(), response.getStatus());
        assertEquals(Collections.emptyList(), response.getOutputList());
        assertEquals("Compilation failed", response.getMessage());
        assertEquals("Compile Error\nMain.java:3: error: ';' expected", response.getJudgeInfo().getMessage());
    }

    @Test
    void mapsRuntimeAndSystemErrors() {
        ExecuteCodeResponse runtimeResponse = SandboxResponseFactory.from(SandboxExecutionResult.failure(
                SandboxFailureType.RUNTIME_ERROR,
                Arrays.asList("3"),
                "Runtime Error\nMain.java:4 ArithmeticException",
                2048L,
                30L
        ));
        ExecuteCodeResponse systemResponse = SandboxResponseFactory.systemError("Docker unavailable");

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), runtimeResponse.getStatus());
        assertEquals(Collections.singletonList("3"), runtimeResponse.getOutputList());
        assertEquals("Runtime Error\nMain.java:4 ArithmeticException",
                runtimeResponse.getJudgeInfo().getMessage());
        assertEquals(ExecuteStatusEnum.SYSTEM_ERROR.getValue(), systemResponse.getStatus());
        assertEquals("System Error\nDocker unavailable", systemResponse.getJudgeInfo().getMessage());
    }

    @Test
    void mapsTimeoutAsRuntimeError() {
        SandboxExecutionResult result = SandboxExecutionResult.failure(
                SandboxFailureType.RUNTIME_ERROR,
                Collections.singletonList(""),
                "Runtime Error\nExecution timed out after 8000 ms",
                4096L,
                8000L
        );

        ExecuteCodeResponse response = SandboxResponseFactory.from(result);

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertEquals("Runtime Error\nExecution timed out after 8000 ms", response.getJudgeInfo().getMessage());
        assertEquals(8000L, response.getJudgeInfo().getTime());
    }
}
