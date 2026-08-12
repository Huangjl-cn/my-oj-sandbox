package com.hjl.ojcodesandbox.sandbox.result;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从编译器和运行时输出中提取与用户源文件相关的简洁诊断。
 */
public final class DiagnosticSanitizer {

    private static final int MAX_LINES = 20;

    private static final int MAX_CHARACTERS = 4096;

    /**
     * 匹配 JavaScript/Python 风格错误头，如 {@code RangeError: msg}、
     * {@code TypeError [ERR_INVALID_ARG_TYPE]: msg}，不区分具体错误类型。
     */
    private static final Pattern ERROR_HEADER_PATTERN = Pattern.compile(
            "[A-Za-z]+(?:Error|Exception|Warning)(?:\\[[^\\]]*\\])?: ");

    /**
     * 匹配 Go 栈帧定位行，如 {@code main.go:5 +0x17}。
     */
    private static final Pattern GO_FRAME_LOCATION_PATTERN = Pattern.compile(
            "^[\\w./-]+\\.go:\\d+(?: \\+0x[0-9a-f]+)?$");

    /**
     * 匹配 Go 栈帧函数名行，如 {@code main.main()}、{@code main.f(0x0)}。
     */
    private static final Pattern GO_FUNCTION_NAME_PATTERN = Pattern.compile(
            "^[A-Za-z_][\\w.]*\\(.*\\)$");

    /**
     * 匹配 Go panic/fatal 输出的 goroutine 上下文头，如 {@code goroutine 1 [running]:}。
     */
    private static final Pattern GO_GOROUTINE_HEADER_PATTERN = Pattern.compile(
            "^goroutine \\d+ \\[running\\]:$");

    /**
     * 工具类不允许实例化。
     */
    private DiagnosticSanitizer() {
    }

    /**
     * 清理诊断路径和内部栈，并限制返回内容长度。
     *
     * @param errorType      稳定的错误类型首行
     * @param diagnostic     原始编译或运行诊断
     * @param sourceFileName 用户源文件名
     * @return 可返回给调用方的诊断文本
     */
    public static String format(String errorType, String diagnostic, String sourceFileName) {
        if (StrUtil.isBlank(diagnostic)) {
            return errorType;
        }

        String[] lines = diagnostic.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> selectedLines = new ArrayList<>();
        int continuationLines = 0;
        // Go 两行式栈帧：函数名行（如 main.main()）与紧邻的定位行（如 main.go:5 +0x17）配对保留。
        String lastNonBlankLine = null;
        String lastNonBlankOriginal = null;

        for (String rawLine : lines) {
            String line = sanitizePath(rawLine, sourceFileName);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // 空行隔断相邻关系，避免续行预算泄漏到后续无关行（如 Node 版本页脚）。
                continuationLines = 0;
                lastNonBlankLine = null;
                lastNonBlankOriginal = null;
                continue;
            }
            if (isInternalStackFrame(trimmed, sourceFileName)
                    || isRuntimeLibraryLocation(trimmed, sourceFileName)
                    || isFooterNoise(trimmed)) {
                continue;
            }

            boolean referencesSource = line.contains(sourceFileName);
            boolean include = referencesSource || isTopLevelError(trimmed) || isErrorSummary(trimmed);
            // 源文件定位后保留少量相邻行，以包含源码片段和指示符。
            if (!include && continuationLines > 0 && !trimmed.startsWith("at ")) {
                include = true;
                continuationLines--;
            }
            if (referencesSource) {
                continuationLines = 2;
            }
            // Go 帧定位行命中时，回补其上一行的函数名，形成完整帧。
            if (include && GO_FRAME_LOCATION_PATTERN.matcher(trimmed).matches()
                    && lastNonBlankLine != null
                    && GO_FUNCTION_NAME_PATTERN.matcher(lastNonBlankLine).matches()
                    && (selectedLines.isEmpty()
                    || !selectedLines.get(selectedLines.size() - 1).equals(lastNonBlankOriginal))) {
                selectedLines.add(lastNonBlankOriginal);
            }
            if (include && (selectedLines.isEmpty()
                    || !selectedLines.get(selectedLines.size() - 1).equals(line))) {
                selectedLines.add(line);
                if (selectedLines.size() == MAX_LINES) {
                    break;
                }
            }
            lastNonBlankLine = trimmed;
            lastNonBlankOriginal = line;
        }

        if (selectedLines.isEmpty()) {
            for (String rawLine : lines) {
                if (StrUtil.isNotBlank(rawLine)) {
                    selectedLines.add(sanitizePath(rawLine, sourceFileName).trim());
                    break;
                }
            }
        }

        StringBuilder result = new StringBuilder(errorType);
        for (String line : selectedLines) {
            if (result.length() + line.length() + 1 > MAX_CHARACTERS) {
                result.append("\n...");
                break;
            }
            result.append('\n').append(line);
        }
        return result.toString();
    }

    /**
     * 移除容器工作目录和 Go 相对路径前缀。
     *
     * @param line           单行诊断
     * @param sourceFileName 用户源文件名
     * @return 清理后的诊断行
     */
    private static String sanitizePath(String line, String sourceFileName) {
        return line.replace("/app/" + sourceFileName, sourceFileName)
                .replace("\\app\\" + sourceFileName, sourceFileName)
                .replace("./" + sourceFileName, sourceFileName);
    }

    /**
     * 判断 JavaScript 或 Java 栈帧是否属于运行库内部实现。
     *
     * @param line           去除首尾空白的诊断行
     * @param sourceFileName 用户源文件名
     * @return 内部栈帧返回 {@code true}
     */
    private static boolean isInternalStackFrame(String line, String sourceFileName) {
        if (!line.startsWith("at ")) {
            return false;
        }
        return !line.contains(sourceFileName);
    }

    /**
     * 判断诊断行是否只引用系统运行库位置。
     *
     * @param line           去除首尾空白的诊断行
     * @param sourceFileName 用户源文件名
     * @return 运行库位置返回 {@code true}
     */
    private static boolean isRuntimeLibraryLocation(String line, String sourceFileName) {
        if (line.contains(sourceFileName)) {
            return false;
        }
        return line.contains("/usr/")
                || line.contains("\\usr\\")
                || line.contains("node:internal");
    }

    /**
     * 判断诊断行是否为各语言常见的顶层错误描述。
     *
     * @param line 去除首尾空白的诊断行
     * @return 顶层错误描述返回 {@code true}
     */
    private static boolean isTopLevelError(String line) {
        String lowerCaseLine = line.toLowerCase();
        return line.startsWith("Exception in thread")
                || lowerCaseLine.startsWith("panic:")
                || lowerCaseLine.startsWith("fatal error:")
                || lowerCaseLine.startsWith("error:")
                || lowerCaseLine.startsWith("terminate called")
                || line.startsWith("what():")
                || GO_GOROUTINE_HEADER_PATTERN.matcher(line).matches()
                || ERROR_HEADER_PATTERN.matcher(line).find();
    }

    /**
     * 判断诊断行是否为运行时的固定页脚噪音，如 Node.js 版本号。
     *
     * @param line 去除首尾空白的诊断行
     * @return 页脚噪音返回 {@code true}
     */
    private static boolean isFooterNoise(String line) {
        return line.startsWith("Node.js v");
    }

    /**
     * 判断诊断行是否为编译器错误数量汇总。
     *
     * @param line 去除首尾空白的诊断行
     * @return 错误或警告汇总行返回 {@code true}
     */
    private static boolean isErrorSummary(String line) {
        return line.matches("\\d+ (error|errors|warning|warnings)");
    }

    /**
     * 生成退出码对应的可读诊断，用于 stderr/stdout 均为空时的兜底。
     * 覆盖常见的 Linux 信号退出码（128 + 信号号）与 OOM 场景。
     *
     * @param exitCode    进程退出码
     * @param maxMemoryKb 采集到的内存峰值（KB），大于 0 时用于补充 OOM 诊断
     * @return 可读诊断文本
     */
    public static String exitCodeDiagnostic(long exitCode, long maxMemoryKb) {
        if (exitCode == 137L) {
            if (maxMemoryKb > 0L) {
                return "Process killed (exit 137) - memory usage peaked at "
                        + String.format("%.1f", maxMemoryKb / 1024.0)
                        + " MB, likely exceeded the memory limit";
            }
            return "Process killed (exit 137) - likely memory limit exceeded";
        }
        if (exitCode == 139L) {
            return "Segmentation fault (exit 139)";
        }
        if (exitCode == 134L) {
            return "Aborted (exit 134)";
        }
        if (exitCode == 136L) {
            return "Floating point exception (exit 136)";
        }
        if (exitCode == 132L) {
            return "Illegal instruction (exit 132)";
        }
        return "Process exited with code " + exitCode;
    }
}
