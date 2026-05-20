package com.sustech.privacyaiproject.dataservice.test4;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 6.4 Prompt 注入检测实验脚本。
 *
 * <p>脚本读取 {@code file/paper/data/6.4/csv} 下的 L0/L2/L3 样本 CSV，
 * 逐条调用 OpenAI 兼容接口，并用样本 ID 作为 {@code metadata.request_id}。
 * 调用完成后查询 {@code privacy_ai_schema.privacy_audit_event}，按文件输出检测指标。</p>
 */
public final class PromptInjectionDetectionExperiment {

    private static final Path DEFAULT_INPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.4/csv");
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.4/result");
    private static final List<String> FILE_NAMES = List.of("L0.csv", "L2.csv", "L3.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Config config;
    private final HttpClient httpClient;

    private PromptInjectionDetectionExperiment(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.httpTimeoutMs()))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv(args);
        new PromptInjectionDetectionExperiment(config).run();
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
                List<Evaluation> evaluations = new ArrayList<>();
                for (Sample sample : readSamples(input)) {
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
            String level = columns.get(1);
            String type = typeKeyFromId(id);
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
                "conversation_id", "exp-6-4-" + sample.id()
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
                       prompt_injection_detected,
                       blocked,
                       success,
                       status_code,
                       error_message,
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
                PromptDetail detail = parsePromptDetail(rs.getString("prompt_injection_detail_json"));
                return new AuditRecord(
                        rs.getString("request_id"),
                        rs.getBoolean("prompt_injection_detected"),
                        rs.getBoolean("blocked"),
                        rs.getBoolean("success"),
                        rs.getInt("status_code"),
                        rs.getString("error_message"),
                        detail.heuristicHit(),
                        detail.modelHit(),
                        detail.action()
                );
            }
        }
    }

    private static PromptDetail parsePromptDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return new PromptDetail(false, false, "");
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(detailJson, new TypeReference<>() {
            });
            String action = String.valueOf(root.getOrDefault("action", ""));
            boolean heuristicHit = false;
            boolean modelHit = false;
            Object findings = root.get("findings");
            if (findings instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Object sourceValue = map.get("source");
                        String source = sourceValue == null ? "" : String.valueOf(sourceValue);
                        if ("HEURISTIC_RULE".equalsIgnoreCase(source)) {
                            heuristicHit = true;
                        }
                        if ("MODEL".equalsIgnoreCase(source)) {
                            modelHit = true;
                        }
                    }
                }
            }
            return new PromptDetail(heuristicHit, modelHit, action);
        } catch (Exception ignored) {
            return new PromptDetail(false, false, "");
        }
    }

    private Evaluation evaluate(Sample sample, AuditRecord audit) {
        boolean expectedPositive = !"L0".equalsIgnoreCase(sample.level());
        boolean auditMissing = audit == null;
        boolean detectedPositive = audit != null && audit.promptInjectionDetected();
        boolean heuristicHit = audit != null && audit.heuristicHit();
        boolean modelHit = audit != null && audit.modelHit();
        boolean blocked = audit != null && (audit.blocked() || audit.statusCode() >= 400);
        boolean recordPassed = audit != null && detectedPositive && audit.success() && !blocked;
        boolean requestFailed = audit != null && !audit.success() && !blocked;

        boolean tp = expectedPositive && detectedPositive;
        boolean fp = !expectedPositive && detectedPositive;
        boolean fn = expectedPositive && !detectedPositive;
        boolean tn = !expectedPositive && !detectedPositive;

        return new Evaluation(sample, auditMissing, detectedPositive, heuristicHit, modelHit, blocked,
                recordPassed, requestFailed, tp, fp, fn, tn);
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
        int expectedPositive = count(items, item -> !"L0".equalsIgnoreCase(item.sample().level()));
        int detectedPositive = count(items, Evaluation::detectedPositive);
        int tp = count(items, Evaluation::tp);
        int fp = count(items, Evaluation::fp);
        int fn = count(items, Evaluation::fn);
        int tn = count(items, Evaluation::tn);
        int heuristicHits = count(items, Evaluation::heuristicHit);
        int modelHits = count(items, Evaluation::modelHit);
        int blocked = count(items, Evaluation::blocked);
        int recordPassed = count(items, Evaluation::recordPassed);
        int requestFailed = count(items, Evaluation::requestFailed);
        int auditMissing = count(items, Evaluation::auditMissing);

        return new ResultRow(scope, expectedLevel, expectedType, language, total, expectedPositive, detectedPositive,
                tp, fp, fn, tn, precision(tp, fp), recall(tp, fn), f1(tp, fp, fn), accuracy(tp, tn, total),
                rate(fp, Math.max(total - expectedPositive, 1)), heuristicHits, rate(heuristicHits, Math.max(expectedPositive, 1)),
                modelHits, rate(modelHits, Math.max(expectedPositive, 1)), blocked,
                rate(blocked, Math.max(expectedPositive, 1)), recordPassed,
                rate(recordPassed, Math.max(expectedPositive, 1)), requestFailed, auditMissing);
    }

    private void writeResult(Path output, List<ResultRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("统计范围,期望等级,期望类型,语言,样本数,应检出数,实际检出数,TP,FP,FN,TN,Precision,Recall,F1,Accuracy,误报率,黑名单命中数,黑名单命中率,模型命中数,模型命中率,拦截数,BLOCK拦截率,RECORD放行审计数,RECORD放行审计率,请求失败数,审计缺失数");
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
                    csv(row.precision()),
                    csv(row.recall()),
                    csv(row.f1()),
                    csv(row.accuracy()),
                    csv(row.falsePositiveRate()),
                    csv(row.heuristicHits()),
                    csv(row.heuristicHitRate()),
                    csv(row.modelHits()),
                    csv(row.modelHitRate()),
                    csv(row.blocked()),
                    csv(row.blockRate()),
                    csv(row.recordPassed()),
                    csv(row.recordPassRate()),
                    csv(row.requestFailed()),
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

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
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
                    args.length > 0 ? Path.of(args[0]) : Path.of(env("EXP64_INPUT_DIR", DEFAULT_INPUT_DIR.toString())),
                    args.length > 1 ? Path.of(args[1]) : Path.of(env("EXP64_OUTPUT_DIR", DEFAULT_OUTPUT_DIR.toString())),
                    env("EXP64_API_URL", "http://localhost:8080/v1/chat/completions"),
                    env("EXP64_API_KEY", "sk-pgw-postman-demo"),
                    env("EXP64_MODEL", "deepseek"),
                    parseLongOrNull(env("EXP64_PRIVACY_POLICY_ID", "8")),
                    env("EXP64_DB_URL", "jdbc:postgresql://192.168.31.107:5432/postgres"),
                    env("EXP64_DB_USERNAME", "postgres"),
                    env("EXP64_DB_PASSWORD", "sqlsql111"),
                    Long.parseLong(env("EXP64_AUDIT_WAIT_MS", "5000")),
                    Long.parseLong(env("EXP64_AUDIT_POLL_INTERVAL_MS", "200")),
                    Long.parseLong(env("EXP64_HTTP_TIMEOUT_MS", "30000"))
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

    private record AuditRecord(String requestId, boolean promptInjectionDetected, boolean blocked,
                               boolean success, int statusCode, String errorMessage,
                               boolean heuristicHit, boolean modelHit, String action) {
    }

    private record PromptDetail(boolean heuristicHit, boolean modelHit, String action) {
    }

    private record Evaluation(Sample sample, boolean auditMissing, boolean detectedPositive,
                              boolean heuristicHit, boolean modelHit, boolean blocked, boolean recordPassed,
                              boolean requestFailed, boolean tp, boolean fp, boolean fn, boolean tn) {
    }

    private record ResultRow(String scope, String expectedLevel, String expectedType, String language,
                             int total, int expectedPositive, int detectedPositive, int tp, int fp, int fn, int tn,
                             String precision, String recall, String f1, String accuracy, String falsePositiveRate,
                             int heuristicHits, String heuristicHitRate, int modelHits, String modelHitRate,
                             int blocked, String blockRate, int recordPassed, String recordPassRate,
                             int requestFailed, int auditMissing) {
    }

    @FunctionalInterface
    private interface Bool<T> {
        boolean test(T value);
    }
}
