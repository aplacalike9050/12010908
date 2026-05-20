package com.sustech.privacyaiproject.infrastructure.ner;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.djl.huggingface.translator.TokenClassificationTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.nlp.translator.NamedEntity;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import com.sustech.privacyaiproject.domain.entity.Entity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 BERT ONNX 的 NER 实现。
 * <p>
 * 设计说明：
 * 1) 本类实现 NerService 接口，后续可并存 DistilBertNerServiceImpl 做对比实验；
 * 2) 模型目录默认指向 resources/models/bert-ner，可通过配置覆盖；
 * 3) 若模型加载失败，返回空实体列表并打日志，避免阻断主流程。
 */
@Slf4j
@Service
@Primary
public class BertNerServiceImpl implements NerService {

    private static final int MAX_MODEL_TOKENS = 512;
    private static final int SLIDING_WINDOW_TOKENS = 480;
    private static final int SLIDING_WINDOW_OVERLAP = 80;

    @Value("${privacy.ner.model-dir:src/main/resources/models/bert-ner}")
    private String modelDir;

    private ZooModel<String, NamedEntity[]> model;
    private HuggingFaceTokenizer tokenizer;

    /**
     * 初始化并预加载 ONNX 模型。
     */
    @PostConstruct
    public void initModel() {
        Path modelPath = resolveModelPath();
        Criteria<String, NamedEntity[]> criteria = buildCriteria(modelPath);
        try {
            this.model = criteria.loadModel();
            this.tokenizer = HuggingFaceTokenizer.newInstance(modelPath.resolve("tokenizer.json"));
            log.info("BERT NER 模型加载成功, path={}", modelPath.toAbsolutePath());
        } catch (IOException | ModelNotFoundException | MalformedModelException ex) {
            log.error("BERT NER 模型加载失败, path={}", modelPath.toAbsolutePath(), ex);
            this.model = null;
            this.tokenizer = null;
        }
    }

    /**
     * 从文本中提取命名实体（PER/LOC/ORG等）。
     */
    @Override
    public List<Entity> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        if (model == null) {
            return Collections.emptyList();
        }
        if (shouldUseSlidingWindow(text)) {
            return extractEntitiesBySlidingWindow(text);
        }
        return predictEntities(text, 0);
    }

    private boolean shouldUseSlidingWindow(String text) {
        if (tokenizer == null) {
            return false;
        }
        try {
            return tokenizer.encode(text, true, false).getIds().length > MAX_MODEL_TOKENS;
        } catch (Exception ex) {
            log.warn("NER token长度计算失败，改用单窗口推理: {}", ex.getMessage());
            return false;
        }
    }

    private List<Entity> extractEntitiesBySlidingWindow(String text) {
        try {
            Encoding encoding = tokenizer.encode(text, false, false);
            CharSpan[] spans = encoding.getCharTokenSpans();
            if (spans == null || spans.length == 0) {
                return Collections.emptyList();
            }
            List<Entity> output = new ArrayList<>();
            java.util.Set<String> deduplicate = new java.util.LinkedHashSet<>();
            int step = Math.max(1, SLIDING_WINDOW_TOKENS - SLIDING_WINDOW_OVERLAP);
            for (int tokenStart = 0; tokenStart < spans.length; tokenStart += step) {
                int tokenEnd = Math.min(spans.length, tokenStart + SLIDING_WINDOW_TOKENS);
                int charStart = firstValidStart(spans, tokenStart, tokenEnd);
                int charEnd = lastValidEnd(spans, tokenStart, tokenEnd);
                if (charStart < 0 || charEnd <= charStart || charEnd > text.length()) {
                    continue;
                }
                String chunk = text.substring(charStart, charEnd);
                for (Entity entity : predictEntities(chunk, charStart)) {
                    String key = entity.getType() + "|" + entity.getStart() + "|" + entity.getEnd() + "|" + entity.getText();
                    if (deduplicate.add(key)) {
                        output.add(entity);
                    }
                }
                if (tokenEnd >= spans.length) {
                    break;
                }
            }
            return output;
        } catch (Exception ex) {
            log.warn("NER滑动窗口推理失败，返回空结果: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Entity> predictEntities(String text, int offset) {
        try (Predictor<String, NamedEntity[]> predictor = model.newPredictor()) {
            NamedEntity[] prediction = predictor.predict(text);
            List<Entity> entities = new ArrayList<>();
            for (NamedEntity namedEntity : prediction) {
                entities.add(new Entity(
                        namedEntity.getWord(),
                        normalizeType(namedEntity.getEntity()),
                        namedEntity.getStart() + offset,
                        namedEntity.getEnd() + offset
                ));
            }
            return entities;
        } catch (Exception ex) {
            log.warn("NER 推理失败，返回空结果: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private int firstValidStart(CharSpan[] spans, int tokenStart, int tokenEnd) {
        for (int i = tokenStart; i < tokenEnd; i++) {
            if (spans[i] != null && spans[i].getEnd() > spans[i].getStart()) {
                return spans[i].getStart();
            }
        }
        return -1;
    }

    private int lastValidEnd(CharSpan[] spans, int tokenStart, int tokenEnd) {
        for (int i = tokenEnd - 1; i >= tokenStart; i--) {
            if (spans[i] != null && spans[i].getEnd() > spans[i].getStart()) {
                return spans[i].getEnd();
            }
        }
        return -1;
    }

    /**
     * 将模型标签标准化为策略引擎可识别类型。
     */
    private String normalizeType(String entity) {
        if (entity == null) {
            return "UNKNOWN";
        }
        String normalized = entity.toUpperCase();
        if (normalized.startsWith("B-") || normalized.startsWith("I-")) {
            normalized = normalized.substring(2);
        }
        return switch (normalized) {
            case "PER", "PERSON" -> "PER";
            case "LOC", "LOCATION" -> "LOC";
            case "ORG", "ORGANIZATION" -> "ORG";
            default -> normalized;
        };
    }

    /**
     * 应用关闭时释放模型资源。
     */
    @PreDestroy
    public void destroy() {
        if (tokenizer != null) {
            tokenizer.close();
        }
        if (model != null) {
            model.close();
        }
    }

    /**
     * 构建 DJL NER 加载配置。
     */
    private Criteria<String, NamedEntity[]> buildCriteria(Path modelPath) {
        return Criteria.builder()
                .setTypes(String.class, NamedEntity[].class)
                .optModelPath(modelPath)
                .optEngine("OnnxRuntime")
                .optArgument("includeTokenTypes", true)
                .optArgument("maxLength", 512)
                .optTranslatorFactory(new SafeCastTranslatorFactory())
                .optProgress(new ProgressBar())
                .build();
    }

    /**
     * 解析模型目录。
     * <p>
     * 兼容从模块目录、仓库根目录、上级工程目录以及 target/classes 启动的不同场景。
     */
    private Path resolveModelPath() {
        Path configured = Paths.get(modelDir);
        Path userDir = Paths.get(System.getProperty("user.dir", "."));
        Path[] candidates = new Path[]{
                configured,
                userDir.resolve(modelDir),
                userDir.resolve("privacy-ai-project").resolve(modelDir),
                userDir.resolve("MyAiProject").resolve("privacy-ai-project").resolve(modelDir),
                userDir.resolve("target").resolve("classes").resolve("models").resolve("bert-ner"),
                userDir.resolve("privacy-ai-project").resolve("target").resolve("classes").resolve("models").resolve("bert-ner"),
                userDir.resolve("MyAiProject").resolve("privacy-ai-project").resolve("target").resolve("classes").resolve("models").resolve("bert-ner")
        };
        for (Path candidate : candidates) {
            if (isUsableModelDir(candidate)) {
                return candidate.normalize();
            }
        }
        return configured;
    }

    /**
     * 判断候选目录是否包含可加载的模型文件。
     */
    private boolean isUsableModelDir(Path candidate) {
        return candidate != null
                && Files.exists(candidate)
                && (Files.exists(candidate.resolve("model.onnx")) || Files.exists(candidate.resolve("tokenizer.json")));
    }

    /**
     * 装饰 DJL 默认 TokenClassificationTranslator：
     * 保持原始 shape 与命名不变，仅将输入张量类型强转为 INT64。
     */
    private static class SafeCastTranslatorFactory extends TokenClassificationTranslatorFactory {

        @Override
        public <I, O> ai.djl.translate.Translator<I, O> newInstance(
                Class<I> input, Class<O> output, ai.djl.Model model, Map<String, ?> arguments)
                throws ai.djl.translate.TranslateException {

            final ai.djl.translate.Translator<I, O> baseTranslator =
                    super.newInstance(input, output, model, arguments);

            return new ai.djl.translate.Translator<I, O>() {
                @Override
                public ai.djl.translate.Batchifier getBatchifier() {
                    return baseTranslator.getBatchifier();
                }

                @Override
                public ai.djl.ndarray.NDList processInput(ai.djl.translate.TranslatorContext ctx, I text) throws Exception {
                    ai.djl.ndarray.NDList original = baseTranslator.processInput(ctx, text);
                    ai.djl.ndarray.NDList casted = new ai.djl.ndarray.NDList();
                    for (ai.djl.ndarray.NDArray array : original) {
                        ai.djl.ndarray.NDArray next = array.toType(ai.djl.ndarray.types.DataType.INT64, false);
                        next.setName(array.getName());
                        casted.add(next);
                    }
                    return casted;
                }

                @Override
                public O processOutput(ai.djl.translate.TranslatorContext ctx, ai.djl.ndarray.NDList list) throws Exception {
                    return baseTranslator.processOutput(ctx, list);
                }

                @Override
                public void prepare(ai.djl.translate.TranslatorContext ctx) throws Exception {
                    baseTranslator.prepare(ctx);
                }

            };
        }
    }
}
