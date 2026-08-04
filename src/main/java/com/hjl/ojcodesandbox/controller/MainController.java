package com.hjl.ojcodesandbox.controller;

import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;
import com.hjl.ojcodesandbox.sandbox.dispatch.DockerCodeSandboxDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest request,
                                           HttpServletResponse response) {
        // 基本的认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!AUTH_REQUEST_SECRET.equals(authHeader)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return dockerCodeSandboxDispatcher.executeCode(executeCodeRequest);
    }

}
