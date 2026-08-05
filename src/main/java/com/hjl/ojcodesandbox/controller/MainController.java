package com.hjl.ojcodesandbox.controller;

import com.hjl.ojcodesandbox.model.ApiErrorResponse;
import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.sandbox.dispatch.DockerCodeSandboxDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/")
public class MainController {

    // 定义鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";

    private static final String AUTH_REQUEST_SECRET = "secretKey";

    @Resource
    private DockerCodeSandboxDispatcher dockerCodeSandboxDispatcher;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    /**
     * 执行代码（docker沙箱）
     *
     * @param executeCodeRequest 代码执行请求
     * @return 代码执行响应
     */
    @PostMapping("/executeCode")
    public ResponseEntity<?> executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest,
                                         HttpServletRequest request) {
        // 基本的认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!AUTH_REQUEST_SECRET.equals(authHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden"));
        }
        return ResponseEntity.ok(dockerCodeSandboxDispatcher.executeCode(executeCodeRequest));
    }

}
