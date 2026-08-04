package com.chtholly.storage.api;

import com.chtholly.storage.UploadContent;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Multipart HTTP adapter for the storage use case's transport-neutral upload content port.
 */
final class MultipartUploadContent implements UploadContent {

    private final MultipartFile file;

    MultipartUploadContent(MultipartFile file) {
        this.file = file;
    }

    @Override
    public boolean isEmpty() {
        return file == null || file.isEmpty();
    }

    @Override
    public long size() {
        return file == null ? 0L : file.getSize();
    }

    @Override
    public String contentType() {
        return file == null ? null : file.getContentType();
    }

    @Override
    public InputStream openStream() throws IOException {
        if (file == null) {
            throw new IOException("multipart file is absent");
        }
        return file.getInputStream();
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        if (file == null) {
            throw new IOException("multipart file is absent");
        }
        return file.getBytes();
    }
}
