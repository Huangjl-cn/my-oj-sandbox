package com.hjl.ojcodesandbox;


import com.hjl.ojcodesandbox.model.ExecuteCodeRequest;
import com.hjl.ojcodesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口定义
 */
public interface CodeSandbox {

    /**
     * 执行代码
     *
     * @param executeCodeRequest 代码执行的参数
     * @return 代码执行结果
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
