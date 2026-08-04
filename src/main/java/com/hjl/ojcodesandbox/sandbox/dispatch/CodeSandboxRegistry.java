package com.hjl.ojcodesandbox.sandbox.dispatch;

import com.hjl.ojcodesandbox.CodeSandbox;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.language.LanguageCodeSandbox;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按编程语言保存可用的代码沙箱实现。
 */
@Component
public class CodeSandboxRegistry {

    private final Map<QuestionSubmitLanguageEnum, LanguageCodeSandbox> sandboxByLanguage;

    /**
     * 注册 Spring 容器中的语言沙箱，并拒绝重复的语言实现。
     *
     * @param sandboxes 语言沙箱列表
     */
    public CodeSandboxRegistry(List<LanguageCodeSandbox> sandboxes) {
        Map<QuestionSubmitLanguageEnum, LanguageCodeSandbox> registry =
                new EnumMap<>(QuestionSubmitLanguageEnum.class);
        for (LanguageCodeSandbox sandbox : sandboxes) {
            LanguageCodeSandbox previous = registry.put(sandbox.getLanguage(), sandbox);
            if (previous != null) {
                throw new IllegalStateException("Duplicate code sandbox for language: " + sandbox.getLanguage().getValue());
            }
        }
        this.sandboxByLanguage = registry;
    }

    /**
     * 获取指定语言的代码沙箱。
     *
     * @param language 编程语言
     * @return 已注册的沙箱，不存在时返回 {@code null}
     */
    public CodeSandbox getSandbox(QuestionSubmitLanguageEnum language) {
        return sandboxByLanguage.get(language);
    }
}
