package com.hjl.ojcodesandbox.model;

import lombok.Getter;

/**
 * 题目提交语言业务类型枚举
 */
@Getter
public enum QuestionSubmitLanguageEnum {

    JAVA("java", "java"),
    CPLUSPLUS("cpp", "cpp"),
    GOLANG("go", "go"),
    PYTHON("python", "python"),
    JAVASCRIPT("javascript", "javascript");

    private final String text;

    private final String value;

    QuestionSubmitLanguageEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static QuestionSubmitLanguageEnum getEnumByValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (QuestionSubmitLanguageEnum anEnum : QuestionSubmitLanguageEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

}
