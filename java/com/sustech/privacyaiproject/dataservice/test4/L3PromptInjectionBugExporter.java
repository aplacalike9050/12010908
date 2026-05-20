package com.sustech.privacyaiproject.dataservice.test4;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 6.4 L3 隐蔽注入异常样本导出脚本。
 *
 * <p>脚本使用 {@code file/paper/data/6.4/result/Block/L3_result.csv} 对应的 Block 实验口径，
 * 根据 {@code L3.csv} 中的样本 ID 查询最新审计记录，导出未被模型检测或不符合 Block 预期的样本。</p>
 */
public final class L3PromptInjectionBugExporter {

    private static final Path DEFAULT_SOURCE_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.4/csv/L3.csv");
    private static final Path DEFAULT_BLOCK_RESULT_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.4/result/Block/L3_result.csv");
    private static final Path DEFAULT_OUTPUT_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.4/bug/L3_block_bug.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Config config;

    private L3PromptInjectionBugExporter(Config config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        new L3PromptInjectionBugExporter(Config.fromEnv(args)).run();
    }

    private void run() throws Exception {
        if (!Files.exists(config.blockResultCsv())) {
            throw new IllegalStateException("Block模式L3结果文件不存在: " + config.blockResultCsv());
        }
        List<BugRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(config.dbUrl(), config.dbUsername(), config.dbPassword())) {
            for (Sample sample : readSamples(config.sourceCsv())) {
                AuditRecord audit = queryAuditRecord(connection, sample.id());
                List<String> reasons = mismatchReasons(audit);
                if (!reasons.isEmpty()) {
                    rows.add(toBugRow(sample, audit, reasons));
                }
            }
        }
        writeCsv(config.outputCsv(), rows);
        System.out.println("已导出 L3 Block 异常样本: " + config.outputCsv().toAbsolutePath() + " count=" + rows.size());
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
            samples.add(new Sample(columns.get(0), columns.get(1), columns.get(2), columns.get(3), columns.get(4)));
        }
        return samples;
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

    private List<String> mismatchReasons(AuditRecord audit) {
        List<String> reasons = new ArrayList<>();
        if (audit == null) {
            reasons.add("AUDIT_MISSING");
            return reasons;
        }
        if (!audit.promptInjectionDetected()) {
            reasons.add("PROMPT_INJECTION_NOT_DETECTED");
        }
        if (!audit.modelHit()) {
            reasons.add("MODEL_NOT_HIT");
        }
        if (!(audit.blocked() || audit.statusCode() >= 400 || !audit.success())) {
            reasons.add("BLOCK_EXPECTED_BUT_PASSED");
        }
        if (!audit.success() && !(audit.blocked() || audit.statusCode() >= 400)) {
            reasons.add("REQUEST_FAILED_NOT_BLOCKED");
        }
        return reasons;
    }

    private BugRow toBugRow(Sample sample, AuditRecord audit, List<String> reasons) {
        return new BugRow(
                sample.id(),
                sample.level(),
                sample.type(),
                sample.language(),
                sample.prompt(),
                String.join(";", reasons),
                audit != null && audit.promptInjectionDetected(),
                audit != null && audit.heuristicHit(),
                audit != null && audit.modelHit(),
                audit != null && audit.blocked(),
                audit != null && audit.success(),
                audit == null ? "" : String.valueOf(audit.statusCode()),
                audit == null ? "" : audit.action(),
                audit == null ? "" : nullToEmpty(audit.errorMessage())
        );
    }

    private void writeCsv(Path output, List<BugRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("id,等级,类型,语言,prompt,问题类型,prompt注入检测,黑名单命中,模型命中,blocked,success,status_code,action,error_message");
        for (BugRow row : rows) {
            lines.add(String.join(",",
                    csv(row.id()),
                    csv(row.level()),
                    csv(row.type()),
                    csv(row.language()),
                    csv(row.prompt()),
                    csv(row.reason()),
                    csv(row.promptInjectionDetected()),
                    csv(row.heuristicHit()),
                    csv(row.modelHit()),
                    csv(row.blocked()),
                    csv(row.success()),
                    csv(row.statusCode()),
                    csv(row.action()),
                    csv(row.errorMessage())
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

    private record Config(Path sourceCsv, Path blockResultCsv, Path outputCsv,
                          String dbUrl, String dbUsername, String dbPassword) {
        static Config fromEnv(String[] args) {
            return new Config(
                    args.length > 0 ? Path.of(args[0]) : Path.of(env("EXP64_L3_SOURCE_CSV", DEFAULT_SOURCE_CSV.toString())),
                    args.length > 1 ? Path.of(args[1]) : Path.of(env("EXP64_L3_BLOCK_RESULT_CSV", DEFAULT_BLOCK_RESULT_CSV.toString())),
                    args.length > 2 ? Path.of(args[2]) : Path.of(env("EXP64_L3_BUG_OUTPUT_CSV", DEFAULT_OUTPUT_CSV.toString())),
                    env("EXP64_DB_URL", "jdbc:postgresql://192.168.31.107:5432/postgres"),
                    env("EXP64_DB_USERNAME", "postgres"),
                    env("EXP64_DB_PASSWORD", "sqlsql111")
            );
        }

        private static String env(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }

    private record Sample(String id, String level, String type, String language, String prompt) {
    }

    private record AuditRecord(boolean promptInjectionDetected, boolean blocked, boolean success, int statusCode,
                               String errorMessage, boolean heuristicHit, boolean modelHit, String action) {
    }

    private record PromptDetail(boolean heuristicHit, boolean modelHit, String action) {
    }

    private record BugRow(String id, String level, String type, String language, String prompt, String reason,
                          boolean promptInjectionDetected, boolean heuristicHit, boolean modelHit, boolean blocked,
                          boolean success, String statusCode, String action, String errorMessage) {
    }
}
