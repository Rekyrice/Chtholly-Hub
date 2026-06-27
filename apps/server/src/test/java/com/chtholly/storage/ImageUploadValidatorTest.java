package com.chtholly.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUploadValidatorTest {

    @Test
    void acceptsJpegMagicBytes() {
        byte[] header = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0};
        assertThat(ImageUploadValidator.matchesMagic(header, 8, "image/jpeg")).isTrue();
    }

    @Test
    void rejectsExeDisguisedAsJpeg() {
        byte[] header = new byte[] {'M', 'Z', 0, 0, 0, 0, 0, 0};
        assertThat(ImageUploadValidator.matchesMagic(header, 8, "image/jpeg")).isFalse();
    }

    @Test
    void acceptsPngMagicBytes() {
        byte[] header = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        };
        assertThat(ImageUploadValidator.matchesMagic(header, 8, "image/png")).isTrue();
    }

    @Test
    void acceptsGifMagicBytes() {
        byte[] header = "GIF89a".getBytes();
        assertThat(ImageUploadValidator.matchesMagic(header, 6, "image/gif")).isTrue();
    }

    @Test
    void acceptsWebpMagicBytes() {
        byte[] header = "RIFFxxxxWEBP".getBytes();
        assertThat(ImageUploadValidator.matchesMagic(header, 12, "image/webp")).isTrue();
    }
}
