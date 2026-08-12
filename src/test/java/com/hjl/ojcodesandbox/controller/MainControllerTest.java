package com.hjl.ojcodesandbox.controller;

import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.model.JudgeInfo;
import com.hjl.ojcodesandbox.model.QuestionSubmitLanguageEnum;
import com.hjl.ojcodesandbox.sandbox.dispatch.DockerCodeSandboxDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MainController.class)
class MainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DockerCodeSandboxDispatcher dockerCodeSandboxDispatcher;

    @ParameterizedTest
    @EnumSource(QuestionSubmitLanguageEnum.class)
    void acceptsEverySupportedLanguageThroughUnifiedRoute(QuestionSubmitLanguageEnum language) throws Exception {
        ExecuteCodeResponse response = ExecuteCodeResponse.builder()
                .outputList(Arrays.asList("3", "7"))
                .message("Execution completed")
                .status(1)
                .judgeInfo(new JudgeInfo())
                .build();
        when(dockerCodeSandboxDispatcher.executeCode(any())).thenReturn(response);

        mockMvc.perform(post("/executeCode")
                        .header("auth", "secretKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cases\":["
                                + "{\"args\":[\"hello world\",\"\"]},"
                                + "{\"args\":[\"[1,2,3]\",\"4\"]},"
                                + "{\"args\":[\"[[1,2],[3,4]]\"]},"
                                + "{\"args\":[\"& calc\",\"$(command)\"]}],"
                                + "\"code\":\"source\",\"language\":\"" + language.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.outputList[0]").value("3"))
                .andExpect(jsonPath("$.outputList[1]").value("7"));

        ArgumentCaptor<ExecuteCodeRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteCodeRequest.class);
        verify(dockerCodeSandboxDispatcher).executeCode(requestCaptor.capture());
        ExecuteCodeRequest request = requestCaptor.getValue();
        assertEquals(4, request.getCases().size());
        assertEquals(Arrays.asList("hello world", ""), request.getCases().get(0).getArgs());
        assertEquals(Arrays.asList("[1,2,3]", "4"), request.getCases().get(1).getArgs());
        assertEquals(Arrays.asList("[[1,2],[3,4]]"), request.getCases().get(2).getArgs());
        assertEquals(Arrays.asList("& calc", "$(command)"), request.getCases().get(3).getArgs());
    }

    @Test
    void rejectsRequestWithoutAuthHeader() throws Exception {
        mockMvc.perform(post("/executeCode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cases\":[],\"code\":\"source\",\"language\":\"java\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());

        verifyNoInteractions(dockerCodeSandboxDispatcher);
    }

    @Test
    void rejectsWrongAuthHeaderWithoutInternalDetails() throws Exception {
        mockMvc.perform(post("/executeCode")
                        .header("auth", "wrongKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cases\":[],\"code\":\"source\",\"language\":\"java\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());

        verifyNoInteractions(dockerCodeSandboxDispatcher);
    }
}
