package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 描述一种语言使用的镜像、源文件和编译运行命令。
 */
public final class DockerLanguageDefinition {

    @Getter
    private final QuestionSubmitLanguageEnum language;

    @Getter
    private final String image;

    @Getter
    private final String sourceFileName;

    private final String[] compileCommand;

    private final String[] runCommand;

    /**
     * 编译缓存卷，格式为 "volumeName:/containerPath"；null 表示该语言不需要持久化编译缓存。
     * 编译容器会将缓存卷挂载到指定路径，使编译器产物（如 Go 的标准库构建缓存）跨提交复用。
     */
    @Getter
    private final String compileCacheVolume;

    private final String[] environment;

    /**
     * 创建带编译缓存卷的语言执行定义，并复制数组参数以避免外部修改内部配置。
     *
     * @param language           编程语言
     * @param image              Docker 镜像
     * @param sourceFileName     源文件名
     * @param compileCommand     编译命令
     * @param runCommand         基础运行命令
     * @param compileCacheVolume 编译缓存卷，格式 "volumeName:/containerPath"，无缓存需求时传 null
     * @param environment        容器环境变量
     */
    public DockerLanguageDefinition(QuestionSubmitLanguageEnum language, String image, String sourceFileName,
                                    String[] compileCommand, String[] runCommand, String compileCacheVolume,
                                    String... environment) {
        this.language = language;
        this.image = image;
        this.sourceFileName = sourceFileName;
        this.compileCommand = compileCommand.clone();
        this.runCommand = runCommand.clone();
        this.compileCacheVolume = compileCacheVolume;
        this.environment = environment.clone();
    }

    /**
     * 获取编译命令副本。
     *
     * @return 编译命令
     */
    public String[] getCompileCommand() {
        return compileCommand.clone();
    }

    /**
     * 将当前用例的独立参数原样追加到基础运行命令。
     *
     * @param args 当前测试用例参数
     * @return 完整运行命令
     */
    public String[] getRunCommand(List<String> args) {
        List<String> command = new ArrayList<>(runCommand.length + args.size());
        command.addAll(Arrays.asList(runCommand));
        command.addAll(args);
        return command.toArray(new String[0]);
    }

    /**
     * 获取容器环境变量副本。
     *
     * @return 环境变量数组
     */
    public String[] getEnvironment() {
        return environment.clone();
    }

    /**
     * 获取编译缓存卷的卷名。
     *
     * @return 卷名；无编译缓存卷时返回 null
     */
    public String getCompileCacheVolumeName() {
        return compileCacheVolume == null ? null : compileCacheVolume.substring(0, compileCacheVolume.indexOf(':'));
    }

    /**
     * 获取编译缓存卷的容器内挂载路径。
     *
     * @return 容器内路径；无编译缓存卷时返回 null
     */
    public String getCompileCacheVolumePath() {
        return compileCacheVolume == null ? null : compileCacheVolume.substring(compileCacheVolume.indexOf(':') + 1);
    }
}
