package com.hjl.ojcodesandbox.sandbox.result;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 从编译器和运行时输出中提取与用户源文件相关的简洁诊断。
 */
public final class DiagnosticSanitizer {

    private static final int MAX_LINES = 20;

    private static final int MAX_CHARACTERS = 4096;

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

        for (String rawLine : lines) {
            String line = sanitizePath(rawLine, sourceFileName);
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || isInternalStackFrame(trimmed, sourceFileName)
                    || isRuntimeLibraryLocation(trimmed, sourceFileName)) {
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
            if (include && (selectedLines.isEmpty()
                    || !selectedLines.get(selectedLines.size() - 1).equals(line))) {
                selectedLines.add(line);
                if (selectedLines.size() == MAX_LINES) {
                    break;
                }
            }
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
                || lowerCaseLine.contains("syntaxerror:")
                || lowerCaseLine.contains("referenceerror:")
                || lowerCaseLine.contains("typeerror:")
                || lowerCaseLine.contains("nameerror:")
                || lowerCaseLine.contains("valueerror:")
                || lowerCaseLine.contains("zerodivisionerror:")
                || lowerCaseLine.contains("exception:")
                || lowerCaseLine.startsWith("error:")
                || lowerCaseLine.startsWith("fatal error:");
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
}
