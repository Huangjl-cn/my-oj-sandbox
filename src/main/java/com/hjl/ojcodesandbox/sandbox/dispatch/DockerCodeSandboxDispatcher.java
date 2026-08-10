package com.hjl.ojcodesandbox.sandbox.dispatch;

import com.hjl.ojcodesandbox.CodeSandbox;
import com.hjl.ojcodesandbox.model.ExecuteCaseRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.result.SandboxResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 统一校验执行请求，并将请求分发给对应语言的 Docker 沙箱。
 */
@Component
@RequiredArgsConstructor
public class DockerCodeSandboxDispatcher implements CodeSandbox {

    private final CodeSandboxRegistry codeSandboxRegistry;

    /**
     * 校验请求并调用匹配的语言沙箱。
     *
     * @param executeCodeRequest 代码执行请求
     * @return 统一的代码执行响应
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        // 在进入 Docker 层前完成不依赖具体语言的请求校验。
        if (executeCodeRequest == null) {
            return SandboxResponseFactory.systemError("Request body is required");
        }
        if (executeCodeRequest.getCases() == null) {
            executeCodeRequest.setCases(Collections.emptyList());
        }
        for (int caseIndex = 0; caseIndex < executeCodeRequest.getCases().size(); caseIndex++) {
            ExecuteCaseRequest executeCase = executeCodeRequest.getCases().get(caseIndex);
            if (executeCase == null) {
                return SandboxResponseFactory.systemError("Case at index " + caseIndex + " is required");
            }
            if (executeCase.getArgs() == null) {
                executeCase.setArgs(Collections.emptyList());
            }
            for (int argIndex = 0; argIndex < executeCase.getArgs().size(); argIndex++) {
                if (executeCase.getArgs().get(argIndex) == null) {
                    return SandboxResponseFactory.systemError("Argument at case " + caseIndex
                            + " index " + argIndex + " is required");
                }
            }
        }
        if (executeCodeRequest.getCode() == null) {
            return SandboxResponseFactory.systemError("Source code is required");
        }
        QuestionSubmitLanguageEnum language =
                QuestionSubmitLanguageEnum.getEnumByValue(executeCodeRequest.getLanguage());
        if (language == null) {
            return SandboxResponseFactory.systemError("Unsupported language: " + executeCodeRequest.getLanguage());
        }

        CodeSandbox codeSandbox = codeSandboxRegistry.getSandbox(language);
        if (codeSandbox == null) {
            return SandboxResponseFactory.systemError("No Docker sandbox registered for language: "
                    + language.getValue());
        }
        return codeSandbox.executeCode(executeCodeRequest);
    }
}
