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
 * 将论文实验数据 PDF 中的 S2 隐私 prompt 样本转换为 CSV 文件。
 *
 * <p>默认读取 {@code file/paper/data/6.3/pdf/S2.pdf}，并输出到 {@code file/paper/data/6.3/csv/S2.csv}。
 * 输出列固定为：id、等级、类型、语言、prompt。</p>
 */
public final class S2PromptPdfToCsv {

    private static final Path DEFAULT_PDF = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/pdf/S2.pdf");
    private static final Path DEFAULT_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/csv/S2.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Pattern PROMPT_LINE = Pattern.compile("^(\\d{1,3})\\.\\s*(.+)$");
    private static final Pattern PAGE_MARKER = Pattern.compile("^--\\s*\\d+\\s+of\\s+\\d+\\s*--$");

    private S2PromptPdfToCsv() {
    }

    public static void main(String[] args) throws IOException {
        Path pdfPath = args.length > 0 ? Path.of(args[0]) : DEFAULT_PDF;
        Path csvPath = args.length > 1 ? Path.of(args[1]) : DEFAULT_CSV;

        List<Row> rows = parsePdf(pdfPath);
        if (rows.size() != 270) {
            throw new IllegalStateException("期望解析 270 条 prompt，实际解析到 " + rows.size() + " 条，请检查 PDF 格式");
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
        int expectedSourceNo = -1;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || PAGE_MARKER.matcher(line).matches()) {
                continue;
            }

            TypeInfo typeInfo = parseType(line);
            if (typeInfo != null) {
                currentPrompt = flushPrompt(rows, counters, currentPrompt);
                currentType = typeInfo;
                currentLanguage = null;
                expectedSourceNo = -1;
                continue;
            }

            String language = parseLanguage(line);
            if (language != null) {
                currentPrompt = flushPrompt(rows, counters, currentPrompt);
                currentLanguage = language;
                expectedSourceNo = -1;
                continue;
            }

            Matcher promptMatcher = PROMPT_LINE.matcher(line);
            if (promptMatcher.matches() && currentType != null && currentLanguage != null) {
                int sourceNo = Integer.parseInt(promptMatcher.group(1));
                if (expectedSourceNo < 0 || sourceNo == expectedSourceNo) {
                    currentPrompt = flushPrompt(rows, counters, currentPrompt);
                    currentPrompt = new PromptBuilder(currentType, currentLanguage, sourceNo, promptMatcher.group(2));
                    expectedSourceNo = sourceNo + 1;
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

    private static TypeInfo parseType(String line) {
        if (line.contains("地名实体") && line.contains("Location")) {
            return new TypeInfo("S2", "LOC", "地名实体 (Location)");
        }
        if (line.contains("组织机构名实体") && line.contains("Organization")) {
            return new TypeInfo("S2", "ORG", "组织机构名实体 (Organization)");
        }
        if (line.contains("手机号/固定电话") && line.contains("Phone Numbers")) {
            return new TypeInfo("S2", "PHONE", "手机号/固定电话 (Phone Numbers)");
        }
        if (line.contains("邮箱") && line.contains("Email Addresses")) {
            return new TypeInfo("S2", "EMAIL", "邮箱 (Email Addresses)");
        }
        if (line.contains("身份证号/通用身份标识") && line.contains("ID Numbers")) {
            return new TypeInfo("S2", "ID", "身份证号/通用身份标识 (ID Numbers)");
        }
        if (line.contains("银行卡号") && line.contains("Bank Card Numbers")) {
            return new TypeInfo("S2", "BANK_CARD", "银行卡号 (Bank Card Numbers)");
        }
        if (line.contains("IP 地址") && line.contains("IP Addresses")) {
            return new TypeInfo("S2", "IP", "IP 地址 (IP Addresses)");
        }
        if (line.contains("MAC 地址") && line.contains("MAC Addresses")) {
            return new TypeInfo("S2", "MAC", "MAC 地址 (MAC Addresses)");
        }
        if (line.contains("护照号/社会安全号") && line.contains("Passport & SSN")) {
            return new TypeInfo("S2", "PASSPORT_SSN", "护照号/社会安全号 (Passport & SSN)");
        }
        return null;
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
        Files.createDirectories(csvPath.getParent());
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
