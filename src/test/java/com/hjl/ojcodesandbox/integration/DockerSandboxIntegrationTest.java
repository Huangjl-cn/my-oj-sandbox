package com.hjl.ojcodesandbox.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hjl.ojcodesandbox.model.ExecuteCaseRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.ExecuteStatusEnum;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.docker.DockerExecutionEngine;
import com.hjl.ojcodesandbox.sandbox.language.DockerLanguageDefinition;
import com.hjl.ojcodesandbox.sandbox.result.SandboxExecutionResult;
import com.hjl.ojcodesandbox.sandbox.result.SandboxFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在 Linux Docker 环境中验证五种语言的真实 HTTP 执行链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
class DockerSandboxIntegrationTest {

    private static final String AUTH_HEADER = "auth";

    private static final String AUTH_SECRET = "secretKey";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DockerExecutionEngine executionEngine;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 验证五种语言均能按输入顺序返回真实运行输出。
     *
     * @param language 请求语言
     * @param code 可执行源代码
     */
    @ParameterizedTest(name = "{0} executes successfully")
    @MethodSource("successfulPrograms")
    void executesEverySupportedLanguage(String language, String code) {
        ExecuteCodeResponse response = execute(language, code, Arrays.asList(
                executeCase("1", "2"),
                executeCase("3", "4")
        ));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Arrays.asList("3", "7"), response.getOutputList());
        assertNotNull(response.getJudgeInfo());
        assertTrue(response.getJudgeInfo().getMemory() >= 0L);
        assertTrue(response.getJudgeInfo().getTime() >= 0L);
    }

    /**
     * 验证 Java 和 C++ 镜像内置的 JSON 依赖可解析并序列化常用 OJ 类型。
     *
     * @param language 请求语言
     * @param code 使用镜像 JSON 依赖的源代码
     */
    @ParameterizedTest(name = "{0} supports bundled JSON types")
    @MethodSource("jsonDependencyPrograms")
    void supportsBundledJsonDependencies(String language, String code) {
        ExecuteCodeResponse response = execute(language, code, Arrays.asList(
                executeCase("intArray", "[1,2,3]"),
                executeCase("intMatrix", "[[1,2],[3,4]]"),
                executeCase("stringArray", "[\"hello world\",\"\"]"),
                executeCase("string", "\"hello world\"")
        ));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Arrays.asList(
                "[1,2,3]",
                "[[1,2],[3,4]]",
                "[\"hello world\",\"\"]",
                "\"hello world\""
        ), response.getOutputList());
    }

    /**
     * 验证 Go、Python 和 JavaScript 镜像自带的 JSON 能力可处理常用 OJ 类型。
     *
     * @param language 请求语言
     * @param code 使用语言标准 JSON 能力的源代码
     */
    @ParameterizedTest(name = "{0} supports standard JSON types")
    @MethodSource("standardJsonPrograms")
    void supportsStandardJsonLibraries(String language, String code) {
        ExecuteCodeResponse response = execute(language, code, Arrays.asList(
                executeCase("intArray", "[1,2,3]"),
                executeCase("intMatrix", "[[1,2],[3,4]]"),
                executeCase("stringArray", "[\"hello world\",\"\"]"),
                executeCase("string", "\"hello world\"")
        ));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus(),
                response.getJudgeInfo() == null ? "missing judgeInfo" : response.getJudgeInfo().getMessage());
        assertEquals(Arrays.asList(
                "[1,2,3]",
                "[[1,2],[3,4]]",
                "[\"hello world\",\"\"]",
                "\"hello world\""
        ), response.getOutputList());
    }

    /**
     * 验证五种语言的语法或编译错误均返回源码定位。
     *
     * @param language 请求语言
     * @param code 包含编译错误的源代码
     * @param sourceFileName 预期出现的源文件名
     */
    @ParameterizedTest(name = "{0} reports compile diagnostics")
    @MethodSource("compileErrorPrograms")
    void reportsCompileErrorsForEveryLanguage(String language, String code, String sourceFileName) {
        ExecuteCodeResponse response = execute(language, code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.COMPILE_ERROR.getValue(), response.getStatus());
        assertTrue(response.getOutputList().isEmpty());
        assertTrue(response.getJudgeInfo().getMessage().startsWith("Compile Error"));
        assertTrue(response.getJudgeInfo().getMessage().contains(sourceFileName));
    }

    /**
     * 验证五种语言的非零退出均返回运行错误诊断。
     *
     * @param language 请求语言
     * @param code 运行时失败的源代码
     */
    @ParameterizedTest(name = "{0} reports runtime diagnostics")
    @MethodSource("runtimeErrorPrograms")
    void reportsRuntimeErrorsForEveryLanguage(String language, String code) {
        ExecuteCodeResponse response = execute(language, code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertTrue(response.getJudgeInfo().getMessage().startsWith("Runtime Error"));
        assertTrue(response.getJudgeInfo().getMessage().length() > "Runtime Error".length());
    }

    /**
     * 验证无限循环会在沙箱硬超时内被终止。
     */
    @Test
    void terminatesTimedOutProgram() {
        ExecuteCodeResponse response = execute(
                "javascript",
                "while (true) {}",
                Collections.singletonList(executeCase())
        );

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertTrue(response.getJudgeInfo().getMessage().contains("Execution timed out after 8000 ms"));
        assertTrue(response.getJudgeInfo().getTime() >= 7_000L);
    }

    /**
     * 验证运行失败后不会为未执行用例补充输出占位值。
     */
    @Test
    void stopsAfterRuntimeFailureWithoutPaddingOutputs() {
        String code = "import sys\n"
                + "value = int(sys.argv[1])\n"
                + "if value == 2:\n"
                + "    raise RuntimeError('stop')\n"
                + "sys.stdout.write(str(value))";

        ExecuteCodeResponse response = execute("python", code, Arrays.asList(
                executeCase("1"),
                executeCase("2"),
                executeCase("3")
        ));

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertEquals(2, response.getOutputList().size());
        assertEquals("1", response.getOutputList().get(0));
    }

    /**
     * 验证用例结束后后台子进程和临时文件不会影响下一个用例。
     */
    @Test
    void isolatesProcessesAndTemporaryFilesBetweenInputs() {
        String code = "import os, subprocess, sys, time\n"
                + "if sys.argv[1] == 'first':\n"
                + "    subprocess.Popen(['sh', '-c', 'sleep 1; touch /tmp/leaked'], "
                + "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)\n"
                + "    open('/tmp/marker', 'w').close()\n"
                + "    try:\n"
                + "        open('/app/work-marker', 'w').close()\n"
                + "    except OSError:\n"
                + "        pass\n"
                + "    print('first', end='')\n"
                + "else:\n"
                + "    time.sleep(1.5)\n"
                + "    print(os.path.exists('/tmp/marker') or os.path.exists('/tmp/leaked') "
                + "or os.path.exists('/app/work-marker'), end='')";

        ExecuteCodeResponse response = execute("python", code, Arrays.asList(
                executeCase("first"),
                executeCase("second")
        ));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Arrays.asList("first", "False"), response.getOutputList());
    }

    /**
     * 验证资源统计以 KB 和 ms 返回合理的非零数值。
     */
    @Test
    void reportsMemoryInKilobytesAndTimeInMilliseconds() {
        String code = "import sys, time\n"
                + "data = bytearray(int(sys.argv[1]) * 1024 * 1024)\n"
                + "time.sleep(1.5)\n"
                + "print(len(data), end='')";

        ExecuteCodeResponse response = execute("python", code,
                Collections.singletonList(executeCase("16")));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Collections.singletonList("16777216"), response.getOutputList());
        assertTrue(response.getJudgeInfo().getMemory() >= 8L * 1024L);
        assertTrue(response.getJudgeInfo().getTime() >= 1_000L);
    }

    /**
     * 验证编译阶段的高内存占用不会进入运行阶段峰值。
     */
    @Test
    void excludesCompilationMemoryFromRunPeak() {
        DockerLanguageDefinition definition = new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.PYTHON,
                "python:3.14-alpine",
                "main.py",
                new String[]{"python", "-c",
                        "import time; data = bytearray(96 * 1024 * 1024); time.sleep(0.2)"},
                new String[]{"python", "main.py"}
        );
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("print('ok', end='')")
                .language("python")
                .cases(Collections.singletonList(executeCase()))
                .build();

        SandboxExecutionResult result = executionEngine.execute(request, definition);

        assertEquals(SandboxFailureType.NONE, result.getFailureType());
        assertEquals(Collections.singletonList("ok"), result.getOutputList());
        assertTrue(result.getMaxMemoryKb() > 0L);
        assertTrue(result.getMaxMemoryKb() < 80L * 1024L);
    }

    /**
     * 验证 Docker 镜像不存在时返回沙箱系统错误。
     */
    @Test
    void reportsMissingImageAsSystemError() {
        DockerLanguageDefinition definition = new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.JAVA,
                "oj-code-sandbox/missing-image:integration-test",
                "Main.java",
                new String[]{"javac", "Main.java"},
                new String[]{"java", "Main"}
        );
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("public class Main {}")
                .language("java")
                .cases(Collections.emptyList())
                .build();

        SandboxExecutionResult result = executionEngine.execute(request, definition);

        assertEquals(SandboxFailureType.SYSTEM_ERROR, result.getFailureType());
        assertTrue(result.getDiagnostic().contains("Required Docker image is unavailable"));
    }

    /**
     * 验证编译命令超过阶段上限时返回编译超时。
     */
    @Test
    void terminatesTimedOutCompilation() {
        DockerLanguageDefinition definition = new DockerLanguageDefinition(
                QuestionSubmitLanguageEnum.PYTHON,
                "python:3.14-alpine",
                "main.py",
                new String[]{"sh", "-c", "sleep 11"},
                new String[]{"python", "main.py"}
        );
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code("print('ok')")
                .language("python")
                .cases(Collections.singletonList(executeCase()))
                .build();

        SandboxExecutionResult result = executionEngine.execute(request, definition);

        assertEquals(SandboxFailureType.COMPILE_ERROR, result.getFailureType());
        assertTrue(result.getDiagnostic().contains("Compilation timed out"));
    }

    /**
     * 验证程序输出超过一 MiB 时终止执行并返回输出上限错误。
     */
    @Test
    void enforcesOutputLimit() {
        String code = "import sys\nsys.stdout.write('x' * (1024 * 1024 + 1024))";

        ExecuteCodeResponse response = execute("python", code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertTrue(response.getJudgeInfo().getMessage().contains("Output Limit Exceeded"));
    }

    /**
     * 验证执行容器不能访问外部网络。
     */
    @Test
    void blocksExternalNetworkAccess() {
        String code = "import socket\nsocket.create_connection(('1.1.1.1', 53), timeout=1)";

        ExecuteCodeResponse response = execute("python", code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertTrue(response.getJudgeInfo().getMessage().startsWith("Runtime Error"));
    }

    /**
     * 验证容器根文件系统保持只读。
     */
    @Test
    void keepsContainerRootFilesystemReadOnly() {
        String code = "with open('/sandbox-write-test', 'w') as file:\n    file.write('blocked')";

        ExecuteCodeResponse response = execute("python", code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.RUNTIME_ERROR.getValue(), response.getStatus());
        assertTrue(response.getJudgeInfo().getMessage().startsWith("Runtime Error"));
    }

    /**
     * 验证结构化参数在空格、空串、JSON 文本和 shell 元字符处保持边界。
     */
    @Test
    void preservesStructuredArgumentBoundariesWithoutShellInterpretation() {
        String code = "import json, sys\n"
                + "sys.stdout.write(json.dumps(sys.argv[1:], separators=(',', ':')))";

        ExecuteCodeResponse response = execute("python", code, Arrays.asList(
                executeCase("hello world", ""),
                executeCase("[1,2,3]", "4"),
                executeCase("[[1,2],[3,4]]"),
                executeCase("& calc", "$(command)")
        ));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Arrays.asList(
                "[\"hello world\",\"\"]",
                "[\"[1,2,3]\",\"4\"]",
                "[\"[[1,2],[3,4]]\"]",
                "[\"& calc\",\"$(command)\"]"
        ), response.getOutputList());
    }

    /**
     * 验证标准输出的空格和换行不会被沙箱规范化。
     */
    @Test
    void returnsRawStandardOutput() {
        String code = "import sys\nsys.stdout.write('  leading\\ntrailing  \\n')";

        ExecuteCodeResponse response = execute("python", code, Collections.singletonList(executeCase()));

        assertEquals(ExecuteStatusEnum.ACCEPTED.getValue(), response.getStatus());
        assertEquals(Collections.singletonList("  leading\ntrailing  \n"), response.getOutputList());
    }

    /**
     * 通过真实 HTTP 接口执行代码并返回响应体。
     *
     * @param language 请求语言
     * @param code 用户源代码
     * @param cases 结构化执行用例
     * @return 代码执行响应
     */
    private ExecuteCodeResponse execute(String language, String code, java.util.List<ExecuteCaseRequest> cases) {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .language(language)
                .code(code)
                .cases(cases)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AUTH_HEADER, AUTH_SECRET);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/executeCode",
                new HttpEntity<>(request, headers),
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Response body: " + response.getBody());
        assertNotNull(response.getBody());
        try {
            return objectMapper.readValue(response.getBody(), ExecuteCodeResponse.class);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Invalid executeCode response: " + response.getBody(), e);
        }
    }

    private static ExecuteCaseRequest executeCase(String... args) {
        return ExecuteCaseRequest.builder()
                .args(Arrays.asList(args))
                .build();
    }

    /**
     * 提供五种语言的加法程序。
     *
     * @return 成功程序参数
     */
    private static Stream<Arguments> successfulPrograms() {
        return Stream.of(
                Arguments.of("java", "public class Main { public static void main(String[] args) { "
                        + "System.out.print(Integer.parseInt(args[0]) + Integer.parseInt(args[1])); } }"),
                Arguments.of("cpp", "#include <cstdlib>\n#include <iostream>\n"
                        + "int main(int argc, char* argv[]) { std::cout << std::atoi(argv[1]) + std::atoi(argv[2]); }"),
                Arguments.of("go", "package main\nimport (\n\"fmt\"\n\"os\"\n\"strconv\"\n)\n"
                        + "func main() { a, _ := strconv.Atoi(os.Args[1]); b, _ := strconv.Atoi(os.Args[2]); "
                        + "fmt.Print(a + b) }"),
                Arguments.of("python", "import sys\nsys.stdout.write(str(int(sys.argv[1]) + int(sys.argv[2])))"),
                Arguments.of("javascript", "process.stdout.write(String(Number(process.argv[2]) "
                        + "+ Number(process.argv[3])));")
        );
    }

    /**
     * 提供 Java Gson 和 C++ nlohmann/json 验收程序。
     *
     * @return JSON 依赖验收程序参数
     */
    private static Stream<Arguments> jsonDependencyPrograms() {
        String javaCode = "import com.google.gson.Gson;\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        Gson gson = new Gson();\n"
                + "        switch (args[0]) {\n"
                + "            case \"intArray\":\n"
                + "                System.out.print(gson.toJson(gson.fromJson(args[1], int[].class)));\n"
                + "                break;\n"
                + "            case \"intMatrix\":\n"
                + "                System.out.print(gson.toJson(gson.fromJson(args[1], int[][].class)));\n"
                + "                break;\n"
                + "            case \"stringArray\":\n"
                + "                System.out.print(gson.toJson(gson.fromJson(args[1], String[].class)));\n"
                + "                break;\n"
                + "            case \"string\":\n"
                + "                System.out.print(gson.toJson(gson.fromJson(args[1], String.class)));\n"
                + "                break;\n"
                + "            default:\n"
                + "                throw new IllegalArgumentException(args[0]);\n"
                + "        }\n"
                + "    }\n"
                + "}";
        String cppCode = "#include <iostream>\n"
                + "#include <string>\n"
                + "#include <vector>\n"
                + "#include <nlohmann/json.hpp>\n"
                + "int main(int argc, char* argv[]) {\n"
                + "    std::string type = argv[1];\n"
                + "    auto input = nlohmann::json::parse(argv[2]);\n"
                + "    if (type == \"intArray\") {\n"
                + "        std::cout << nlohmann::json(input.get<std::vector<int>>()).dump();\n"
                + "    } else if (type == \"intMatrix\") {\n"
                + "        std::cout << nlohmann::json(input.get<std::vector<std::vector<int>>>()).dump();\n"
                + "    } else if (type == \"stringArray\") {\n"
                + "        std::cout << nlohmann::json(input.get<std::vector<std::string>>()).dump();\n"
                + "    } else if (type == \"string\") {\n"
                + "        std::cout << nlohmann::json(input.get<std::string>()).dump();\n"
                + "    } else {\n"
                + "        return 2;\n"
                + "    }\n"
                + "}";
        return Stream.of(
                Arguments.of("java", javaCode),
                Arguments.of("cpp", cppCode)
        );
    }

    /**
     * 提供 Go、Python 和 JavaScript 标准 JSON 验收程序。
     *
     * @return 标准 JSON 验收程序参数
     */
    private static Stream<Arguments> standardJsonPrograms() {
        String goCode = "package main\n"
                + "import (\n"
                + "    \"encoding/json\"\n"
                + "    \"fmt\"\n"
                + "    \"os\"\n"
                + ")\n"
                + "func main() {\n"
                + "    var value interface{}\n"
                + "    if err := json.Unmarshal([]byte(os.Args[2]), &value); err != nil {\n"
                + "        panic(err)\n"
                + "    }\n"
                + "    output, err := json.Marshal(value)\n"
                + "    if err != nil {\n"
                + "        panic(err)\n"
                + "    }\n"
                + "    fmt.Print(string(output))\n"
                + "}";
        String pythonCode = "import json, sys\n"
                + "value = json.loads(sys.argv[2])\n"
                + "sys.stdout.write(json.dumps(value, ensure_ascii=False, separators=(',', ':')))";
        String javascriptCode = "const value = JSON.parse(process.argv[3]);\n"
                + "process.stdout.write(JSON.stringify(value));";
        return Stream.of(
                Arguments.of("go", goCode),
                Arguments.of("python", pythonCode),
                Arguments.of("javascript", javascriptCode)
        );
    }

    /**
     * 提供五种语言的编译错误程序。
     *
     * @return 编译错误程序参数
     */
    private static Stream<Arguments> compileErrorPrograms() {
        return Stream.of(
                Arguments.of("java", "public class Main { public static void main(String[] args) { int value = } }",
                        "Main.java"),
                Arguments.of("cpp", "int main() { return }", "main.cpp"),
                Arguments.of("go", "package main\nfunc main( {", "main.go"),
                Arguments.of("python", "def main(:\n    pass", "main.py"),
                Arguments.of("javascript", "const value = ;", "main.js")
        );
    }

    /**
     * 提供五种语言的运行错误程序。
     *
     * @return 运行错误程序参数
     */
    private static Stream<Arguments> runtimeErrorPrograms() {
        return Stream.of(
                Arguments.of("java", "public class Main { public static void main(String[] args) { "
                        + "throw new RuntimeException(\"boom\"); } }"),
                Arguments.of("cpp", "int main() { return 7; }"),
                Arguments.of("go", "package main\nfunc main() { panic(\"boom\") }"),
                Arguments.of("python", "raise RuntimeError('boom')"),
                Arguments.of("javascript", "throw new Error('boom');")
        );
    }
}
