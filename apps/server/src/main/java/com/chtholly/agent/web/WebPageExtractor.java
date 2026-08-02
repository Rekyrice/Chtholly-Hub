package com.chtholly.agent.web;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts bounded HTML or plain-text responses into normalized readable page content.
 */
@Slf4j
public final class WebPageExtractor {

    /** Maximum extracted text length. */
    public static final int MAX_TEXT_CHARS = 12_000;
    /** Maximum page title length. */
    public static final int MAX_TITLE_CHARS = 512;

    private static final String REMOVE_SELECTOR =
            "script,style,noscript,svg,nav,header,footer,aside,form";
    private static final Set<String> BLOCK_TAGS = Set.of(
            "article", "main", "section", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "p", "ul", "ol", "li", "blockquote", "pre", "code", "br", "table", "tr");
    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[\\t\\x0B\\f ]+");

    /**
     * Extracts readable content from a safe response.
     *
     * @param response bounded web response
     * @return normalized page content
     */
    public ExtractedWebPage extract(SafeWebResponse response) {
        try {
            return extractResponse(response);
        } catch (WebResearchException exception) {
            log.debug("Web page extraction failed with code {}", exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            log.debug("Web page extraction failed", exception);
            throw new WebResearchException(
                    "WEB_EXTRACTION_FAILED", "The web page could not be extracted.", exception);
        }
    }

    private static ExtractedWebPage extractResponse(SafeWebResponse response) {
        byte[] bytes = response.bytes();
        if ("text/plain".equals(response.contentType())) {
            Charset charset = plainTextCharset(response.charset(), bytes);
            String source = stripByteOrderMark(new String(bytes, charset));
            String text = truncate(normalizeText(source));
            return result(
                    response.finalUrl(), "", text, "plain-text", source.length(), charset.name(), null);
        }

        Document document = parseHtml(response, bytes);
        Charset effectiveCharset = document.charset();
        String source = stripByteOrderMark(new String(bytes, effectiveCharset));
        document.select(REMOVE_SELECTOR).remove();
        Element root = firstPresent(document, "article", "main", "[role=main]", "body");
        String text = root == null ? "" : truncate(normalizeText(readableText(root)));
        String title = truncate(document.title().trim(), MAX_TITLE_CHARS);
        URI declaredCanonical = declaredCanonicalUrl(document, response.finalUrl());
        return result(
                response.finalUrl(),
                title,
                text,
                "jsoup",
                source.length(),
                effectiveCharset.name(),
                declaredCanonical);
    }

    private static Document parseHtml(SafeWebResponse response, byte[] bytes) {
        String charsetHint = response.charset().isBlank() ? null : response.charset();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return Jsoup.parse(input, charsetHint, response.finalUrl().toString());
        } catch (IOException exception) {
            throw new WebResearchException(
                    "WEB_EXTRACTION_FAILED", "The web page could not be extracted.", exception);
        }
    }

    private static Charset plainTextCharset(String declaredCharset, byte[] bytes) {
        if (!declaredCharset.isBlank()) {
            return Charset.forName(declaredCharset);
        }
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) {
            return Charset.forName("UTF-32BE");
        }
        if (startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) {
            return Charset.forName("UTF-32LE");
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return StandardCharsets.UTF_16BE;
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return StandardCharsets.UTF_16LE;
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String stripByteOrderMark(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static Element firstPresent(Document document, String... selectors) {
        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    private static URI declaredCanonicalUrl(Document document, URI fallback) {
        Element canonical = document.selectFirst("link[rel~=canonical][href]");
        if (canonical == null || canonical.attr("href").isBlank()) {
            return null;
        }
        try {
            URI resolved = fallback.resolve(canonical.attr("href").trim());
            String scheme = resolved.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || resolved.getHost() == null || resolved.getRawUserInfo() != null
                    || resolved.toASCIIString().length() > WebUrlPolicy.MAX_URL_LENGTH) {
                return null;
            }
            return resolved;
        } catch (RuntimeException exception) {
            log.debug("Ignoring invalid declared canonical URL", exception);
            return null;
        }
    }

    private static String readableText(Element root) {
        StringBuilder output = new StringBuilder();
        appendNode(root, output);
        return output.toString();
    }

    private static void appendNode(Node node, StringBuilder output) {
        if (node instanceof TextNode textNode) {
            output.append(textNode.text());
            return;
        }
        if (node instanceof Element element) {
            String tag = element.normalName().toLowerCase(Locale.ROOT);
            if (tag.equals("li")) {
                newline(output);
                output.append("- ");
            } else if (BLOCK_TAGS.contains(tag)) {
                newline(output);
            }
            for (Node child : element.childNodes()) {
                appendNode(child, output);
            }
            if (BLOCK_TAGS.contains(tag)) {
                newline(output);
            }
        }
    }

    private static void newline(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
    }

    private static String normalizeText(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder output = new StringBuilder();
        for (String line : normalized.split("\\n", -1)) {
            String compact = INLINE_WHITESPACE.matcher(line).replaceAll(" ").trim();
            if (compact.isEmpty()) {
                if (!output.isEmpty() && !endsWithBlankLine(output)) {
                    output.append('\n');
                }
            } else {
                if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
                    output.append(' ');
                }
                output.append(compact).append('\n');
            }
        }
        return output.toString().strip();
    }

    private static String truncate(String text) {
        return truncate(text, MAX_TEXT_CHARS);
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static boolean endsWithBlankLine(StringBuilder output) {
        int length = output.length();
        return length >= 2 && output.charAt(length - 1) == '\n' && output.charAt(length - 2) == '\n';
    }

    private static ExtractedWebPage result(
            URI canonical,
            String title,
            String text,
            String extractor,
            int rawChars,
            String charset,
            URI declaredCanonical) {
        return new ExtractedWebPage(
                canonical,
                title,
                text,
                extractor,
                rawChars,
                text.length(),
                sha256(text),
                charset,
                declaredCanonical);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            log.error("SHA-256 digest is unavailable", exception);
            throw new WebResearchException(
                    "WEB_EXTRACTION_FAILED", "The web page could not be extracted.", exception);
        }
    }
}
