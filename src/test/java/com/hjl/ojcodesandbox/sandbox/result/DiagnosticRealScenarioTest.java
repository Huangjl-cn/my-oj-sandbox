package com.hjl.ojcodesandbox.sandbox.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实容器捕获输出（与沙箱一致的镜像/命令/512MB 限制）的端到端诊断过滤回归测试。
 * 每个场景内嵌完整原始 stderr，验证关键信息保留、噪音剔除。
 */
class DiagnosticRealScenarioTest {

    @Test
    void nodeStackOverflowKeepsRangeErrorAndDropsVersionFooter() {
        String diagnostic = "/app/main.js:2\n"
                + "    return sortedArrayToBST(nums);  // 无限递归，无终止条件\n"
                + "    ^\n"
                + "\n"
                + "RangeError: Maximum call stack size exceeded\n"
                + "    at sortedArrayToBST (/app/main.js:2:5)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "    at sortedArrayToBST (/app/main.js:2:12)\n"
                + "\n"
                + "Node.js v24.19.0";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.js");

        assertTrue(result.contains("RangeError: Maximum call stack size exceeded"));
        assertTrue(result.contains("at sortedArrayToBST (main.js:2:5)"));
        assertTrue(result.contains("at sortedArrayToBST (main.js:2:12)"));
        assertFalse(result.contains("Node.js v"));
    }

    @Test
    void nodeReferenceErrorKeepsErrorHeaderAndDropsInternalsAndFooter() {
        String diagnostic = "/app/main.js:1\n"
                + "console.log(missingValue);\n"
                + "            ^\n"
                + "\n"
                + "ReferenceError: missingValue is not defined\n"
                + "    at Object.<anonymous> (/app/main.js:1:13)\n"
                + "    at Module._compile (node:internal/modules/cjs/loader:1872:14)\n"
                + "    at Object..js (node:internal/modules/cjs/loader:2003:10)\n"
                + "    at Module.load (node:internal/modules/cjs/loader:1594:32)\n"
                + "    at Module._load (node:internal/modules/cjs/loader:1396:12)\n"
                + "    at wrapModuleLoad (node:internal/modules/cjs/loader:255:19)\n"
                + "    at Module.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:154:5)\n"
                + "    at node:internal/main/run_main_module:33:47\n"
                + "\n"
                + "Node.js v24.19.0";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.js");

        assertTrue(result.contains("ReferenceError: missingValue is not defined"));
        assertTrue(result.contains("main.js:1:13"));
        assertFalse(result.contains("node:internal"));
        assertFalse(result.contains("Node.js v"));
    }

    @Test
    void pythonRecursionErrorKeepsErrorLineAndFoldedRepeatedFrame() {
        String diagnostic = "Traceback (most recent call last):\n"
                + "  File \"/app/main.py\", line 3, in <module>\n"
                + "    f(0)\n"
                + "    ~^^^\n"
                + "  File \"/app/main.py\", line 2, in f\n"
                + "    return f(n + 1)\n"
                + "  File \"/app/main.py\", line 2, in f\n"
                + "    return f(n + 1)\n"
                + "  File \"/app/main.py\", line 2, in f\n"
                + "    return f(n + 1)\n"
                + "  [Previous line repeated 996 more times]\n"
                + "RecursionError: maximum recursion depth exceeded";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.py");

        assertTrue(result.contains("RecursionError: maximum recursion depth exceeded"));
        assertTrue(result.contains("main.py\", line 3"));
        assertTrue(result.contains("[Previous line repeated 996 more times]"));
    }

    @Test
    void goPanicKeepsFunctionNameFrameAndLocation() {
        String diagnostic = "panic: runtime error: index out of range [10] with length 3\n"
                + "\n"
                + "goroutine 1 [running]:\n"
                + "main.main()\n"
                + "\t/app/main.go:5 +0x17";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.go");

        assertTrue(result.contains("panic: runtime error: index out of range [10] with length 3"));
        assertTrue(result.contains("goroutine 1 [running]:"));
        assertTrue(result.contains("main.main()"));
        assertTrue(result.contains("main.go:5"));
    }

    @Test
    void cppUncaughtExceptionKeepsTerminateAndWhatMessage() {
        String diagnostic = "terminate called after throwing an instance of 'std::runtime_error'\n"
                + "  what():  boom";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.cpp");

        assertTrue(result.contains("terminate called after throwing an instance of 'std::runtime_error'"));
        assertTrue(result.contains("what():  boom"));
    }

    @Test
    void cppCompileErrorKeepsLocationAndLineNumberedSource() {
        String diagnostic = "main.cpp: In function 'int main()':\n"
                + "main.cpp:3:19: error: expected ';' before 'return'\n"
                + "    3 |     std::cout << 1\n"
                + "      |                   ^\n"
                + "      |                   ;\n"
                + "    4 |     return 0;\n"
                + "      |     ~~~~~~";

        String result = DiagnosticSanitizer.format("Compile Error", diagnostic, "main.cpp");

        assertTrue(result.contains("main.cpp:3:19: error"));
        assertTrue(result.contains("std::cout << 1"));
        assertTrue(result.contains("|"));
    }

    @Test
    void goCompileErrorDropsPackageHeaderLine() {
        String diagnostic = "# command-line-arguments\n"
                + "./main.go:4:5: undefined: fmt";

        String result = DiagnosticSanitizer.format("Compile Error", diagnostic, "main.go");

        assertTrue(result.contains("main.go:4:5: undefined: fmt"));
        assertFalse(result.contains("# command-line-arguments"));
    }

    @Test
    void javaStackOverflowKeepsErrorHeaderAndDeduplicatesFrames() {
        StringBuilder diagnostic = new StringBuilder("Exception in thread \"main\" java.lang.StackOverflowError\n");
        for (int i = 0; i < 500; i++) {
            diagnostic.append("\tat Main.test(Main.java:6)\n");
        }

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic.toString(), "Main.java");

        assertTrue(result.contains("java.lang.StackOverflowError"));
        assertTrue(result.contains("Main.java:6"));
        // 500 个重复帧应被去重为极少量
        long frameCount = 0L;
        for (String resultLine : result.split("\n")) {
            if (resultLine.trim().startsWith("at Main.test")) {
                frameCount++;
            }
        }
        assertTrue(frameCount <= 2, "expected deduplicated frames, got " + frameCount);
    }
}
