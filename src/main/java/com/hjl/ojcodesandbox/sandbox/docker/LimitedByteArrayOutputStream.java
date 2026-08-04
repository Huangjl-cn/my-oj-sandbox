package com.hjl.ojcodesandbox.sandbox.docker;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;

/**
 * 限制可写字节数，并记录输出是否被截断。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {

    private final int limit;

    private boolean truncated;

    /**
     * 写入单个字节，达到上限后仅记录截断状态。
     *
     * @param value 待写入字节
     */
    @Override
    public synchronized void write(int value) {
        if (count < limit) {
            super.write(value);
        } else {
            truncated = true;
        }
    }

    /**
     * 写入不超过剩余容量的字节。
     *
     * @param bytes  数据来源
     * @param offset 起始偏移量
     * @param length 写入长度
     */
    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        int writableLength = Math.min(length, limit - count);
        if (writableLength > 0) {
            super.write(bytes, offset, writableLength);
        }
        if (writableLength < length) {
            truncated = true;
        }
    }

    /**
     * 判断是否存在超出容量限制的输出。
     *
     * @return 输出被截断时返回 {@code true}
     */
    boolean isTruncated() {
        return truncated;
    }
}
