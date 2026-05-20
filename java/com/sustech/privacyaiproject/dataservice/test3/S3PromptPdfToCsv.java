package com.sustech.privacyaiproject.dataservice.test3;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将论文实验数据 PDF 中的 S3 高危密钥 prompt 样本转换为 CSV 文件。
 *
 * <p>默认读取 {@code file/paper/data/6.3/S3.pdf}，并在同目录下输出 {@code S3.csv}。
 * 输出列固定为：id、等级、类型、语言、prompt。</p>
 */
public final class S3PromptPdfToCsv {

    private static final Path DEFAULT_PDF = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/S3.pdf");
    private static final Path DEFAULT_CSV = DEFAULT_PDF.resolveSibling("S3.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Pattern PROMPT_LINE = Pattern.compile("^(\\d{1,3})\\.\\s*(.+)$");
    private static final Pattern PAGE_MARKER = Pattern.compile("^--\\s*\\d+\\s+of\\s+\\d+\\s*--$");

    private S3PromptPdfToCsv() {
    }

    public static void main(String[] args) throws IOException {
        Path pdfPath = args.length > 0 ? Path.of(args[0]) : DEFAULT_PDF;
        Path csvPath = args.length > 1 ? Path.of(args[1]) : pdfPath.resolveSibling(DEFAULT_CSV.getFileName());

        List<Row> rows = parsePdf(pdfPath);
        if (rows.size() != 100) {
            throw new IllegalStateException("期望解析 100 条 prompt，实际解析到 " + rows.size() + " 条，请检查 PDF 格式");
        }

        writeCsv(csvPath, rows);
        System.out.println("已生成 CSV: " + csvPath.toAbsolutePath());
    }

    private static List<Row> parsePdf(Path pdfPath) throws IOException {
        String text = extractText(pdfPath);
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();

        TypeInfo currentType = null;
        String currentLanguage = null;
        PromptBuilder currentPrompt = null;
        int expectedSourceNo = 1;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || PAGE_MARKER.matcher(line).matches()) {
                continue;
            }

            String language = parseLanguage(line);
            if (language != null) {
                currentPrompt = flushPrompt(rows, counters, currentPrompt);
                currentLanguage = language;
                continue;
            }

            TypeInfo typeInfo = parseType(line);
            if (typeInfo != null) {
                currentPrompt = flushPrompt(rows, counters, currentPrompt);
                currentType = typeInfo;
                continue;
            }

            Matcher promptMatcher = PROMPT_LINE.matcher(line);
            if (promptMatcher.matches() && currentType != null && currentLanguage != null) {
                int sourceNo = Integer.parseInt(promptMatcher.group(1));
                if (sourceNo == expectedSourceNo) {
                    currentPrompt = flushPrompt(rows, counters, currentPrompt);
                    currentPrompt = new PromptBuilder(currentType, currentLanguage, sourceNo, promptMatcher.group(2));
                    expectedSourceNo++;
                    continue;
                }
            }

            if (currentPrompt != null) {
                currentPrompt.append(line);
            }
        }

        flushPrompt(rows, counters, currentPrompt);
        return rows;
    }

    private static String extractText(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String parseLanguage(String line) {
        if (line.contains("中文测试集")) {
            return "CN";
        }
        if (line.contains("英文测试集")) {
            return "EN";
        }
        return null;
    }

    private static TypeInfo parseType(String line) {
        if (line.startsWith("1. S3 类：API Key / 访问令牌") || line.startsWith("5.1 API Keys")) {
            return new TypeInfo("S3", "API_KEY", "API Key / 访问令牌 (API Keys & Tokens)");
        }
        if (line.startsWith("2. S3 类：JWT") || line.startsWith("5.2 JWT")) {
            return new TypeInfo("S3", "JWT", "JWT (JSON Web Tokens)");
        }
        if (line.startsWith("3. S3 类：AWS 高危密钥") || line.startsWith("5.3 AWS Keys")) {
            return new TypeInfo("S3", "AWS_KEY", "AWS 高危密钥 (AWS Access / Secret Keys)");
        }
        if (line.startsWith("4. S3 类：私钥块") || line.startsWith("5.4 Private Key Blocks")) {
            return new TypeInfo("S3", "PRIVATE_KEY", "私钥块 (Private Key Blocks)");
        }
        return null;
    }

    private static PromptBuilder flushPrompt(List<Row> rows, Map<String, Integer> counters, PromptBuilder prompt) {
        if (prompt == null) {
            return null;
        }
        String counterKey = prompt.type.level + "_" + prompt.type.abbreviation + "_" + prompt.language;
        int sequence = counters.getOrDefault(counterKey, 0) + 1;
        counters.put(counterKey, sequence);
        String id = "%s_%s_%s_%03d".formatted(
                prompt.type.level,
                prompt.type.abbreviation,
                prompt.language,
                sequence
        );
        rows.add(new Row(id, prompt.type.level, prompt.type.displayName, prompt.language, prompt.text.toString()));
        return null;
    }

    private static void writeCsv(Path csvPath, List<Row> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("id,等级,类型,语言,prompt");
        for (Row row : rows) {
            lines.add(String.join(",",
                    csv(row.id),
                    csv(row.level),
                    csv(row.type),
                    csv(row.language),
                    csv(row.prompt)
            ));
        }
        String csvContent = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        byte[] contentBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] outputBytes = new byte[UTF_8_BOM.length + contentBytes.length];
        System.arraycopy(UTF_8_BOM, 0, outputBytes, 0, UTF_8_BOM.length);
        System.arraycopy(contentBytes, 0, outputBytes, UTF_8_BOM.length, contentBytes.length);
        Files.write(csvPath, outputBytes);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private record TypeInfo(String level, String abbreviation, String displayName) {
    }

    private record Row(String id, String level, String type, String language, String prompt) {
    }

    private static final class PromptBuilder {
        private final TypeInfo type;
        private final String language;
        @SuppressWarnings("unused")
        private final int sourceNo;
        private final StringBuilder text;

        private PromptBuilder(TypeInfo type, String language, int sourceNo, String firstLine) {
            this.type = type;
            this.language = language;
            this.sourceNo = sourceNo;
            this.text = new StringBuilder(firstLine);
        }

        private void append(String line) {
            if (containsCjk(text.toString()) || containsCjk(line)) {
                text.append(line);
            } else if (!text.isEmpty() && text.charAt(text.length() - 1) == '-') {
                text.append(line);
            } else {
                text.append(' ').append(line);
            }
        }
    }
}
