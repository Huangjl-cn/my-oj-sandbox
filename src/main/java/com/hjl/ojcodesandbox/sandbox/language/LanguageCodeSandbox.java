package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.CodeSandbox;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;

/**
 * 能够声明其支持语言的代码沙箱。
 */
public interface LanguageCodeSandbox extends CodeSandbox {

    /**
     * 获取当前沙箱支持的编程语言。
     *
     * @return 编程语言枚举
     */
    QuestionSubmitLanguageEnum getLanguage();
}
