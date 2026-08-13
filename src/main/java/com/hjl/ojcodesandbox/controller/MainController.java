package com.hjl.ojcodesandbox.controller;

import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.sandbox.dispatch.DockerCodeSandboxDispatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/")
public class MainController {

    @Resource
    private DockerCodeSandboxDispatcher dockerCodeSandboxDispatcher;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    /**
     * 执行代码（docker沙箱）。鉴权由 SandboxAuthFilter 统一处理。
     *
     * @param executeCodeRequest 代码执行请求
     * @return 代码执行响应
     */
    @PostMapping("/executeCode")
    public ResponseEntity<?> executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest) {
        return ResponseEntity.ok(dockerCodeSandboxDispatcher.executeCode(executeCodeRequest));
    }

}
