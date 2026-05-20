package com.sustech.privacyaiproject.dataservice.test5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 统计 6.5 实验目录下 PDF 文件路径，并输出为 CSV。
 *
 * <p>脚本只扫描 PDF 文件路径，不读取 PDF 文件内容。默认扫描
 * {@code file/paper/data/6.5/pdf} 下的 {@code 3000}、{@code 6000}、{@code 30000}
 * 三个子目录，输出到 {@code file/paper/data/6.5/csv/pdf-paths.csv}。</p>
 */
public final class Experiment65PdfPathToCsv {

    private static final Path DEFAULT_INPUT_ROOT = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.5/pdf");
    private static final Path DEFAULT_OUTPUT_CSV = Path.of("D:/Project/MYAI/MyAiProject/file/paper/data/6.5/csv/pdf-paths.csv");
    private static final List<String> TARGET_DIRS = List.of("3000", "6000", "30000");
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Experiment65PdfPathToCsv() {
    }

    public static void main(String[] args) throws IOException {
        Path inputRoot = args.length > 0 ? Path.of(args[0]) : DEFAULT_INPUT_ROOT;
        Path outputCsv = args.length > 1 ? Path.of(args[1]) : DEFAULT_OUTPUT_CSV;

        List<Row> rows = collectRows(inputRoot);
        writeCsv(outputCsv, rows);
        System.out.println("已生成 CSV: " + outputCsv.toAbsolutePath());
    }

    private static List<Row> collectRows(Path inputRoot) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String dirName : TARGET_DIRS) {
            Path dir = inputRoot.resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }

            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(Files::isRegularFile)
                        .filter(Experiment65PdfPathToCsv::isPdf)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), Experiment65PdfPathToCsv::compareFileNames))
                        .map(Experiment65PdfPathToCsv::toRow)
                        .forEach(rows::add);
            }
        }
        return rows;
    }

    private static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".pdf");
    }

    private static Row toRow(Path path) {
        String fileName = path.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".pdf".length());
        return new Row(id, "", "", path.toAbsolutePath().toString());
    }

    private static int compareFileNames(String left, String right) {
        return extractNumber(left) == extractNumber(right)
                ? left.compareToIgnoreCase(right)
                : Integer.compare(extractNumber(left), extractNumber(right));
    }

    private static int extractNumber(String fileName) {
        int underscoreIndex = fileName.indexOf('_');
        int dotIndex = fileName.lastIndexOf('.');
        if (underscoreIndex < 0 || dotIndex <= underscoreIndex + 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(fileName.substring(underscoreIndex + 1, dotIndex));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static void writeCsv(Path outputCsv, List<Row> rows) throws IOException {
        if (outputCsv.getParent() != null) {
            Files.createDirectories(outputCsv.getParent());
        }

        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add("id,隐私类型,注入类型,路径");
        for (Row row : rows) {
            lines.add(String.join(",",
                    csv(row.id),
                    csv(row.privacyType),
                    csv(row.injectionType),
                    csv(row.path)
            ));
        }

        String csvContent = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        byte[] contentBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] outputBytes = new byte[UTF_8_BOM.length + contentBytes.length];
        System.arraycopy(UTF_8_BOM, 0, outputBytes, 0, UTF_8_BOM.length);
        System.arraycopy(contentBytes, 0, outputBytes, UTF_8_BOM.length, contentBytes.length);
        Files.write(outputCsv, outputBytes);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record Row(String id, String privacyType, String injectionType, String path) {
    }
}
