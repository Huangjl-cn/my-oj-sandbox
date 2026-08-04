package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DockerLanguageDefinitionTest {

    private final DockerLanguageDefinition definition = new DockerLanguageDefinition(
            QuestionSubmitLanguageEnum.JAVA,
            "image",
            "Main.java",
            new String[]{"javac", "Main.java"},
            new String[]{"java", "Main"}
    );

    @Test
    void appendsWhitespaceSeparatedArgvValues() {
        assertArrayEquals(
                new String[]{"java", "Main", "1", "2", "3"},
                definition.getRunCommand("  1   2  3  ")
        );
    }

    @Test
    void leavesCommandUnchangedForEmptyInput() {
        assertArrayEquals(new String[]{"java", "Main"}, definition.getRunCommand(""));
    }
}
