package com.chtholly.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对象存储抽象：OSS 与本地文件系统可切换。
 */
public interface StorageService {

    /**
     * 上传用户头像，返回可公开访问的 URL。
     */
    String uploadAvatar(long userId, InputStream inputStream, String contentType) throws IOException;

    /**
     * 生成直传 URL（OSS 为预签名 PUT，本地为服务端 multipart 上传端点）。
     */
    PresignedUrl generatePresignedPutUrl(String objectKey, String contentType);

    /**
     * 将文件写入指定 objectKey（本地存储模式由服务端接收上传）。
     */
    void uploadObject(String objectKey, InputStream inputStream, String contentType, long size) throws IOException;

    /**
     * 删除指定 objectKey 对应的对象。
     */
    void deleteObject(String objectKey);
}
