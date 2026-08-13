package com.hjl.ojcodesandbox.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxAuthFilter 验签逻辑测试。
 *
 * <p>测试密钥对与 oj-backend application-dev.yml 的开发测试密钥对一致
 * （公钥同沙箱 application.yml 的 codesandbox.auth.public-key），
 * 签名构造逻辑与后端 SandboxAuthSigner 的规范化字符串协议严格一致。
 */
class SandboxAuthFilterTest {

    private static final String KEY_ID = "sandbox-key-dev-2026";

    /**
     * 与后端 application-dev.yml 配套的开发私钥（测试用途）。
     */
    private static final String TEST_PRIVATE_KEY_BASE64 =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA3g4tm7fJYlnSAbBjD7pU8exOMaXToOSfNkCuMzfmfDQ==";

    private static final String TEST_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEujoSoLNpNzIcqf6diJhgPPccfqroPqBFTwxm51jt9cb+JmO8/LLsTgUqRkcLA7Xf3SEpl3TUrzYNdv7K0Nv07w==";

    private static final long CLOCK_SKEW_SECONDS = 300L;

    private static final String REQUEST_BODY =
            "{\"language\":\"go\",\"code\":\"package main\\nfunc main(){}\",\"cases\":[{\"args\":[]}]}";

    private SandboxAuthFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new SandboxAuthFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "keyId", KEY_ID);
        ReflectionTestUtils.setField(filter, "publicKeyConfig", TEST_PUBLIC_KEY_BASE64);
        ReflectionTestUtils.setField(filter, "clockSkewSeconds", CLOCK_SKEW_SECONDS);
        filter.afterPropertiesSet();
    }

    @Test
    void testHealthEndpointBypassesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void testDisabledAllowsRequestsWithoutHeaders() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/executeCode");
        request.setContent(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void testValidSignedRequestPasses() throws Exception {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-valid-" + System.nanoTime();
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY, timestamp, nonce);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        // 下游应收到请求体缓存 wrapper，可再次读取 body
        assertNotNull(chain.getRequest());
        String forwardedBody = chain.getRequest().getReader().lines()
                .reduce("", (a, b) -> a + b);
        assertEquals(REQUEST_BODY, forwardedBody);
    }

    @Test
    void testMissingHeaderRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/executeCode");
        request.setContent(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    void testUnknownKeyIdRejected() throws Exception {
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY,
                System.currentTimeMillis(), "nonce-" + System.nanoTime());
        request.removeHeader("X-Sandbox-Key-Id");
        request.addHeader("X-Sandbox-Key-Id", "sandbox-key-attacker");

        MockHttpServletResponse response = new MockHttpServletResponse();
        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    void testInvalidTimestampRejected() throws Exception {
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY,
                System.currentTimeMillis(), "nonce-" + System.nanoTime());
        request.removeHeader("X-Sandbox-Timestamp");
        request.addHeader("X-Sandbox-Timestamp", "not-a-number");

        MockHttpServletResponse response = new MockHttpServletResponse();
        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    void testExpiredTimestampRejected() throws Exception {
        long expired = System.currentTimeMillis() - (CLOCK_SKEW_SECONDS + 60) * 1000L;
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY, expired,
                "nonce-" + System.nanoTime());

        MockHttpServletResponse response = new MockHttpServletResponse();
        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    void testReplayedNonceRejected() throws Exception {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-replay";
        MockHttpServletRequest first = buildSignedRequest(REQUEST_BODY, timestamp, nonce);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        invokeFilter(first, firstResponse);
        assertEquals(200, firstResponse.getStatus());

        MockHttpServletRequest replay = buildSignedRequest(REQUEST_BODY, timestamp, nonce);
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        invokeFilter(replay, replayResponse);

        assertEquals(401, replayResponse.getStatus());
        assertTrue(replayResponse.getContentAsString().contains("replayed request"));
    }

    @Test
    void testTamperedBodyRejected() throws Exception {
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY,
                System.currentTimeMillis(), "nonce-" + System.nanoTime());
        // 签名基于原始 body 生成，篡改请求体后验签应失败
        request.setContent("{\"language\":\"go\",\"code\":\"hacked\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    void testInvalidSignatureRejected() throws Exception {
        MockHttpServletRequest request = buildSignedRequest(REQUEST_BODY,
                System.currentTimeMillis(), "nonce-" + System.nanoTime());
        request.removeHeader("X-Sandbox-Signature");
        request.addHeader("X-Sandbox-Signature", Base64.getEncoder()
                .encodeToString("tampered-signature".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        invokeFilter(request, response);

        assertEquals(401, response.getStatus());
    }

    /**
     * 构造带完整签名头的请求，签名协议与后端 SandboxAuthSigner 一致。
     */
    private MockHttpServletRequest buildSignedRequest(String body, long timestamp, String nonce)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/executeCode");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Sandbox-Key-Id", KEY_ID);
        request.addHeader("X-Sandbox-Timestamp", String.valueOf(timestamp));
        request.addHeader("X-Sandbox-Nonce", nonce);
        String canonical = timestamp + "\n" + nonce + "\n" + sha256Hex(body);
        request.addHeader("X-Sandbox-Signature", sign(canonical));
        return request;
    }

    private void invokeFilter(MockHttpServletRequest request, MockHttpServletResponse response) {
        try {
            filter.doFilter(request, response, new MockFilterChain());
        } catch (Exception e) {
            throw new AssertionError("Filter 执行失败", e);
        }
    }

    private static String sign(String canonical) throws Exception {
        byte[] der = Base64.getDecoder().decode(TEST_PRIVATE_KEY_BASE64);
        PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String sha256Hex(String content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
