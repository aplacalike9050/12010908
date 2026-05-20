package com.sustech.privacyaiproject.dataservice.test3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 6.3 隐私识别与脱敏准确性实验脚本。
 *
 * <p>脚本读取 {@code file/paper/data/6.3/csv} 下的 S0/S1/S2/S3 样本 CSV，
 * 逐条调用 OpenAI 兼容接口，并用样本 ID 作为 {@code metadata.request_id}。
 * 调用完成后从 {@code privacy_ai_schema.privacy_audit_event} 查询审计结果，
 * 分别为每个输入文件生成 {@code S0_result.csv}、{@code S1_result.csv} 等汇总指标。</p>
 *
 * <p>实验前建议开启 Mock 实验模式：</p>
 * <pre>
 * GATEWAY_MOCK_MODEL_ENABLED=true
 * GATEWAY_MOCK_MODEL_RESPONSE_MODE=SANITIZED_INPUT
 * GATEWAY_MOCK_MODEL_SKIP_RESTORE=true
 * </pre>
 */
public final class PrivacyDetectionAccuracyExperiment {

    private static final Path DEFAULT_INPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/csv");
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/result");
    private static final List<String> FILE_NAMES = List.of("S0.csv", "S1.csv", "S2.csv", "S3.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Config config;
    private final HttpClient httpClient;

    private PrivacyDetectionAccuracyExperiment(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.httpTimeoutMs()))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv(args);
        PrivacyDetectionAccuracyExperiment experiment = new PrivacyDetectionAccuracyExperiment(config);
        experiment.run();
    }

    private void run() throws Exception {
        Files.createDirectories(config.outputDir());
        try (Connection connection = DriverManager.getConnection(config.dbUrl(), config.dbUsername(), config.dbPassword())) {
            for (String fileName : FILE_NAMES) {
                Path input = config.inputDir().resolve(fileName);
                if (!Files.exists(input)) {
                    System.out.println("跳过不存在的文件: " + input);
                    continue;
                }
                List<Sample> samples = readSamples(input);
                List<Evaluation> evaluations = new ArrayList<>();
                for (Sample sample : samples) {
                    callGateway(sample);
                    AuditRecord audit = waitAuditRecord(connection, sample.id());
                    evaluations.add(evaluate(sample, audit));
                }
                Path output = config.outputDir().resolve(fileName.replace(".csv", "_result.csv"));
                writeResult(output, summarize(evaluations));
                System.out.println("已生成实验结果: " + output.toAbsolutePath());
            }
        }
    }

    private List<Sample> readSamples(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = stripBom(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = parseCsvLine(line);
            if (columns.size() < 5) {
                throw new IllegalArgumentException("CSV列数不足: " + input + " line=" + (i + 1));
            }
            String id = columns.get(0);
            String type = typeKeyFromId(id);
            String level = expectedLevel(columns.get(1), type);
            String language = columns.get(3);
            String prompt = columns.get(4);
            samples.add(new Sample(id, level, type, language, prompt));
        }
        return samples;
    }

    private void callGateway(Sample sample) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("stream", false);
        body.put("credential_mode", "SYSTEM_DEFAULT");
        if (config.privacyPolicyId() != null && config.privacyPolicyId() > 0) {
            body.put("privacy_policy_id", config.privacyPolicyId());
        }
        body.put("metadata", Map.of(
                "request_id", sample.id(),
                "conversation_id", "exp-6-3-" + sample.id()
        ));
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", sample.prompt()
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
                       blocked,
                       success,
                       status_code,
                       finding_detail_json::text
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
                return new AuditRecord(
                        rs.getString("request_id"),
                        rs.getString("privacy_risk_level"),
                        rs.getInt("finding_count"),
                        rs.getBoolean("blocked"),
                        rs.getBoolean("success"),
                        rs.getInt("status_code"),
                        extractFindingTypes(rs.getString("finding_detail_json"))
                );
            }
        }
    }

    private static Set<String> extractFindingTypes(String findingJson) {
        Set<String> output = new HashSet<>();
        if (findingJson == null || findingJson.isBlank()) {
            return output;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(findingJson, new TypeReference<>() {
            });
            Object fields = root.get("fields");
            if (!(fields instanceof List<?> list)) {
                return output;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object type = map.get("type");
                    if (type != null) {
                        output.add(normalizeType(String.valueOf(type)));
                    }
                }
            }
        } catch (Exception ignored) {
            return output;
        }
        return output;
    }

    private Evaluation evaluate(Sample sample, AuditRecord audit) {
        boolean expectedPositive = !"S0".equalsIgnoreCase(sample.level());
        boolean auditMissing = audit == null;
        boolean detectedPositive = audit != null && (notBlank(audit.privacyRiskLevel()) || audit.findingCount() > 0 || !audit.findingTypes().isEmpty());
        boolean levelMatched = expectedPositive && audit != null && levelMatches(sample, audit);
        boolean typeMatched = expectedPositive && audit != null && typeMatches(sample.type(), audit.findingTypes());
        boolean blocked = audit != null && ("S3".equalsIgnoreCase(sample.level()) ? !audit.success() : audit.blocked() || audit.statusCode() >= 400);

        boolean tp = expectedPositive && detectedPositive;
        boolean fp = !expectedPositive && detectedPositive;
        boolean fn = expectedPositive && !detectedPositive;
        boolean tn = !expectedPositive && !detectedPositive;

        return new Evaluation(sample, auditMissing, detectedPositive, levelMatched, typeMatched, blocked, tp, fp, fn, tn);
    }

    private List<ResultRow> summarize(List<Evaluation> evaluations) {
        List<ResultRow> rows = new ArrayList<>();
        rows.add(toResultRow("OVERALL", "ALL", "ALL", "ALL", evaluations));

        Map<String, List<Evaluation>> byType = new TreeMap<>();
        Map<String, List<Evaluation>> byLanguage = new TreeMap<>();
        for (Evaluation evaluation : evaluations) {
            byType.computeIfAbsent(evaluation.sample().type(), ignored -> new ArrayList<>()).add(evaluation);
            byLanguage.computeIfAbsent(evaluation.sample().language(), ignored -> new ArrayList<>()).add(evaluation);
        }
        byType.forEach((type, items) -> rows.add(toResultRow("BY_TYPE", items.get(0).sample().level(), type, "ALL", items)));
        byLanguage.forEach((language, items) -> rows.add(toResultRow("BY_LANGUAGE", items.get(0).sample().level(), "ALL", language, items)));
        return rows;
    }

    private ResultRow toResultRow(String scope, String expectedLevel, String expectedType, String language, List<Evaluation> items) {
        int total = items.size();
        int expectedPositive = count(items, item -> !"S0".equalsIgnoreCase(item.sample().level()));
        int detectedPositive = count(items, Evaluation::detectedPositive);
        int tp = count(items, Evaluation::tp);
        int fp = count(items, Evaluation::fp);
        int fn = count(items, Evaluation::fn);
        int tn = count(items, Evaluation::tn);
        int levelMatch = count(items, Evaluation::levelMatched);
        int typeMatch = count(items, Evaluation::typeMatched);
        int blocked = count(items, Evaluation::blocked);
        int auditMissing = count(items, Evaluation::auditMissing);

        return new ResultRow(scope, expectedLevel, expectedType, language, total, expectedPositive, detectedPositive,
                tp, fp, fn, tn, levelMatch, typeMatch, precision(tp, fp), recall(tp, fn), f1(tp, fp, fn),
                accuracy(tp, tn, total), blocked, rate(blocked, Math.max(expectedPositive, 1)), auditMissing);
    }

    private void writeResult(Path output, List<ResultRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("统计范围,期望等级,期望类型,语言,样本数,应检出数,实际检出数,TP,FP,FN,TN,等级匹配数,类型匹配数,Precision,Recall,F1,Accuracy,拦截数,拦截率,审计缺失数");
        for (ResultRow row : rows) {
            lines.add(String.join(",",
                    csv(row.scope()),
                    csv(row.expectedLevel()),
                    csv(row.expectedType()),
                    csv(row.language()),
                    csv(row.total()),
                    csv(row.expectedPositive()),
                    csv(row.detectedPositive()),
                    csv(row.tp()),
                    csv(row.fp()),
                    csv(row.fn()),
                    csv(row.tn()),
                    csv(row.levelMatch()),
                    csv(row.typeMatch()),
                    csv(row.precision()),
                    csv(row.recall()),
                    csv(row.f1()),
                    csv(row.accuracy()),
                    csv(row.blocked()),
                    csv(row.blockRate()),
                    csv(row.auditMissing())
            ));
        }
        Files.createDirectories(output.getParent());
        String content = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] outputBytes = new byte[UTF_8_BOM.length + contentBytes.length];
        System.arraycopy(UTF_8_BOM, 0, outputBytes, 0, UTF_8_BOM.length);
        System.arraycopy(contentBytes, 0, outputBytes, UTF_8_BOM.length, contentBytes.length);
        Files.write(output, outputBytes);
    }

    private static boolean typeMatches(String expectedType, Set<String> actualTypes) {
        String expected = normalizeType(expectedType);
        if ("ID".equals(expected)) {
            return actualTypes.contains("ID") || actualTypes.contains("ID_CARD");
        }
        if ("PHONE".equals(expected)) {
            return actualTypes.contains("PHONE") || actualTypes.contains("LANDLINE");
        }
        if ("IP".equals(expected)) {
            return actualTypes.contains("IP") || actualTypes.contains("IPV4") || actualTypes.contains("IPV6");
        }
        if ("PASSPORT_SSN".equals(expected)) {
            return actualTypes.contains("PASSPORT") || actualTypes.contains("SSN");
        }
        if ("API_KEY".equals(expected)) {
            return actualTypes.contains("API_KEY");
        }
        if ("AWS_KEY".equals(expected)) {
            return actualTypes.contains("AWS_KEY") || actualTypes.contains("AWS_ACCESS_KEY");
        }
        if ("PRIVATE_KEY".equals(expected)) {
            return actualTypes.contains("PRIVATE_KEY") || actualTypes.contains("PRIVATE_KEY_BLOCK");
        }
        return actualTypes.contains(expected);
    }

    private static boolean levelMatches(Sample sample, AuditRecord audit) {
        if ("S3".equalsIgnoreCase(sample.level())) {
            return !audit.success() && "S3".equalsIgnoreCase(nullToEmpty(audit.privacyRiskLevel()));
        }
        return sample.level().equalsIgnoreCase(nullToEmpty(audit.privacyRiskLevel()));
    }

    private static String expectedLevel(String csvLevel, String type) {
        String normalizedType = normalizeType(type);
        if ("LOC".equals(normalizedType) || "ORG".equals(normalizedType)) {
            return "S2";
        }
        if ("ID".equals(normalizedType) || "ID_CARD".equals(normalizedType)
                || "PHONE".equals(normalizedType) || "LANDLINE".equals(normalizedType)
                || "PASSPORT".equals(normalizedType) || "PASSPORT_SSN".equals(normalizedType)
                || "TRAVEL_PERMIT".equals(normalizedType) || "EMAIL".equals(normalizedType)
                || "BANK_CARD".equals(normalizedType) || "IP".equals(normalizedType)
                || "IPV4".equals(normalizedType) || "IPV6".equals(normalizedType)
                || "MAC".equals(normalizedType) || "SSN".equals(normalizedType)) {
            return "S2";
        }
        return csvLevel;
    }

    private static String typeKeyFromId(String id) {
        String normalized = id == null ? "" : id.trim();
        int cnIndex = normalized.lastIndexOf("_CN_");
        int enIndex = normalized.lastIndexOf("_EN_");
        int langIndex = Math.max(cnIndex, enIndex);
        int firstUnderscore = normalized.indexOf('_');
        if (firstUnderscore < 0 || langIndex <= firstUnderscore) {
            return "UNKNOWN";
        }
        return normalized.substring(firstUnderscore + 1, langIndex);
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.trim().toUpperCase(Locale.ROOT)
                .replace("ID_CARD", "ID")
                .replace("JWT_TOKEN", "JWT")
                .replace("AWS_ACCESS_KEY", "AWS_KEY")
                .replace("PRIVATE_KEY_BLOCK", "PRIVATE_KEY");
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

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int count(List<Evaluation> items, Bool<Evaluation> predicate) {
        int count = 0;
        for (Evaluation item : items) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }

    private static String precision(int tp, int fp) {
        return formatRate(tp + fp == 0 ? 0D : (double) tp / (tp + fp));
    }

    private static String recall(int tp, int fn) {
        return formatRate(tp + fn == 0 ? 0D : (double) tp / (tp + fn));
    }

    private static String f1(int tp, int fp, int fn) {
        double p = tp + fp == 0 ? 0D : (double) tp / (tp + fp);
        double r = tp + fn == 0 ? 0D : (double) tp / (tp + fn);
        return formatRate(p + r == 0 ? 0D : 2 * p * r / (p + r));
    }

    private static String accuracy(int tp, int tn, int total) {
        return formatRate(total == 0 ? 0D : (double) (tp + tn) / total);
    }

    private static String rate(int numerator, int denominator) {
        return formatRate(denominator == 0 ? 0D : (double) numerator / denominator);
    }

    private static String formatRate(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String csv(Object value) {
        String safe = value == null ? "" : String.valueOf(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record Config(Path inputDir, Path outputDir, String apiUrl, String apiKey, String model, Long privacyPolicyId,
                          String dbUrl, String dbUsername, String dbPassword, long auditWaitMs,
                          long auditPollIntervalMs, long httpTimeoutMs) {
        static Config fromEnv(String[] args) {
            return new Config(
                    args.length > 0 ? Path.of(args[0]) : Path.of(env("EXP63_INPUT_DIR", DEFAULT_INPUT_DIR.toString())),
                    args.length > 1 ? Path.of(args[1]) : Path.of(env("EXP63_OUTPUT_DIR", DEFAULT_OUTPUT_DIR.toString())),
                    env("EXP63_API_URL", "http://localhost:8080/v1/chat/completions"),
                    env("EXP63_API_KEY", "sk-pgw-postman-demo"),
                    env("EXP63_MODEL", "deepseek"),
                    parseLongOrNull(env("EXP63_PRIVACY_POLICY_ID", "8")),
                    env("EXP63_DB_URL", "jdbc:postgresql://192.168.31.107:5432/postgres"),
                    env("EXP63_DB_USERNAME", "postgres"),
                    env("EXP63_DB_PASSWORD", "sqlsql111"),
                    Long.parseLong(env("EXP63_AUDIT_WAIT_MS", "5000")),
                    Long.parseLong(env("EXP63_AUDIT_POLL_INTERVAL_MS", "200")),
                    Long.parseLong(env("EXP63_HTTP_TIMEOUT_MS", "30000"))
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

    private record Sample(String id, String level, String type, String language, String prompt) {
    }

    private record AuditRecord(String requestId, String privacyRiskLevel, int findingCount, boolean blocked,
                               boolean success, int statusCode, Set<String> findingTypes) {
    }

    private record Evaluation(Sample sample, boolean auditMissing, boolean detectedPositive, boolean levelMatched,
                              boolean typeMatched, boolean blocked, boolean tp, boolean fp, boolean fn, boolean tn) {
    }

    private record ResultRow(String scope, String expectedLevel, String expectedType, String language,
                             int total, int expectedPositive, int detectedPositive, int tp, int fp, int fn, int tn,
                             int levelMatch, int typeMatch, String precision, String recall, String f1,
                             String accuracy, int blocked, String blockRate, int auditMissing) {
    }

    @FunctionalInterface
    private interface Bool<T> {
        boolean test(T value);
    }
}
