package com.chtholly.agent.web;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Normalized readable page content and integrity metadata.
 *
 * @param canonicalUrl authoritative final fetched page URL
 * @param title page title
 * @param text bounded readable text
 * @param extractor extraction strategy identifier
 * @param rawChars source character count
 * @param extractedChars extracted character count after bounding
 * @param contentSha256 SHA-256 of extracted UTF-8 text
 * @param charset effective charset used to decode the response body
 * @param declaredCanonicalUrl bounded canonical URL declared by HTML, or null when absent or unsafe
 */
public record ExtractedWebPage(
        URI canonicalUrl,
        String title,
        String text,
        String extractor,
        int rawChars,
        int extractedChars,
        String contentSha256,
        String charset,
        URI declaredCanonicalUrl) {

    /**
     * Creates an extracted page without declared canonical metadata.
     *
     * @param canonicalUrl authoritative fetched URL
     * @param title bounded page title
     * @param text bounded readable text
     * @param extractor extraction strategy identifier
     * @param rawChars source character count
     * @param extractedChars extracted character count
     * @param contentSha256 SHA-256 of extracted text
     */
    public ExtractedWebPage(
            URI canonicalUrl,
            String title,
            String text,
            String extractor,
            int rawChars,
            int extractedChars,
            String contentSha256) {
        this(canonicalUrl, title, text, extractor, rawChars, extractedChars,
                contentSha256, StandardCharsets.UTF_8.name(), null);
    }

    /**
     * Creates an extracted page using UTF-8 compatibility metadata.
     *
     * @param canonicalUrl authoritative fetched URL
     * @param title bounded page title
     * @param text bounded readable text
     * @param extractor extraction strategy identifier
     * @param rawChars source character count
     * @param extractedChars extracted character count
     * @param contentSha256 SHA-256 of extracted text
     * @param declaredCanonicalUrl bounded canonical URL declared by HTML
     */
    public ExtractedWebPage(
            URI canonicalUrl,
            String title,
            String text,
            String extractor,
            int rawChars,
            int extractedChars,
            String contentSha256,
            URI declaredCanonicalUrl) {
        this(canonicalUrl, title, text, extractor, rawChars, extractedChars,
                contentSha256, StandardCharsets.UTF_8.name(), declaredCanonicalUrl);
    }

    /**
     * Validates immutable extraction values.
     */
    public ExtractedWebPage {
        canonicalUrl = Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        title = Objects.requireNonNull(title, "title");
        text = Objects.requireNonNull(text, "text");
        extractor = Objects.requireNonNull(extractor, "extractor");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        charset = Charset.forName(Objects.requireNonNull(charset, "charset")).name();
        if (rawChars < 0 || extractedChars < 0) {
            throw new IllegalArgumentException("character counts must not be negative");
        }
    }
}
