package com.sustech.privacyaiproject.dataservice.test3;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 6.3 实验异常样本导出脚本。
 *
 * <p>脚本不发起 API 调用，只根据源 prompt CSV 和数据库审计结果，筛出不符合预期的样本，
 * 输出到 {@code file/paper/data/6.3/bug} 目录。</p>
 */
public final class PrivacyDetectionBugDatasetExporter {

    private static final Path DEFAULT_INPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/csv");
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.3/bug");
    private static final List<String> FILE_NAMES = List.of("S0.csv", "S1.csv", "S2.csv", "S3.csv");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Config config;

    private PrivacyDetectionBugDatasetExporter(Config config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv(args);
        new PrivacyDetectionBugDatasetExporter(config).run();
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
                List<BugRow> bugRows = new ArrayList<>();
                for (Sample sample : readSamples(input)) {
                    AuditRecord audit = queryAuditRecord(connection, sample.id());
                    List<String> reasons = mismatchReasons(sample, audit);
                    if (!reasons.isEmpty()) {
                        bugRows.add(toBugRow(sample, audit, reasons));
                    }
                }
                Path output = config.outputDir().resolve(fileName.replace(".csv", "_bug.csv"));
                writeBugCsv(output, bugRows);
                System.out.println("已导出异常样本: " + output.toAbsolutePath() + " count=" + bugRows.size());
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
            samples.add(new Sample(
                    id,
                    expectedLevel(columns.get(1), type),
                    type,
                    columns.get(3),
                    columns.get(4)
            ));
        }
        return samples;
    }

    private AuditRecord queryAuditRecord(Connection connection, String requestId) throws SQLException {
        String sql = """
                SELECT request_id,
                       privacy_risk_level,
                       finding_count,
                       blocked,
                       success,
                       status_code,
                       error_message,
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
                        rs.getString("error_message"),
                        extractFindingTypes(rs.getString("finding_detail_json"))
                );
            }
        }
    }

    private List<String> mismatchReasons(Sample sample, AuditRecord audit) {
        List<String> reasons = new ArrayList<>();
        if (audit == null) {
            reasons.add("AUDIT_MISSING");
            return reasons;
        }

        boolean expectedPositive = !"S0".equalsIgnoreCase(sample.level());
        boolean detectedPositive = notBlank(audit.privacyRiskLevel()) || audit.findingCount() > 0 || !audit.findingTypes().isEmpty();
        boolean typeMatched = typeMatches(sample.type(), audit.findingTypes());

        if (!expectedPositive && detectedPositive) {
            reasons.add("FALSE_POSITIVE");
        }
        if (expectedPositive && !detectedPositive) {
            reasons.add("FALSE_NEGATIVE");
        }
        if (expectedPositive && detectedPositive && !levelMatches(sample, audit)) {
            reasons.add("LEVEL_MISMATCH");
        }
        if (expectedPositive && detectedPositive && !typeMatched) {
            reasons.add("TYPE_MISMATCH");
        }
        if (!"S3".equalsIgnoreCase(sample.level()) && audit.blocked()) {
            reasons.add("UNEXPECTED_BLOCK");
        }
        if ("S3".equalsIgnoreCase(sample.level()) && audit.success()) {
            reasons.add("S3_UNEXPECTED_SUCCESS");
        }
        if (!audit.success() && !audit.blocked() && !("S3".equalsIgnoreCase(sample.level()) && levelMatches(sample, audit))) {
            reasons.add("REQUEST_FAILED");
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
                audit == null ? "" : nullToEmpty(audit.privacyRiskLevel()),
                audit == null ? "" : String.join("|", audit.findingTypes()),
                audit != null && audit.blocked(),
                audit != null && audit.success(),
                audit == null ? "" : String.valueOf(audit.statusCode()),
                audit == null ? "" : String.valueOf(audit.findingCount()),
                audit == null ? "" : nullToEmpty(audit.errorMessage())
        );
    }

    private void writeBugCsv(Path output, List<BugRow> rows) throws IOException {
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("id,等级,类型,语言,prompt,问题类型,审计隐私等级,审计命中类型,blocked,success,status_code,finding_count,error_message");
        for (BugRow row : rows) {
            lines.add(String.join(",",
                    csv(row.id()),
                    csv(row.level()),
                    csv(row.type()),
                    csv(row.language()),
                    csv(row.prompt()),
                    csv(row.reason()),
                    csv(row.auditPrivacyRiskLevel()),
                    csv(row.auditFindingTypes()),
                    csv(row.blocked()),
                    csv(row.success()),
                    csv(row.statusCode()),
                    csv(row.findingCount()),
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

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String csv(Object value) {
        String safe = value == null ? "" : String.valueOf(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record Config(Path inputDir, Path outputDir, String dbUrl, String dbUsername, String dbPassword) {
        static Config fromEnv(String[] args) {
            return new Config(
                    args.length > 0 ? Path.of(args[0]) : Path.of(env("EXP63_INPUT_DIR", DEFAULT_INPUT_DIR.toString())),
                    args.length > 1 ? Path.of(args[1]) : Path.of(env("EXP63_BUG_OUTPUT_DIR", DEFAULT_OUTPUT_DIR.toString())),
                    env("EXP63_DB_URL", "jdbc:postgresql://192.168.31.107:5432/postgres"),
                    env("EXP63_DB_USERNAME", "postgres"),
                    env("EXP63_DB_PASSWORD", "sqlsql111")
            );
        }

        private static String env(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }

    private record Sample(String id, String level, String type, String language, String prompt) {
    }

    private record AuditRecord(String requestId, String privacyRiskLevel, int findingCount, boolean blocked,
                               boolean success, int statusCode, String errorMessage, Set<String> findingTypes) {
    }

    private record BugRow(String id, String level, String type, String language, String prompt, String reason,
                          String auditPrivacyRiskLevel, String auditFindingTypes, boolean blocked, boolean success,
                          String statusCode, String findingCount, String errorMessage) {
    }
}
