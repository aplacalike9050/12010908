package com.sustech.privacyaiproject.dataservice.test5;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 6.5 长文本输入处理实验脚本。
 *
 * <p>脚本读取 {@code file/paper/data/6.5/csv/pdf-paths.csv}，将 PDF 文本整体作为一次
 * OpenAI 兼容请求的 user content，并根据审计表统计不同长度类别下的处理结果。</p>
 *
 * <p>A 类短文本结果由 6.3/6.4 实验复用，本脚本仅在汇总 CSV 中预留空行。</p>
 */
public final class LongTextInputExperiment {

    private static final Path DEFAULT_INPUT_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.5/csv/pdf-paths.csv");
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.5/result");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Config config;
    private final HttpClient httpClient;

    private LongTextInputExperiment(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.httpTimeoutMs()))
                .build();
    }

    public static void main(String[] args) throws Exception {
        new LongTextInputExperiment(Config.fromEnv(args)).run();
    }

    private void run() throws Exception {
        Files.createDirectories(config.outputDir());
        List<DetailRow> detailRows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(config.dbUrl(), config.dbUsername(), config.dbPassword())) {
            for (PdfSample sample : readSamples(config.inputCsv())) {
                String text = extractText(Path.of(sample.path()));
                callGateway(sample, text);
                AuditRecord audit = waitAuditRecord(connection, sample.id());
                detailRows.add(toDetailRow(sample, text, audit));
            }
        }
        writeDetailCsv(config.outputDir().resolve("long-text_detail.csv"), detailRows);
        writeSummaryCsv(config.outputDir().resolve("long-text_summary.csv"), summarize(detailRows));
        System.out.println("已生成长文本实验结果: " + config.outputDir().toAbsolutePath());
    }

    private List<PdfSample> readSamples(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        List<PdfSample> samples = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = stripBom(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = parseCsvLine(line);
            if (columns.size() < 4) {
                throw new IllegalArgumentException("CSV列数不足: " + input + " line=" + (i + 1));
            }
            samples.add(new PdfSample(columns.get(0), columns.get(1), columns.get(2), columns.get(3)));
        }
        return samples;
    }

    private String extractText(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document).trim();
        }
    }

    private void callGateway(PdfSample sample, String content) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("credential_mode", "SYSTEM_DEFAULT");
        if (config.privacyPolicyId() != null && config.privacyPolicyId() > 0) {
            body.put("privacy_policy_id", config.privacyPolicyId());
        }
        body.put("metadata", Map.of(
                "request_id", sample.id(),
                "conversation_id", "exp-6-5-" + sample.id(),
                "privacy_type", sample.privacyType(),
                "injection_type", sample.injectionType()
        ));
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", content
        )));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl()))
                .timeout(Duration.ofMillis(config.httpTimeoutMs()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private AuditRecord waitAuditRecord(Connection connection, String requestId) throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + config.auditWaitMs();
        AuditRecord record;
        do {
            record = queryAuditRecord(connection, requestId);
            if (record != null) {
                return record;
            }
            Thread.sleep(config.auditPollIntervalMs());
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private AuditRecord queryAuditRecord(Connection connection, String requestId) throws SQLException {
        String sql = """
                SELECT request_id,
                       privacy_risk_level,
                       finding_count,
                       prompt_injection_detected,
                       blocked,
                       success,
                       status_code,
                       error_message,
                       finding_detail_json::text,
                       prompt_injection_detail_json::text
                FROM privacy_ai_schema.privacy_audit_event
                WHERE request_id = ?
                ORDER BY create_time DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String findingJson = rs.getString("finding_detail_json");
                return new AuditRecord(
                        rs.getString("privacy_risk_level"),
                        extractFindingCount(findingJson),
                        rs.getBoolean("prompt_injection_detected"),
                        rs.getBoolean("blocked"),
                        rs.getBoolean("success"),
                        rs.getInt("status_code"),
                        rs.getString("error_message"),
                        extractFindingTypes(findingJson),
                        extractPromptSources(rs.getString("prompt_injection_detail_json"))
                );
            }
        }
    }

    private DetailRow toDetailRow(PdfSample sample, String text, AuditRecord audit) {
        String category = lengthCategory(sample.id(), text.length());
        boolean expectedReject = "D".equals(category) || containsAny(sample.privacyType(), "S3");
        boolean auditMissing = audit == null;
        boolean rejectedByLength = audit != null && !audit.success()
                && containsAny(audit.errorMessage(), "20000", "长度", "超过");
        boolean blockedOrFailed = audit != null && (audit.blocked() || audit.statusCode() >= 400 || !audit.success());
        boolean promptExpected = !sample.injectionType().isBlank() && !"无".equals(sample.injectionType());
        boolean privacyExpected = !sample.privacyType().isBlank() && !"无".equals(sample.privacyType());
        boolean promptOk = !promptExpected || (audit != null && audit.promptInjectionDetected());
        String privacyRiskLevel = audit == null ? "" : nullToEmpty(audit.privacyRiskLevel());
        boolean privacyOk = !privacyExpected || (audit != null && (!privacyRiskLevel.isBlank() || audit.findingCount() > 0));
        boolean expectedBehavior = audit != null && (expectedReject ? blockedOrFailed : audit.success() && promptOk && privacyOk);
        return new DetailRow(
                sample.id(),
                category,
                sample.privacyType(),
                sample.injectionType(),
                sample.path(),
                text.length(),
                expectedReject,
                auditMissing,
                privacyRiskLevel,
                audit == null ? 0 : audit.findingCount(),
                audit == null ? "" : String.join("|", audit.findingTypes()),
                audit != null && audit.promptInjectionDetected(),
                audit == null ? "" : String.join("|", audit.promptSources()),
                audit != null && audit.blocked(),
                audit != null && audit.success(),
                audit == null ? "" : String.valueOf(audit.statusCode()),
                rejectedByLength,
                expectedBehavior,
                audit == null ? "" : nullToEmpty(audit.errorMessage())
        );
    }

    private List<SummaryRow> summarize(List<DetailRow> details) {
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("A", "A类短文本，复用6.3/6.4数据，本脚本暂不统计", 0, 0, 0, 0, 0, 0, 0, "", "", ""));

        Map<String, List<DetailRow>> byCategory = new TreeMap<>();
        for (DetailRow detail : details) {
            byCategory.computeIfAbsent(detail.category(), ignored -> new ArrayList<>()).add(detail);
        }
        for (String category : List.of("B", "C", "D")) {
            List<DetailRow> items = byCategory.getOrDefault(category, List.of());
            int total = items.size();
            int success = count(items, DetailRow::success);
            int lengthRejected = count(items, DetailRow::rejectedByLength);
            int privacyDetected = count(items, item -> !item.privacyRiskLevel().isBlank() || item.findingCount() > 0);
            int promptDetected = count(items, DetailRow::promptInjectionDetected);
            int expectedBehavior = count(items, DetailRow::expectedBehavior);
            int auditMissing = count(items, DetailRow::auditMissing);
            rows.add(new SummaryRow(category, categoryDescription(category), total, success, lengthRejected,
                    privacyDetected, promptDetected, expectedBehavior, auditMissing,
                    rate(expectedBehavior, total), rate(auditMissing, total), categoryExpectedBehavior(category)));
        }
        return rows;
    }

    private void writeDetailCsv(Path output, List<DetailRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("id,长度类别,隐私类型,注入类型,路径,字符数,期望拒绝,审计缺失,隐私风险,命中字段数,命中类型,Prompt注入,Prompt命中来源,blocked,success,status_code,长度拒绝,符合预期,error_message");
        for (DetailRow row : rows) {
            lines.add(String.join(",",
                    csv(row.id()),
                    csv(row.category()),
                    csv(row.privacyType()),
                    csv(row.injectionType()),
                    csv(row.path()),
                    csv(row.charCount()),
                    csv(row.expectedReject()),
                    csv(row.auditMissing()),
                    csv(row.privacyRiskLevel()),
                    csv(row.findingCount()),
                    csv(row.findingTypes()),
                    csv(row.promptInjectionDetected()),
                    csv(row.promptSources()),
                    csv(row.blocked()),
                    csv(row.success()),
                    csv(row.statusCode()),
                    csv(row.rejectedByLength()),
                    csv(row.expectedBehavior()),
                    csv(row.errorMessage())
            ));
        }
        writeUtf8Bom(output, lines);
    }

    private void writeSummaryCsv(Path output, List<SummaryRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("长度类别,说明,样本数,成功处理数,长度拒绝数,隐私检出数,Prompt注入检出数,符合预期数,审计缺失数,符合预期率,审计缺失率,期望行为");
        for (SummaryRow row : rows) {
            lines.add(String.join(",",
                    csv(row.category()),
                    csv(row.description()),
                    csv(row.total()),
                    csv(row.success()),
                    csv(row.lengthRejected()),
                    csv(row.privacyDetected()),
                    csv(row.promptDetected()),
                    csv(row.expectedBehavior()),
                    csv(row.auditMissing()),
                    csv(row.expectedBehaviorRate()),
                    csv(row.auditMissingRate()),
                    csv(row.expected())
            ));
        }
        writeUtf8Bom(output, lines);
    }

    private void writeUtf8Bom(Path output, List<String> lines) throws IOException {
        Files.createDirectories(output.getParent());
        String content = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] outputBytes = new byte[UTF_8_BOM.length + contentBytes.length];
        System.arraycopy(UTF_8_BOM, 0, outputBytes, 0, UTF_8_BOM.length);
        System.arraycopy(contentBytes, 0, outputBytes, UTF_8_BOM.length, contentBytes.length);
        Files.write(output, outputBytes);
    }

    private static Set<String> extractFindingTypes(String findingJson) {
        Set<String> output = new TreeSet<>();
        if (findingJson == null || findingJson.isBlank()) {
            return output;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(findingJson, new TypeReference<>() {
            });
            Object fields = root.get("fields");
            if (fields instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Object type = map.get("type");
                        if (type != null) {
                            output.add(String.valueOf(type));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return output;
        }
        return output;
    }

    private static int extractFindingCount(String findingJson) {
        if (findingJson == null || findingJson.isBlank()) {
            return 0;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(findingJson, new TypeReference<>() {
            });
            Object fields = root.get("fields");
            return fields instanceof List<?> list ? list.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Set<String> extractPromptSources(String detailJson) {
        Set<String> output = new TreeSet<>();
        if (detailJson == null || detailJson.isBlank()) {
            return output;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(detailJson, new TypeReference<>() {
            });
            Object findings = root.get("findings");
            if (findings instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Object source = map.get("source");
                        if (source != null) {
                            output.add(String.valueOf(source));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return output;
        }
        return output;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString());
        return columns;
    }

    private static String lengthCategory(String id, int charCount) {
        if (id != null && id.startsWith("3000_")) {
            return "B";
        }
        if (id != null && id.startsWith("6000_")) {
            return "C";
        }
        if (id != null && id.startsWith("30000_")) {
            return "D";
        }
        if (charCount <= 512) {
            return "A";
        }
        if (charCount <= 4000) {
            return "B";
        }
        if (charCount <= 20000) {
            return "C";
        }
        return "D";
    }

    private static String categoryDescription(String category) {
        return switch (category) {
            case "B" -> "B类：512 token以上且小于4000字符，验证NER滑动窗口与全量正则/黑名单";
            case "C" -> "C类：4000-20000字符，验证跳过NER但保留S2/S3正则和Prompt黑名单";
            case "D" -> "D类：20000字符以上，验证系统直接拒绝处理";
            default -> "";
        };
    }

    private static String categoryExpectedBehavior(String category) {
        return switch (category) {
            case "B" -> "请求成功或被安全策略拦截，审计应记录隐私/Prompt命中";
            case "C" -> "请求成功或被安全策略拦截，NER跳过但S2/S3正则和Prompt黑名单仍生效";
            case "D" -> "请求失败并提示超过20000字符";
            default -> "";
        };
    }

    private static boolean containsAny(String source, String... values) {
        if (source == null) {
            return false;
        }
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static int count(List<DetailRow> items, Bool<DetailRow> predicate) {
        int count = 0;
        for (DetailRow item : items) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }

    private static String rate(int numerator, int denominator) {
        return denominator == 0 ? "" : String.format(Locale.ROOT, "%.4f", (double) numerator / denominator);
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String csv(Object value) {
        String safe = value == null ? "" : String.valueOf(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record Config(Path inputCsv, Path outputDir, String apiUrl, String apiKey, String model,
                          Long privacyPolicyId, String dbUrl, String dbUsername, String dbPassword,
                          long auditWaitMs, long auditPollIntervalMs, long httpTimeoutMs) {
        static Config fromEnv(String[] args) {
            return new Config(
                    args.length > 0 ? Path.of(args[0]) : Path.of(env("EXP65_INPUT_CSV", DEFAULT_INPUT_CSV.toString())),
                    args.length > 1 ? Path.of(args[1]) : Path.of(env("EXP65_OUTPUT_DIR", DEFAULT_OUTPUT_DIR.toString())),
                    env("EXP65_API_URL", "http://localhost:8080/v1/chat/completions"),
                    env("EXP65_API_KEY", "sk-pgw-postman-demo"),
                    env("EXP65_MODEL", "deepseek"),
                    parseLongOrNull(env("EXP65_PRIVACY_POLICY_ID", "8")),
                    env("EXP65_DB_URL", "jdbc:postgresql://192.168.31.107:5432/postgres"),
                    env("EXP65_DB_USERNAME", "postgres"),
                    env("EXP65_DB_PASSWORD", "sqlsql111"),
                    Long.parseLong(env("EXP65_AUDIT_WAIT_MS", "5000")),
                    Long.parseLong(env("EXP65_AUDIT_POLL_INTERVAL_MS", "200")),
                    Long.parseLong(env("EXP65_HTTP_TIMEOUT_MS", "60000"))
            );
        }

        private static String env(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static Long parseLongOrNull(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Long.parseLong(value.trim());
        }
    }

    private record PdfSample(String id, String privacyType, String injectionType, String path) {
    }

    private record AuditRecord(String privacyRiskLevel, int findingCount, boolean promptInjectionDetected,
                               boolean blocked, boolean success, int statusCode, String errorMessage,
                               Set<String> findingTypes, Set<String> promptSources) {
    }

    private record DetailRow(String id, String category, String privacyType, String injectionType, String path,
                             int charCount, boolean expectedReject, boolean auditMissing, String privacyRiskLevel,
                             int findingCount, String findingTypes, boolean promptInjectionDetected,
                             String promptSources, boolean blocked, boolean success, String statusCode,
                             boolean rejectedByLength, boolean expectedBehavior, String errorMessage) {
    }

    private record SummaryRow(String category, String description, int total, int success, int lengthRejected,
                              int privacyDetected, int promptDetected, int expectedBehavior, int auditMissing,
                              String expectedBehaviorRate, String auditMissingRate, String expected) {
    }

    @FunctionalInterface
    private interface Bool<T> {
        boolean test(T value);
    }
}
