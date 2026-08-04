package com.hjl.ojcodesandbox.sandbox.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSanitizerTest {

    @Test
    void keepsJavaCompileLocationAndSourceLine() {
        String diagnostic = "/app/Main.java:3: error: ';' expected\n"
                + "        System.out.println(1)\n"
                + "                             ^\n"
                + "1 error";

        String result = DiagnosticSanitizer.format("Compile Error", diagnostic, "Main.java");

        assertTrue(result.contains("Main.java:3: error"));
        assertTrue(result.contains("System.out.println(1)"));
        assertFalse(result.contains("/app/"));
    }

    @Test
    void removesJavaRuntimeLibraryFrames() {
        String diagnostic = "Exception in thread \"main\" java.lang.ArithmeticException: / by zero\n"
                + "\tat Main.main(Main.java:4)\n"
                + "\tat java.lang.reflect.Method.invoke(Method.java:498)";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "Main.java");

        assertTrue(result.contains("ArithmeticException"));
        assertTrue(result.contains("Main.java:4"));
        assertFalse(result.contains("reflect.Method"));
    }

    @Test
    void keepsPythonSourceLocationAndException() {
        String diagnostic = "Traceback (most recent call last):\n"
                + "  File \"/app/main.py\", line 2, in <module>\n"
                + "    print(1 / 0)\n"
                + "ZeroDivisionError: division by zero";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.py");

        assertTrue(result.contains("main.py\", line 2"));
        assertTrue(result.contains("print(1 / 0)"));
        assertTrue(result.contains("ZeroDivisionError"));
        assertFalse(result.contains("/app/"));
    }

    @Test
    void keepsCppCompileLocationAndDropsToolchainNoise() {
        String diagnostic = "In file included from /usr/include/c++/15/iostream:40:\n"
                + "/app/main.cpp:4:18: error: expected ';' before 'return'\n"
                + "    std::cout << 1\n"
                + "                  ^\n";

        String result = DiagnosticSanitizer.format("Compile Error", diagnostic, "main.cpp");

        assertTrue(result.contains("main.cpp:4:18: error"));
        assertTrue(result.contains("std::cout << 1"));
        assertFalse(result.contains("/usr/include"));
        assertFalse(result.contains("/app/"));
    }

    @Test
    void keepsGoSourceLocationAndPanic() {
        String diagnostic = "panic: runtime error: integer divide by zero\n"
                + "goroutine 1 [running]:\n"
                + "main.main()\n"
                + "\t/app/main.go:5 +0x9\n"
                + "runtime.main()\n"
                + "\t/usr/local/go/src/runtime/proc.go:285 +0x29d";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.go");

        assertTrue(result.contains("panic: runtime error"));
        assertTrue(result.contains("main.go:5"));
        assertFalse(result.contains("runtime/proc.go"));
        assertFalse(result.contains("/app/"));
    }

    @Test
    void keepsJavaScriptSourceLocationAndDropsNodeInternals() {
        String diagnostic = "ReferenceError: missingValue is not defined\n"
                + "    at Object.<anonymous> (/app/main.js:2:13)\n"
                + "    at Module._compile (node:internal/modules/cjs/loader:1730:14)";

        String result = DiagnosticSanitizer.format("Runtime Error", diagnostic, "main.js");

        assertTrue(result.contains("ReferenceError"));
        assertTrue(result.contains("main.js:2:13"));
        assertFalse(result.contains("node:internal"));
        assertFalse(result.contains("/app/"));
    }

    @Test
    void normalizesRelativeGoCompilePath() {
        String diagnostic = "# command-line-arguments\n./main.go:4:2: undefined: fmt.Prinln";

        String result = DiagnosticSanitizer.format("Compile Error", diagnostic, "main.go");

        assertTrue(result.contains("main.go:4:2"));
        assertFalse(result.contains("./main.go"));
    }
}
