package com.finagent.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFBox research-report renderer (Phase 10).
 *
 * <p>Pure presentation: renders a pre-built {@link ResearchReportData} snapshot and
 * performs no research, AI, MCP or network calls. Only sections with actual data
 * are emitted; missing values render as the word "Unavailable" — never 0, never
 * fabricated. Long text is word-wrapped, tables break across pages, and every
 * page carries a header, footer and page number.</p>
 *
 * <p>Registered as a stateless singleton: per-report state (including document
 * fonts) lives in the local {@code Canvas}, so concurrent report requests
 * never share mutable state.</p>
 *
 * <p>Phase 17: full Unicode rendering via embedded DejaVu fonts (bundled under
 * {@code resources/fonts}, DejaVu license included — freely redistributable,
 * no runtime download, no dependency on local OS fonts). If the bundled fonts
 * are ever unavailable, rendering falls back to WinAnsi standard-14 fonts
 * with lossy sanitization (never a crash).</p>
 */
@Component
public class PdfReportGenerator {

    static final float MARGIN_SIDE = 54;
    static final float MARGIN_TOP = 72;
    static final float MARGIN_BOTTOM = 60;
    static final int MAX_HEADLINES_PER_TICKER = 8;

    static final String UNAVAILABLE = "Unavailable";

    private static final Color ACCENT = new Color(0.10f, 0.28f, 0.48f);
    private static final Color GRAY = new Color(0.42f, 0.42f, 0.42f);
    private static final Color TABLE_HEAD_FILL = new Color(0.13f, 0.30f, 0.48f);
    private static final Color GRID = new Color(0.70f, 0.70f, 0.70f);

    /** Per-document font set. Document-scoped because PDFBox embeds per document. */
    record Fonts(PDFont regular, PDFont bold, PDFont italic, PDFont fallback, boolean unicode) {
    }

    /**
     * Glyph coverage of the bundled fonts, parsed once per JVM (the 12 MB
     * Unifont parse is far too expensive per report). Primary = DejaVu Sans
     * (all three styles share its repertoire); fallback = GNU Unifont.
     */
    static final class Coverage {
        private static volatile TrueTypeFont dejavu;
        private static volatile TrueTypeFont unifont;

        static boolean inPrimary(int codePoint) {
            return has(loadDejavu(), codePoint);
        }

        static boolean inFallback(int codePoint) {
            return has(loadUnifont(), codePoint);
        }

        private static boolean has(TrueTypeFont font, int codePoint) {
            if (font == null) {
                return false;
            }
            try {
                // Glyph id 0 is .notdef (missing). Note: TrueTypeFont.hasGlyph(String)
                // looks up glyph NAMES, not Unicode coverage — wrong API for this.
                return font.getUnicodeCmapLookup().getGlyphId(codePoint) != 0;
            } catch (Exception ex) {
                return false;
            }
        }

        private static TrueTypeFont loadDejavu() {
            TrueTypeFont cached = dejavu;
            if (cached == null) {
                synchronized (Coverage.class) {
                    cached = dejavu;
                    if (cached == null) {
                        cached = parse("/fonts/DejaVuSans.ttf");
                        dejavu = cached;
                    }
                }
            }
            return cached;
        }

        private static TrueTypeFont loadUnifont() {
            TrueTypeFont cached = unifont;
            if (cached == null) {
                synchronized (Coverage.class) {
                    cached = unifont;
                    if (cached == null) {
                        cached = parse("/fonts/unifont-14.0.01.ttf");
                        unifont = cached;
                    }
                }
            }
            return cached;
        }

        private static TrueTypeFont parse(String resource) {
            try (InputStream in = PdfReportGenerator.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                // fontbox 3.x parses from random-access reads, not streams.
                return new TTFParser().parse(new RandomAccessReadBuffer(in));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Loads the bundled DejaVu triple + Unifont fallback (embedded subsets per
     * document). Falls back to standard-14 WinAnsi fonts when the resources are
     * missing or unreadable.
     */
    static Fonts loadFonts(PDDocument document) {
        PDFont regular = loadBundled(document, "/fonts/DejaVuSans.ttf");
        PDFont bold = loadBundled(document, "/fonts/DejaVuSans-Bold.ttf");
        PDFont italic = loadBundled(document, "/fonts/DejaVuSans-Oblique.ttf");
        PDFont fallback = loadBundled(document, "/fonts/unifont-14.0.01.ttf");
        if (regular != null && bold != null && italic != null && fallback != null) {
            return new Fonts(regular, bold, italic, fallback, true);
        }
        return new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA), false);
    }

    private static PDFont loadBundled(PDDocument document, String resource) {
        try (InputStream in = PdfReportGenerator.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return PDType0Font.load(document, in, true);
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    public PdfReportGenerator() {
        // Stateless: nothing to initialize. Font metrics are computed per document.
    }

    /**
     * Renders the report. The document is never written to disk here — the caller
     * decides how to serve the returned bytes.
     */
    public byte[] generate(ResearchReportData data) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("Report data must not be null");
        }
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("FinAgent Research Report " + data.researchId());
            info.setAuthor("FinAgent");
            info.setCreator("FinAgent PDF export (PDFBox)");
            info.setCreationDate(java.util.Calendar.getInstance());

            Canvas canvas = new Canvas(document, data.researchId().toString(), loadFonts(document));
            renderCover(canvas, data);
            renderExecutiveSummary(canvas, data);
            renderStocks(canvas, data);
            renderNewsAndSentiment(canvas, data);
            renderInterpretation(canvas, data);
            renderGapsAndLimitations(canvas, data);
            renderDisclaimer(canvas, data);
            canvas.finish();

            document.save(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ sections

    private void renderCover(Canvas canvas, ResearchReportData data) throws IOException {
        canvas.title("FINAGENT", 22, ACCENT);
        canvas.line("AI-POWERED FINANCIAL RESEARCH", canvas.fonts().regular(), 10, GRAY);
        canvas.gap(4);
        canvas.rule();
        canvas.gap(6);
        canvas.title("Research Report", 16, Color.BLACK);
        canvas.gap(4);
        canvas.labeled("Research ID", data.researchId().toString());
        canvas.labeled("Generated", stamp(data.generatedAt()));
        canvas.labeled("Completed", stamp(data.completedAt()));
        canvas.labeled("Stocks analyzed",
                data.tickers().isEmpty() ? UNAVAILABLE : String.join(", ", data.tickers()));
        canvas.gap(4);
        canvas.heading2("Research Request");
        String request = data.requestText() == null || data.requestText().isBlank()
                ? UNAVAILABLE : quote(data.requestText().strip());
        canvas.quote(request);
    }

    private void renderExecutiveSummary(Canvas canvas, ResearchReportData data) throws IOException {
        if (blank(data.executiveSummary())) {
            return;
        }
        canvas.heading1("EXECUTIVE SUMMARY");
        canvas.para(data.executiveSummary());
    }

    private void renderStocks(Canvas canvas, ResearchReportData data) throws IOException {
        if (data.entries().isEmpty()) {
            return;
        }
        canvas.heading1("STOCK ANALYSIS");

        List<String[]> marketRows = new ArrayList<>();
        for (ResearchReportData.Entry entry : data.entries()) {
            marketRows.add(new String[]{
                    entry.symbol(),
                    value(entry.price()),
                    signed(entry.change()),
                    pct(entry.changePercent())});
        }
        if (marketRows.stream().anyMatch(r -> !UNAVAILABLE.equals(r[1]))) {
            canvas.heading2("Market Data");
            canvas.table(new String[]{"Symbol", "Price", "Change", "Change %"},
                    new float[]{0.22f, 0.26f, 0.26f, 0.26f}, marketRows);
        }

        List<ResearchReportData.Entry> withFundamentals = data.entries().stream()
                .filter(ResearchReportData.Entry::hasFundamentals).toList();
        if (!withFundamentals.isEmpty()) {
            canvas.heading2("Fundamentals");
            for (ResearchReportData.Entry entry : withFundamentals) {
                canvas.labeled(entry.symbol() + " company", value(entry.companyName()));
                canvas.labeled(entry.symbol() + " sector", value(entry.sector()));
                canvas.labeled(entry.symbol() + " market cap", value(entry.marketCap()));
                canvas.labeled(entry.symbol() + " P/E ratio", value(entry.peRatio()));
            }
        }

        List<String[]> riskRows = new ArrayList<>();
        for (ResearchReportData.Entry entry : data.entries()) {
            if (entry.hasRisk()) {
                riskRows.add(new String[]{
                        entry.symbol(),
                        value(entry.riskScore()),
                        value(entry.riskCategory()),
                        value(entry.volatility()),
                        value(entry.maxDrawdown())});
            }
        }
        if (!riskRows.isEmpty()) {
            canvas.heading2("Risk Analysis");
            canvas.table(new String[]{"Symbol", "Risk Score", "Risk Level", "Volatility", "Max Drawdown"},
                    new float[]{0.16f, 0.18f, 0.18f, 0.24f, 0.24f}, riskRows);
            for (ResearchReportData.Entry entry : data.entries()) {
                if (entry.beta() != null || entry.sharpe() != null) {
                    canvas.labeled(entry.symbol() + " beta", value(entry.beta()));
                    canvas.labeled(entry.symbol() + " Sharpe ratio", value(entry.sharpe()));
                }
            }
        }
    }

    private void renderNewsAndSentiment(Canvas canvas, ResearchReportData data) throws IOException {
        boolean anyNews = data.entries().stream().anyMatch(e -> e.newsCount() > 0);
        boolean anySentiment = data.entries().stream().anyMatch(ResearchReportData.Entry::hasSentiment);
        if (!anyNews && !anySentiment && blank(data.newsSummary())) {
            return;
        }
        canvas.heading1("NEWS & SENTIMENT");
        int total = data.entries().stream().mapToInt(ResearchReportData.Entry::newsCount).sum();
        canvas.para("Articles analyzed: " + total + ".");

        List<String[]> rows = new ArrayList<>();
        for (ResearchReportData.Entry entry : data.entries()) {
            if (entry.sentimentAvailable()) {
                rows.add(new String[]{
                        entry.symbol(),
                        String.valueOf(entry.analyzedCount()),
                        String.valueOf(entry.positiveCount()),
                        String.valueOf(entry.neutralCount()),
                        String.valueOf(entry.negativeCount()),
                        value(entry.sentimentScore())});
            }
        }
        if (!rows.isEmpty()) {
            canvas.heading2("Sentiment Analysis");
            canvas.table(new String[]{"Symbol", "Articles", "Positive", "Neutral", "Negative", "Score"},
                    new float[]{0.18f, 0.14f, 0.15f, 0.15f, 0.15f, 0.23f}, rows);
        }
        for (ResearchReportData.Entry entry : data.entries()) {
            if (entry.sentimentAvailable()) {
                canvas.labeled(entry.symbol() + " sentiment",
                        entry.sentimentLabel() + " (score " + value(entry.sentimentScore())
                                + ", confidence " + value(entry.sentimentConfidence()) + ")");
            }
        }
        for (ResearchReportData.Entry entry : data.entries()) {
            if (entry.hasSentiment() && !entry.sentimentAvailable()) {
                canvas.labeled(entry.symbol() + " sentiment",
                        "Unavailable (" + value(entry.sentimentReason()) + ")");
            }
        }
        data.entries().stream()
                .map(ResearchReportData.Entry::methodology)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .ifPresent(methodology -> {
                    try {
                        canvas.note("Methodology: " + methodology);
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                });

        if (!blank(data.newsSummary())) {
            canvas.heading2("News Overview");
            canvas.para(data.newsSummary());
        }
        for (ResearchReportData.Entry entry : data.entries()) {
            if (!entry.headlines().isEmpty()) {
                canvas.heading2("Headlines — " + entry.symbol());
                List<String> shown = entry.headlines().stream().limit(MAX_HEADLINES_PER_TICKER).toList();
                for (String headline : shown) {
                    canvas.bullet(headline);
                }
                if (entry.headlines().size() > shown.size()) {
                    canvas.note("…and " + (entry.headlines().size() - shown.size()) + " more.");
                }
            }
        }
    }

    private void renderInterpretation(Canvas canvas, ResearchReportData data) throws IOException {
        if (blank(data.interpretation())) {
            return;
        }
        canvas.heading1("AI INTERPRETATION");
        canvas.para(data.interpretation());
    }

    private void renderGapsAndLimitations(Canvas canvas, ResearchReportData data) throws IOException {
        List<String> gaps = data.entries().stream()
                .flatMap(e -> e.gaps().stream().map(g -> e.symbol() + ": " + g))
                .toList();
        if (gaps.isEmpty() && data.guardRedactions() == 0) {
            return;
        }
        canvas.heading1("DATA GAPS & LIMITATIONS");
        for (String gap : gaps) {
            canvas.bullet(gap);
        }
        if (data.guardRedactions() > 0) {
            canvas.bullet("Safety guard redacted " + data.guardRedactions()
                    + " ungrounded figure(s) from the AI interpretation.");
        }
        canvas.bullet("Metrics are computed deterministically from retrieved market data.");
        canvas.bullet("News sentiment is a keyword baseline, not professional financial analysis.");
    }

    private void renderDisclaimer(Canvas canvas, ResearchReportData data) throws IOException {
        canvas.heading1("DISCLAIMER");
        String disclaimer = blank(data.disclaimer())
                ? "This report is for informational and educational purposes only and does not "
                + "constitute financial advice, investment advice, or a recommendation to buy or "
                + "sell securities."
                : data.disclaimer();
        canvas.disclaimer(disclaimer);
    }

    // ------------------------------------------------------------------ helpers

    static String value(String raw) {
        return raw == null || raw.isBlank() ? UNAVAILABLE : raw.strip();
    }

    private static String signed(String change) {
        if (change == null || change.isBlank()) {
            return UNAVAILABLE;
        }
        String stripped = change.strip();
        return stripped.startsWith("-") || stripped.startsWith("+") ? stripped : "+" + stripped;
    }

    private static String pct(String percent) {
        if (percent == null || percent.isBlank()) {
            return UNAVAILABLE;
        }
        String stripped = percent.strip();
        return stripped.endsWith("%") ? stripped : stripped + "%";
    }

    private static String quote(String request) {
        return "\"" + request + "\"";
    }

    private static String stamp(Instant instant) {
        return instant == null ? UNAVAILABLE : instant.toString();
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * WinAnsi (PDFBox standard-14 fonts) cannot encode arbitrary Unicode —
     * unmappable glyphs throw inside {@code showText}. Map the common cases,
     * drop the rest to '?'. Control characters (except tab/newline, handled
     * upstream) are removed.
     */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String mapped = text
                .replace("\u2013", "-").replace("\u2014", "-")
                .replace("\u2018", "'").replace("\u2019", "'")
                .replace("\u201C", "\"").replace("\u201D", "\"")
                .replace("\u2022", "-").replace("\u00B7", "-")
                .replace("\u2026", "...").replace("\u00A0", " ")
                .replace("\u20B9", "Rs.").replace("\u2192", "->");
        StringBuilder safe = new StringBuilder(mapped.length());
        for (int i = 0; i < mapped.length(); i++) {
            char c = mapped.charAt(i);
            if (c == '\n' || c == '\t') {
                safe.append(c);
            } else if (c >= 0x20 && c <= 0x7E) {
                safe.append(c);
            } else if (c >= 0xA0 && c <= 0xFF && !(c >= 0x80 && c <= 0x9F)) {
                safe.append(c);
            } else {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    // ------------------------------------------------------------------ canvas

    /**
     * Single-pass flow layout: cursor tracking, page breaks, wrapped text,
     * tables with repeating headers, and per-page header/footer/page numbers.
     */
    private class Canvas {
        private final PDDocument document;
        private final String researchId;
        private final Fonts fonts;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        private float contentWidth() {
            return PDRectangle.LETTER.getWidth() - 2 * MARGIN_SIDE;
        }

        Canvas(PDDocument document, String researchId, Fonts fonts) throws IOException {
            this.document = document;
            this.researchId = researchId;
            this.fonts = fonts;
            newPage();
        }

        Fonts fonts() {
            return fonts;
        }

        /**
         * Text cleanup for the active font set: Unicode fonts render almost
         * everything, so only control characters are stripped and glyphs
         * present in NEITHER bundled font are dropped (never '?', never a
         * crash). The WinAnsi fallback still needs the lossy {@link #sanitize}
         * mapping.
         */
        String clean(String text) {
            if (text == null) {
                return "";
            }
            if (!fonts.unicode()) {
                return sanitize(text);
            }
            StringBuilder safe = new StringBuilder(text.length());
            text.codePoints().forEach(cp -> {
                if (cp == '\n' || cp == '\t') {
                    safe.appendCodePoint(cp);
                } else if (cp == '\r') {
                    safe.append('\n');
                } else if (!Character.isISOControl(cp)
                        && (Coverage.inPrimary(cp) || Coverage.inFallback(cp))) {
                    safe.appendCodePoint(cp);
                }
            });
            return safe.toString();
        }

        /**
         * Renders text with per-run font fallback: maximal runs covered by the
         * requested (DejaVu) style stay styled; gaps switch to the Unifont
         * fallback (unstyled) at an advancing cursor. Every code point reaching
         * here passed {@link #clean}, so both branches have the glyph.
         */
        private void showRuns(String text, PDFont font, float size, Color color,
                              float x, float atY) throws IOException {
            float cursor = x;
            int i = 0;
            int n = text.length();
            while (i < n) {
                int cp = text.codePointAt(i);
                boolean primary = Coverage.inPrimary(cp);
                int j = i + Character.charCount(cp);
                while (j < n) {
                    int next = text.codePointAt(j);
                    if (Coverage.inPrimary(next) != primary) {
                        break;
                    }
                    j += Character.charCount(next);
                }
                PDFont runFont = primary ? font : fonts.fallback();
                String run = text.substring(i, j);
                stream.beginText();
                stream.setFont(runFont, size);
                stream.setNonStrokingColor(color);
                stream.newLineAtOffset(cursor, atY);
                stream.showText(run);
                stream.endText();
                cursor += runFont.getStringWidth(run) / 1000f * size;
                i = j;
            }
        }

        void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            pageNumber++;
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.LETTER.getHeight() - MARGIN_TOP;
            drawHeaderFooter();
        }

        private void drawHeaderFooter() throws IOException {
            lineAt("FINAGENT - Financial Research Report", fonts.regular(), 8, GRAY,
                    MARGIN_SIDE, PDRectangle.LETTER.getHeight() - 40);
            lineAt("Research ID: " + researchId + "  |  Page " + pageNumber,
                    fonts.regular(), 8, GRAY, MARGIN_SIDE, 32);
        }

        private void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN_BOTTOM) {
                newPage();
            }
        }

        private void lineAt(String text, PDFont font, float size, Color color, float x, float atY)
                throws IOException {
            showRuns(text.isEmpty() ? " " : text, font, size, color, x, atY);
        }

        private List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : clean(text).split("\n", -1)) {
                String remaining = paragraph.strip();
                if (remaining.isEmpty()) {
                    lines.add("");
                    continue;
                }
                while (!remaining.isEmpty()) {
                    int split = fittingPrefix(remaining, font, size, width);
                    lines.add(remaining.substring(0, split).strip());
                    remaining = remaining.substring(split).stripLeading();
                }
            }
            return lines;
        }

        private int fittingPrefix(String text, PDFont font, float size, float width) throws IOException {
            if (widthOf(text, font, size) <= width) {
                return text.length();
            }
            // Binary search: the old linear scan was O(n^2) font-metric calls and
            // took minutes on multi-KB unbroken tokens (a report-generation DoS).
            int lo = 1;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (widthOf(text.substring(0, mid), font, size) <= width) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            int space = text.lastIndexOf(' ', lo);
            if (space > 0) {
                return space;
            }
            return Math.max(1, lo);
        }

        private float widthOf(String text, PDFont font, float size) throws IOException {
            if (!fonts.unicode()) {
                return font.getStringWidth(text) / 1000f * size;
            }
            // Mixed-script strings are measured per fallback run, matching showRuns.
            float total = 0;
            int i = 0;
            int n = text.length();
            while (i < n) {
                int cp = text.codePointAt(i);
                boolean primary = Coverage.inPrimary(cp);
                int j = i + Character.charCount(cp);
                while (j < n && Coverage.inPrimary(text.codePointAt(j)) == primary) {
                    j += Character.charCount(text.codePointAt(j));
                }
                PDFont runFont = primary ? font : fonts.fallback();
                total += runFont.getStringWidth(text.substring(i, j)) / 1000f * size;
                i = j;
            }
            return total;
        }

        void title(String text, float size, Color color) throws IOException {
            for (String lineContent : wrap(text, fonts.bold(), size, contentWidth())) {
                ensureSpace(size + 6);
                y -= size + 2;
                lineAt(lineContent, fonts.bold(), size, color, MARGIN_SIDE, y);
                y -= 4;
            }
        }

        void line(String text, PDFont font, float size, Color color) throws IOException {
            for (String lineContent : wrap(text, font, size, contentWidth())) {
                ensureSpace(size + 4);
                y -= size + 2;
                lineAt(lineContent.isEmpty() ? " " : lineContent, font, size, color, MARGIN_SIDE, y);
                y -= 2;
            }
        }

        void heading1(String text) throws IOException {
            gap(8);
            for (String lineContent : wrap(text, fonts.bold(), 14, contentWidth())) {
                ensureSpace(20);
                y -= 16;
                lineAt(lineContent, fonts.bold(), 14, ACCENT, MARGIN_SIDE, y);
                y -= 4;
            }
            rule();
            gap(4);
        }

        void heading2(String text) throws IOException {
            gap(6);
            for (String lineContent : wrap(text, fonts.bold(), 11.5f, contentWidth())) {
                ensureSpace(17);
                y -= 13.5f;
                lineAt(lineContent, fonts.bold(), 11.5f, Color.BLACK, MARGIN_SIDE, y);
                y -= 3;
            }
        }

        void para(String text) throws IOException {
            for (String lineContent : wrap(text, fonts.regular(), 9.5f, contentWidth())) {
                ensureSpace(13.5f);
                y -= 11.5f;
                lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.regular(), 9.5f, Color.BLACK, MARGIN_SIDE, y);
                y -= 2;
            }
            y -= 4;
        }

        void quote(String text) throws IOException {
            for (String lineContent : wrap(text, fonts.italic(), 9.5f, contentWidth() - 16)) {
                ensureSpace(13.5f);
                y -= 11.5f;
                lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.italic(), 9.5f, Color.BLACK, MARGIN_SIDE + 16, y);
                y -= 2;
            }
            y -= 4;
        }

        void note(String text) throws IOException {
            for (String lineContent : wrap(text, fonts.regular(), 8.5f, contentWidth())) {
                ensureSpace(12.5f);
                y -= 10.5f;
                lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.regular(), 8.5f, GRAY, MARGIN_SIDE, y);
                y -= 2;
            }
            y -= 2;
        }

        void disclaimer(String text) throws IOException {
            for (String lineContent : wrap(text, fonts.regular(), 9f, contentWidth())) {
                ensureSpace(13);
                y -= 11;
                lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.regular(), 9f, Color.BLACK, MARGIN_SIDE, y);
                y -= 2;
            }
        }

        void bullet(String text) throws IOException {
            for (String lineContent : wrap("- " + text, fonts.regular(), 9.5f, contentWidth() - 12)) {
                ensureSpace(13.5f);
                y -= 11.5f;
                lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.regular(), 9.5f, Color.BLACK, MARGIN_SIDE + 12, y);
                y -= 2;
            }
            y -= 1;
        }

        void labeled(String label, String text) throws IOException {
            String full = clean(label) + ": " + clean(text == null ? "" : text);
            List<String> lines = wrap(full, fonts.regular(), 9.5f, contentWidth());
            String head = clean(label) + ": ";
            float labelWidth = widthOf(head, fonts.bold(), 9.5f);
            boolean first = true;
            for (String lineContent : lines) {
                ensureSpace(13.5f);
                y -= 11.5f;
                if (first) {
                    first = false;
                    int headLength = Math.min(lineContent.length(), head.length());
                    String boldPart = lineContent.substring(0, headLength);
                    String rest = lineContent.substring(headLength);
                    showRuns(boldPart.isEmpty() ? " " : boldPart, fonts.bold(), 9.5f,
                            Color.BLACK, MARGIN_SIDE, y);
                    if (!rest.isBlank()) {
                        showRuns(rest.stripLeading(), fonts.regular(), 9.5f,
                                Color.BLACK, MARGIN_SIDE + labelWidth, y);
                    }
                } else {
                    lineAt(lineContent.isEmpty() ? " " : lineContent, fonts.regular(), 9.5f, Color.BLACK,
                            MARGIN_SIDE + labelWidth, y);
                }
                y -= 2;
            }
            y -= 1;
        }

        void rule() throws IOException {
            ensureSpace(8);
            y -= 4;
            stream.setStrokingColor(GRID);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN_SIDE, y);
            stream.lineTo(MARGIN_SIDE + contentWidth(), y);
            stream.stroke();
            y -= 4;
        }

        void gap(float points) throws IOException {
            if (y - points < MARGIN_BOTTOM) {
                newPage();
            } else {
                y -= points;
            }
        }

        void table(String[] headers, float[] fractions, List<String[]> rows) throws IOException {
            float[] widths = new float[fractions.length];
            for (int i = 0; i < fractions.length; i++) {
                widths[i] = fractions[i] * contentWidth();
            }
            final float size = 8.5f;
            final float leading = 11.5f;
            final float pad = 4;
            List<List<String>> headerLines = new ArrayList<>();
            for (String header : headers) {
                headerLines.add(wrap(header, fonts.bold(), size, 100000));
            }
            drawRow(headerLines, widths, size, leading, pad, true);
            for (String[] row : rows) {
                List<List<String>> cellLines = new ArrayList<>();
                float rowHeight = leading;
                for (int i = 0; i < headers.length; i++) {
                    String cell = i < row.length && row[i] != null ? row[i] : UNAVAILABLE;
                    List<String> wrapped = wrap(cell, fonts.regular(), size, widths[i] - 2 * pad);
                    if (wrapped.isEmpty()) {
                        wrapped = List.of(" ");
                    }
                    cellLines.add(wrapped);
                    rowHeight = Math.max(rowHeight, wrapped.size() * leading);
                }
                rowHeight += 2 * pad;
                int pageBefore = pageNumber;
                ensureSpace(rowHeight);
                if (pageNumber != pageBefore) {
                    // A page break mid-table: re-emit the column headers so the
                    // continued rows stay labeled (as documented for this report).
                    drawRow(headerLines, widths, size, leading, pad, true);
                }
                drawRow(cellLines, widths, size, leading, pad, false);
            }
            y -= 6;
        }

        private void drawRow(List<List<String>> cells, float[] widths, float size,
                             float leading, float pad, boolean header) throws IOException {
            float rowHeight = leading + 2 * pad;
            for (List<String> cell : cells) {
                rowHeight = Math.max(rowHeight, cell.size() * leading + 2 * pad);
            }
            ensureSpace(rowHeight);
            float x = MARGIN_SIDE;
            float top = y;
            PDFont font = header ? fonts.bold() : fonts.regular();
            if (header) {
                stream.setNonStrokingColor(TABLE_HEAD_FILL);
                stream.addRect(x, top - rowHeight, contentWidth(), rowHeight);
                stream.fill();
            }
            for (int i = 0; i < cells.size(); i++) {
                float cellY = top - pad - size;
                for (String lineContent : cells.get(i)) {
                    lineAt(lineContent.isEmpty() ? " " : lineContent, font, size,
                            header ? Color.WHITE : Color.BLACK, x + pad, cellY);
                    cellY -= leading;
                }
                x += widths[i];
            }
            stream.setStrokingColor(GRID);
            stream.setLineWidth(0.5f);
            x = MARGIN_SIDE;
            for (float width : widths) {
                stream.addRect(x, top - rowHeight, width, rowHeight);
                x += width;
            }
            stream.stroke();
            y = top - rowHeight;
        }
    }
}
