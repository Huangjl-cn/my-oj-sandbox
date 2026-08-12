package com.hjl.ojcodesandbox.sandbox.language;

import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DockerLanguageDefinitionTest {

    private final DockerLanguageDefinition definition = new DockerLanguageDefinition(
            QuestionSubmitLanguageEnum.JAVA,
            "image",
            "Main.java",
            new String[]{"javac", "Main.java"},
            new String[]{"java", "Main"},
            null
    );

    @Test
    void appendsEveryArgumentWithoutParsingOrShellInterpretation() {
        assertArrayEquals(
                new String[]{"java", "Main", "hello world", "", "[1,2,3]", "[[1,2],[3,4]]",
                        "& calc", "$(command)"},
                definition.getRunCommand(Arrays.asList(
                        "hello world", "", "[1,2,3]", "[[1,2],[3,4]]", "& calc", "$(command)"))
        );
    }

    @Test
    void leavesCommandUnchangedForEmptyArgumentList() {
        assertArrayEquals(new String[]{"java", "Main"}, definition.getRunCommand(Collections.emptyList()));
    }
}
