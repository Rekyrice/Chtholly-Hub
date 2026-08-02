package com.chtholly.agent.web;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class WebPageExtractorTest {

    @Test
    void extractsReadableArticleAndCanonicalMetadata() {
        String html = """
                <html><head><title> Page title </title><link rel="canonical" href="/canonical"></head>
                <body><nav>menu</nav><article><h1>Heading</h1><p>First paragraph.</p>
                <ul><li>One</li><li>Two</li></ul><blockquote>A quote</blockquote>
                <pre><code>int x = 1;</code></pre><script>bad()</script></article><footer>foot</footer></body></html>
                """;
        SafeWebResponse response = new SafeWebResponse(URI.create("https://example.com/source"), 200,
                List.of(URI.create("https://example.com/source")), "text/html",
                html.getBytes(StandardCharsets.UTF_8));

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.canonicalUrl()).isEqualTo(URI.create("https://example.com/source"));
        assertThat(declaredCanonical(page)).isEqualTo(URI.create("https://example.com/canonical"));
        assertThat(page.title()).isEqualTo("Page title");
        assertThat(page.text()).contains("Heading", "First paragraph.", "One", "Two", "A quote", "int x = 1;")
                .doesNotContain("menu", "bad()", "foot");
        assertThat(page.extractor()).isEqualTo("jsoup");
        assertThat(page.rawChars()).isEqualTo(html.length());
        assertThat(page.extractedChars()).isEqualTo(page.text().length());
        assertThat(page.contentSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void supportsPlainTextAndTruncatesContent() {
        String text = "x".repeat(13_000);
        SafeWebResponse response = new SafeWebResponse(URI.create("https://example.com/a.txt"), 200,
                List.of(URI.create("https://example.com/a.txt")), "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.text()).hasSize(12_000);
        assertThat(page.rawChars()).isEqualTo(13_000);
        assertThat(page.extractor()).isEqualTo("plain-text");
    }

    @Test
    void decodesHtmlUsingDeclaredHeaderCharset() {
        Charset charset = Charset.forName("GBK");
        String html = "<html><head><title>\u590F\u65E5\u65C5\u5E97</title></head>"
                + "<body><article>\u7A97\u5916\u7684\u6D77\u8FD8\u662F\u5F88\u84DD\u3002</article></body></html>";
        SafeWebResponse response = new SafeWebResponse(
                URI.create("https://example.com/gbk"),
                200,
                List.of(URI.create("https://example.com/gbk")),
                "text/html",
                charset.name(),
                html.getBytes(charset));

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.title()).isEqualTo("\u590F\u65E5\u65C5\u5E97");
        assertThat(page.text()).contains("\u7A97\u5916\u7684\u6D77\u8FD8\u662F\u5F88\u84DD");
        assertThat(effectiveCharset(page)).isEqualTo("GBK");
    }

    @Test
    void detectsHtmlMetaCharsetWhenHeaderOmitsIt() {
        Charset charset = Charset.forName("Shift_JIS");
        String html = "<html><head><meta charset=\"Shift_JIS\"><title>\u590F\u306E\u65C5</title></head>"
                + "<body><article>\u6D77\u8FBA\u306E\u30DB\u30C6\u30EB</article></body></html>";
        SafeWebResponse response = new SafeWebResponse(
                URI.create("https://example.com/meta"),
                200,
                List.of(URI.create("https://example.com/meta")),
                "text/html",
                html.getBytes(charset));

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.title()).isEqualTo("\u590F\u306E\u65C5");
        assertThat(page.text()).contains("\u6D77\u8FBA\u306E\u30DB\u30C6\u30EB");
        assertThat(effectiveCharset(page)).isEqualTo("Shift_JIS");
    }

    @Test
    void detectsHtmlBomWhenHeaderAndMetaOmitCharset() {
        String html = "<html><head><title>\u96E8\u591C</title></head>"
                + "<body><article>\u706F\u308A\u306E\u6B8B\u308B\u30DB\u30C6\u30EB</article></body></html>";
        byte[] encoded = html.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[encoded.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(encoded, 0, withBom, 2, encoded.length);
        SafeWebResponse response = new SafeWebResponse(
                URI.create("https://example.com/bom"),
                200,
                List.of(URI.create("https://example.com/bom")),
                "text/html",
                withBom);

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.title()).isEqualTo("\u96E8\u591C");
        assertThat(page.text()).contains("\u706F\u308A\u306E\u6B8B\u308B\u30DB\u30C6\u30EB");
        assertThat(effectiveCharset(page)).isEqualTo("UTF-16");
    }

    @Test
    void reportsStableExtractionErrorForInvalidInput() {
        assertThatThrownBy(() -> new WebPageExtractor().extract(null))
                .isInstanceOf(WebResearchException.class)
                .extracting(error -> ((WebResearchException) error).code())
                .isEqualTo("WEB_EXTRACTION_FAILED");
    }

    @Test
    void boundsUntrustedPageTitles() {
        String html = "<html><head><title>" + "t".repeat(2_000) + "</title></head><body>text</body></html>";
        SafeWebResponse response = new SafeWebResponse(URI.create("https://example.com/source"), 200,
                List.of(URI.create("https://example.com/source")), "text/html",
                html.getBytes(StandardCharsets.UTF_8));

        ExtractedWebPage page = new WebPageExtractor().extract(response);

        assertThat(page.title()).hasSize(512);
    }

    @Test
    void rejectsUnsafeOrOversizedDeclaredCanonicalMetadata() {
        for (String declared : List.of(
                "https://user@example.com/credentialed",
                "https://example.com/" + "x".repeat(2_100),
                "javascript:alert(1)")) {
            String html = "<html><head><link rel=\"canonical\" href=\"" + declared
                    + "\"></head><body>text</body></html>";
            SafeWebResponse response = new SafeWebResponse(URI.create("https://example.com/source"), 200,
                    List.of(URI.create("https://example.com/source")), "text/html",
                    html.getBytes(StandardCharsets.UTF_8));

            ExtractedWebPage page = new WebPageExtractor().extract(response);

            assertThat(page.canonicalUrl()).isEqualTo(response.finalUrl());
            assertThat(declaredCanonical(page)).isNull();
        }
    }

    @Test
    void normalizesManyBlankLinesWithinLinearTime() {
        String text = ("line\n\n").repeat(80_000);
        SafeWebResponse response = new SafeWebResponse(URI.create("https://example.com/a.txt"), 200,
                List.of(URI.create("https://example.com/a.txt")), "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        assertTimeout(Duration.ofSeconds(1), () -> new WebPageExtractor().extract(response));
    }

    private static URI declaredCanonical(ExtractedWebPage page) {
        try {
            return (URI) page.getClass().getMethod("declaredCanonicalUrl").invoke(page);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static String effectiveCharset(ExtractedWebPage page) {
        try {
            return (String) page.getClass().getMethod("charset").invoke(page);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("ExtractedWebPage must expose the effective charset", exception);
        }
    }
}
