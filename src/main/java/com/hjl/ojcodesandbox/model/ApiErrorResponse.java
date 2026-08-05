package com.hjl.ojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * HTTP 接口的简洁错误响应。
 */
@Getter
@AllArgsConstructor
public class ApiErrorResponse {

    private final int status;

    private final String message;
}
