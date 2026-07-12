package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Performs deterministic prose-quality checks without an LLM or persistence dependency.
 */
@Component
public final class ContentPackQualityGate {

    private static final int MIN_BODY_CHINESE_CHARACTERS = 600;
    private static final int MAX_BODY_CHINESE_CHARACTERS = 2_600;
    private static final double MAX_FIVE_GRAM_JACCARD = 0.38;
    private static final Set<String> CONTENT_V3_FORMATS = Set.of(
            "community-note", "issue-note", "review", "longform-review");
    private static final List<TemplateFamily> TEMPLATE_FAMILIES = List.of(
            new TemplateFamily("不是…而是…", Pattern.compile("不是[^。！？\\n]{0,80}而是")),
            new TemplateFamily("真正…在意", Pattern.compile("真正[^。！？\\n]{0,80}在意")),
            new TemplateFamily("总的来说", Pattern.compile("总的来说")));
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#{2,3}\\s+(.+?)\\s*$");
    private static final Pattern ANY_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern IMAGE_CAPTION_PATTERN = Pattern.compile(
            "^\\s*(?:\\*[^*\\r\\n]+\\*|_[^_\\r\\n]+_)\\s*$");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern AUTOLINK_PATTERN = Pattern.compile("<https?://[^>]+>");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s。，、；：！？)\\]}>]+");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("(`+)([^`]*?)\\1");
    private static final List<BannedPhrase> BANNED_PHRASES = List.of(
            new BannedPhrase("在当今.*时代", Pattern.compile("在当今.*?时代", Pattern.DOTALL)),
            new BannedPhrase("首先.*其次.*最后", Pattern.compile("首先.*?其次.*?最后", Pattern.DOTALL)),
            new BannedPhrase("总的来说", Pattern.compile("总的来说")),
            new BannedPhrase("值得一提", Pattern.compile("值得一提")),
            new BannedPhrase("让我们", Pattern.compile("让我们")));

    /**
     * Audits all articles and reports stable ordered blocking errors and non-blocking warnings.
     *
     * @param pack loaded content pack
     * @return deterministic quality diagnostics
     */
    public QualityGateResult audit(ContentPack pack) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<PostAnalysis> analyses = pack.posts().stream().map(this::analyze).toList();

        boolean contentV3 = "content-v3".equals(pack.manifest().version());
        for (PostAnalysis analysis : analyses) {
            auditPost(analysis, contentV3, errors);
        }
        for (int left = 0; left < analyses.size(); left++) {
            for (int right = left + 1; right < analyses.size(); right++) {
                auditPair(analyses.get(left), analyses.get(right), errors);
            }
        }
        if (contentV3) {
            auditCorpusTemplates(analyses, errors);
        }
        return new QualityGateResult(errors, warnings);
    }

    private PostAnalysis analyze(SeedPostDefinition post) {
        ScannedMarkdown scanned = scanMarkdown(post.markdown());
        String lead = scanned.paragraphs().isEmpty() ? "" : scanned.paragraphs().getFirst();
        String ending = scanned.paragraphs().isEmpty() ? "" : scanned.paragraphs().getLast();
        return new PostAnalysis(post, scanned.prose(), lead, ending, scanned.headings(), fiveGrams(scanned.prose()));
    }

    private void auditPost(PostAnalysis analysis, boolean contentV3, List<String> errors) {
        SeedPostDefinition post = analysis.post();
        String prose = analysis.prose();
        for (BannedPhrase bannedPhrase : BANNED_PHRASES) {
            if (bannedPhrase.pattern().matcher(prose).find()) {
                errors.add("boilerplate in " + post.seedKey() + ": " + bannedPhrase.label());
            }
        }

        List<String> anchors = post.brief() == null ? List.of() : post.brief().factAnchors();
        boolean hasAnchor = anchors != null && anchors.stream()
                .filter(anchor -> anchor != null && !anchor.isBlank())
                .anyMatch(anchor -> containsIgnoreCase(prose, anchor));
        if (!hasAnchor) {
            errors.add("missing fact anchor: " + post.seedKey());
        }

        String format = post.brief() == null ? null : post.brief().format();
        long bodyLength = chineseCharacterCount(prose);
        if (contentV3) {
            if (format == null || !CONTENT_V3_FORMATS.contains(format)) {
                errors.add("unsupported content-v3 format: " + post.seedKey() + " -> " + format);
                return;
            }
            LengthRange range = switch (format) {
                case "community-note" -> new LengthRange(180, 700);
                case "issue-note" -> new LengthRange(300, 1_800);
                case "review" -> new LengthRange(450, 2_200);
                case "longform-review" -> new LengthRange(4_000, 6_000);
                default -> throw new IllegalStateException("unreachable content-v3 format: " + format);
            };
            if (!range.contains(bodyLength)) {
                errors.add("body length out of range: " + post.seedKey() + " -> " + bodyLength);
            }
        } else if (!"short-note".equals(format)
                && (bodyLength < MIN_BODY_CHINESE_CHARACTERS || bodyLength > MAX_BODY_CHINESE_CHARACTERS)) {
            errors.add("body length out of range: " + post.seedKey() + " -> " + bodyLength);
        }
    }

    private long chineseCharacterCount(String prose) {
        return prose.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
    }

    private void auditCorpusTemplates(List<PostAnalysis> analyses, List<String> errors) {
        for (TemplateFamily family : TEMPLATE_FAMILIES) {
            List<String> matches = analyses.stream()
                    .filter(analysis -> family.pattern().matcher(analysis.prose()).find())
                    .map(analysis -> analysis.post().seedKey())
                    .toList();
            if (matches.size() > 1) {
                errors.add("corpus template family " + family.label() + ": " + String.join(" <-> ", matches));
            }
        }
    }

    private void auditPair(PostAnalysis left, PostAnalysis right, List<String> errors) {
        if (!left.lead().isBlank() && left.lead().equals(right.lead())) {
            errors.add("duplicate lead: " + left.post().seedKey() + " <-> " + right.post().seedKey());
        }

        double similarity = jaccard(left.fiveGrams(), right.fiveGrams());
        if (similarity > MAX_FIVE_GRAM_JACCARD) {
            errors.add("5-gram similarity above 0.38: " + left.post().seedKey() + " <-> " + right.post().seedKey());
        }

        if (!left.headings().isEmpty() && left.headings().equals(right.headings())) {
            errors.add("identical heading sequence: " + left.post().seedKey() + " <-> " + right.post().seedKey());
        }

        if (!left.ending().isBlank() && left.ending().equals(right.ending())) {
            errors.add("repeated ending: " + left.post().seedKey() + " <-> " + right.post().seedKey());
        }
    }

    private boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private ScannedMarkdown scanMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new ScannedMarkdown(List.of(), List.of(), "");
        }
        List<String> paragraphs = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        Character fenceMarker = null;
        boolean imageAwaitingCaption = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.stripLeading();
            Character marker = fenceMarker(trimmed);
            if (fenceMarker == null && marker != null) {
                flushParagraph(paragraph, paragraphs);
                fenceMarker = marker;
                continue;
            }
            if (fenceMarker != null) {
                if (marker != null && marker.equals(fenceMarker)) {
                    fenceMarker = null;
                }
                continue;
            }

            boolean imageLine = IMAGE_PATTERN.matcher(line.strip()).matches();
            if (imageLine) {
                flushParagraph(paragraph, paragraphs);
                imageAwaitingCaption = true;
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(paragraph, paragraphs);
                continue;
            }
            if (imageAwaitingCaption && IMAGE_CAPTION_PATTERN.matcher(line).matches()) {
                imageAwaitingCaption = false;
                continue;
            }
            imageAwaitingCaption = false;

            String proseLine = stripNonProse(line).strip();
            if (ANY_HEADING_PATTERN.matcher(proseLine).matches()) {
                flushParagraph(paragraph, paragraphs);
                if (HEADING_PATTERN.matcher(proseLine).matches()) {
                    headings.add(proseLine);
                }
            } else if (proseLine.isBlank()) {
                flushParagraph(paragraph, paragraphs);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(proseLine);
            }
        }
        flushParagraph(paragraph, paragraphs);
        return new ScannedMarkdown(paragraphs, headings, String.join("\n", paragraphs));
    }

    private Character fenceMarker(String trimmedLine) {
        if (trimmedLine.matches("^`{3,}.*$")) {
            return '`';
        }
        if (trimmedLine.matches("^~{3,}.*$")) {
            return '~';
        }
        return null;
    }

    private String stripNonProse(String line) {
        String result = IMAGE_PATTERN.matcher(line).replaceAll("");
        result = LINK_PATTERN.matcher(result).replaceAll("$1");
        result = AUTOLINK_PATTERN.matcher(result).replaceAll("");
        result = URL_PATTERN.matcher(result).replaceAll("");
        // 行内代码是用户可见正文，解包保留类名、命令和 Agent 术语；仅 fenced code 整块忽略。
        return INLINE_CODE_PATTERN.matcher(result).replaceAll("$2");
    }

    private void flushParagraph(StringBuilder paragraph, List<String> paragraphs) {
        String normalized = paragraph.toString().strip().replaceAll("\\s+", " ");
        if (!normalized.isBlank()) {
            paragraphs.add(normalized);
        }
        paragraph.setLength(0);
    }

    private Set<String> fiveGrams(String prose) {
        if (prose == null) {
            return Set.of();
        }
        // 去掉 Markdown 标记和空白，让检查关注正文措辞而不是排版差异。
        String normalized = prose.replaceAll("[#*_>`~\\-\\s]", "");
        int[] codePoints = normalized.codePoints().toArray();
        Set<String> grams = new HashSet<>();
        for (int index = 0; index + 5 <= codePoints.length; index++) {
            grams.add(new String(codePoints, index, 5));
        }
        return grams;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private record BannedPhrase(String label, Pattern pattern) {
    }

    private record TemplateFamily(String label, Pattern pattern) {
    }

    private record LengthRange(long minimum, long maximum) {

        private boolean contains(long value) {
            return value >= minimum && value <= maximum;
        }
    }

    private record ScannedMarkdown(List<String> paragraphs, List<String> headings, String prose) {
    }

    private record PostAnalysis(
            SeedPostDefinition post,
            String prose,
            String lead,
            String ending,
            List<String> headings,
            Set<String> fiveGrams) {
    }

    /**
     * Stable diagnostics used to block formal imports while preserving dry-run visibility.
     *
     * @param errors blocking quality defects
     * @param warnings non-blocking quality observations
     */
    public record QualityGateResult(List<String> errors, List<String> warnings) {

        /**
         * Protects diagnostics from caller mutation.
         */
        public QualityGateResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
