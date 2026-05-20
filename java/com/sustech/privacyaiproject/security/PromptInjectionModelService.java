package com.sustech.privacyaiproject.security;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextClassificationTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import com.sustech.privacyaiproject.domain.dto.security.PromptInjectionDetectionResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Prompt 注入 ONNX 模型检测服务。
 * <p>
 * 服务启动时加载 HuggingFace 文本分类 ONNX 模型，并初始化 Predictor 对象池，请求期间只复用 Predictor。
 */
@Slf4j
@Service
public class PromptInjectionModelService {

    private static final int MAX_MODEL_TOKENS = 512;
    private static final int LONG_TEXT_CHAR_THRESHOLD = 4000;
    private static final int WINDOW_TOKENS = 512;

    @Value("${privacy.prompt-injection.enabled:true}")
    private boolean enabled;

    @Value("${privacy.prompt-injection.model-dir:src/main/resources/models/prompt-injection}")
    private String modelDir;

    @Value("${privacy.prompt-injection.threshold:0.85}")
    private double threshold;

    @Value("${privacy.prompt-injection.predictor-pool-size:2}")
    private int predictorPoolSize;

    private ZooModel<String, Classifications> model;
    private BlockingQueue<Predictor<String, Classifications>> predictorPool;
    private HuggingFaceTokenizer tokenizer;

    /**
     * 应用启动时加载模型并初始化 Predictor 池。
     */
    @PostConstruct
    public void initModel() {
        if (!enabled) {
            log.info("Prompt注入模型检测已关闭");
            return;
        }
        Path modelPath = resolveModelPath();
        Criteria<String, Classifications> criteria = buildCriteria(modelPath);
        try {
            this.model = criteria.loadModel();
            this.tokenizer = HuggingFaceTokenizer.newInstance(modelPath.resolve("tokenizer.json"));
            int realPoolSize = Math.max(1, predictorPoolSize);
            this.predictorPool = new ArrayBlockingQueue<>(realPoolSize);
            for (int i = 0; i < realPoolSize; i++) {
                predictorPool.offer(model.newPredictor());
            }
            log.info("Prompt注入检测模型加载成功, path={}, predictorPoolSize={}",
                    modelPath.toAbsolutePath(), realPoolSize);
        } catch (IOException | ModelNotFoundException | MalformedModelException ex) {
            log.error("Prompt注入检测模型加载失败, path={}", modelPath.toAbsolutePath(), ex);
            this.model = null;
            this.predictorPool = null;
            this.tokenizer = null;
        }
    }

    /**
     * 使用本地 ONNX 模型检测 Prompt 注入风险。
     *
     * @param text 用户输入文本
     * @return 模型检测结果
     */
    public PromptInjectionDetectionResult detect(String text) {
        if (!enabled || text == null || text.isBlank() || model == null || predictorPool == null) {
            return PromptInjectionDetectionResult.safe();
        }
        Predictor<String, Classifications> predictor = null;
        try {
            predictor = predictorPool.poll(5, TimeUnit.SECONDS);
            if (predictor == null) {
                log.warn("Prompt注入检测Predictor池暂时耗尽，跳过模型检测");
                return PromptInjectionDetectionResult.safe();
            }
            List<String> modelInputs = toModelInputs(text);
            Classifications.Classification bestOverall = null;
            for (int i = 0; i < modelInputs.size(); i++) {
                Classifications classifications = predictor.predict(modelInputs.get(i));
                Classifications.Classification best = classifications.best();
                if (bestOverall == null || best.getProbability() > bestOverall.getProbability()) {
                    bestOverall = best;
                }
                if (isMaliciousLabel(best.getClassName()) && best.getProbability() >= threshold) {
                    return PromptInjectionDetectionResult.builder()
                            .malicious(true)
                            .score(best.getProbability())
                            .source("MODEL")
                            .label(best.getClassName())
                            .reason("模型判定为Prompt注入攻击，命中长文本首尾窗口 " + (i + 1) + "/" + modelInputs.size())
                            .build();
                }
            }
            String label = bestOverall == null ? "UNKNOWN" : bestOverall.getClassName();
            double probability = bestOverall == null ? 0D : bestOverall.getProbability();
            return PromptInjectionDetectionResult.builder()
                    .malicious(false)
                    .score(probability)
                    .source("MODEL")
                    .label(label)
                    .reason("模型判定为安全或低风险")
                    .build();
        } catch (Exception ex) {
            log.warn("Prompt注入模型推理失败，跳过模型检测", ex);
            return PromptInjectionDetectionResult.safe();
        } finally {
            if (predictor != null) {
                predictorPool.offer(predictor);
            }
        }
    }

    /**
     * 应用关闭时释放 Predictor 池和模型资源。
     */
    @PreDestroy
    public void destroy() {
        if (tokenizer != null) {
            tokenizer.close();
        }
        if (predictorPool != null) {
            Predictor<String, Classifications> predictor;
            while ((predictor = predictorPool.poll()) != null) {
                predictor.close();
            }
        }
        if (model != null) {
            model.close();
        }
    }

    /**
     * 构建 DJL 文本分类模型加载配置。
     */
    private Criteria<String, Classifications> buildCriteria(Path modelPath) {
        return Criteria.builder()
                .setTypes(String.class, Classifications.class)
                .optModelPath(modelPath)
                .optEngine("OnnxRuntime")
                .optArgument("maxLength", 512)
                .optArgument("includeTokenTypes", false)
                .optTranslatorFactory(new SafeCastTextClassificationTranslatorFactory())
                .optProgress(new ProgressBar())
                .build();
    }

    private List<String> toModelInputs(String text) {
        if (tokenizer == null) {
            return List.of(text);
        }
        try {
            Encoding encoding = tokenizer.encode(text, false, false);
            long[] ids = encoding.getIds();
            if (ids.length <= MAX_MODEL_TOKENS && text.length() <= LONG_TEXT_CHAR_THRESHOLD) {
                return List.of(text);
            }
            return headTailWindowInputs(ids);
        } catch (Exception ex) {
            log.warn("Prompt注入模型首尾窗口构造失败，改用首尾字符窗口: {}", ex.getMessage());
            return List.of(firstAndLastChars(text));
        }
    }

    private List<String> headTailWindowInputs(long[] ids) {
        if (ids.length <= WINDOW_TOKENS) {
            return List.of(tokenizer.decode(ids, true));
        }
        return List.of(
                tokenizer.decode(slice(ids, 0, WINDOW_TOKENS), true),
                tokenizer.decode(slice(ids, Math.max(0, ids.length - WINDOW_TOKENS), ids.length), true)
        );
    }

    private long[] slice(long[] ids, int startInclusive, int endExclusive) {
        int size = Math.max(0, endExclusive - startInclusive);
        long[] window = new long[size];
        System.arraycopy(ids, startInclusive, window, 0, size);
        return window;
    }

    private String firstAndLastChars(String text) {
        if (text == null || text.length() <= 1024) {
            return text;
        }
        return text.substring(0, 512) + "\n" + text.substring(text.length() - 512);
    }

    /**
     * 解析 Prompt 注入检测模型目录。
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
                userDir.resolve("target").resolve("classes").resolve("models").resolve("prompt-injection"),
                userDir.resolve("privacy-ai-project").resolve("target").resolve("classes").resolve("models").resolve("prompt-injection"),
                userDir.resolve("MyAiProject").resolve("privacy-ai-project").resolve("target").resolve("classes").resolve("models").resolve("prompt-injection")
        };
        for (Path candidate : candidates) {
            if (isUsableModelDir(candidate)) {
                return candidate.normalize();
            }
        }
        return configured;
    }

    /**
     * 判断候选目录是否包含可加载的 Prompt 注入检测模型文件。
     */
    private boolean isUsableModelDir(Path candidate) {
        return candidate != null
                && Files.exists(candidate)
                && (Files.exists(candidate.resolve("model.onnx")) || Files.exists(candidate.resolve("tokenizer.json")));
    }

    /**
     * 判断分类标签是否代表恶意输入。
     */
    private boolean isMaliciousLabel(String label) {
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase(Locale.ROOT);
        return normalized.contains("injection")
                || normalized.contains("malicious")
                || normalized.contains("unsafe")
                || normalized.contains("jailbreak")
                || normalized.equals("label_1")
                || normalized.equals("1");
    }

    /**
     * 装饰 DJL 默认文本分类 Translator，将输入张量统一转换为 INT64。
     */
    private static class SafeCastTextClassificationTranslatorFactory extends TextClassificationTranslatorFactory {

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
                        casted.add(toInt64(ctx, array));
                    }
                    return casted;
                }

                @Override
                public O processOutput(ai.djl.translate.TranslatorContext ctx, ai.djl.ndarray.NDList list) throws Exception {
                    return toOutput(list, 0, 1);
                }

                @Override
                public ai.djl.ndarray.NDList batchProcessInput(ai.djl.translate.TranslatorContext ctx, List<I> inputs)
                        throws Exception {
                    return castNdListToInt64(ctx, baseTranslator.batchProcessInput(ctx, inputs));
                }

                @Override
                public List<O> batchProcessOutput(ai.djl.translate.TranslatorContext ctx, ai.djl.ndarray.NDList list)
                        throws Exception {
                    return toBatchOutput(list);
                }

                @Override
                public void prepare(ai.djl.translate.TranslatorContext ctx) throws Exception {
                    baseTranslator.prepare(ctx);
                }

                /**
                 * 将 tokenizer 输出转换为 ONNX 常用的 INT64 输入。
                 * <p>
                 * 部分 DJL/OnnxRuntime NDArray 不支持直接 toType，这里通过重新创建数组兜底。
                 */
                private ai.djl.ndarray.NDArray toInt64(ai.djl.translate.TranslatorContext ctx,
                                                       ai.djl.ndarray.NDArray array) {
                    ai.djl.ndarray.NDArray next;
                    if (array.getDataType() == ai.djl.ndarray.types.DataType.INT64) {
                        next = array;
                    } else {
                        try {
                            next = array.toType(ai.djl.ndarray.types.DataType.INT64, false);
                        } catch (UnsupportedOperationException ex) {
                            next = ctx.getNDManager().create(array.toLongArray(), array.getShape());
                        }
                    }
                    next.setName(array.getName());
                    return next;
                }

                private ai.djl.ndarray.NDList castNdListToInt64(ai.djl.translate.TranslatorContext ctx,
                                                                 ai.djl.ndarray.NDList original) {
                    ai.djl.ndarray.NDList casted = new ai.djl.ndarray.NDList();
                    for (ai.djl.ndarray.NDArray array : original) {
                        casted.add(toInt64(ctx, array));
                    }
                    return casted;
                }

                @SuppressWarnings("unchecked")
                private O toOutput(ai.djl.ndarray.NDList list, int batchIndex, int batchSize) {
                    return (O) toClassifications(list, batchIndex, batchSize);
                }

                private List<O> toBatchOutput(ai.djl.ndarray.NDList list) {
                    int batchSize = resolveBatchSize(list);
                    List<O> output = new ArrayList<>(batchSize);
                    for (int i = 0; i < batchSize; i++) {
                        output.add(toOutput(list, i, batchSize));
                    }
                    return output;
                }

                /**
                 * 使用纯 Java 解析 ONNX logits，避免 DJL Rust NDArray argSort 未实现的问题。
                 */
                private Classifications toClassifications(ai.djl.ndarray.NDList list, int batchIndex, int batchSize) {
                    ai.djl.ndarray.NDArray logits = list.get(0);
                    float[] values = logits.toFloatArray();
                    int labelCount = Math.max(1, values.length / Math.max(1, batchSize));
                    int offset = batchIndex * labelCount;
                    double[] probabilities = softmax(values, offset, labelCount);
                    List<String> labels = labels(labelCount);
                    List<Double> scores = new ArrayList<>(labelCount);
                    for (double probability : probabilities) {
                        scores.add(probability);
                    }
                    return new Classifications(labels, scores);
                }

                private int resolveBatchSize(ai.djl.ndarray.NDList list) {
                    ai.djl.ndarray.NDArray logits = list.get(0);
                    ai.djl.ndarray.types.Shape shape = logits.getShape();
                    if (shape.dimension() >= 2) {
                        return Math.max(1, (int) shape.get(0));
                    }
                    return 1;
                }

                private double[] softmax(float[] values, int offset, int labelCount) {
                    double max = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < labelCount; i++) {
                        max = Math.max(max, values[offset + i]);
                    }
                    double sum = 0D;
                    double[] output = new double[labelCount];
                    for (int i = 0; i < labelCount; i++) {
                        output[i] = Math.exp(values[offset + i] - max);
                        sum += output[i];
                    }
                    if (sum <= 0D) {
                        return output;
                    }
                    for (int i = 0; i < labelCount; i++) {
                        output[i] = output[i] / sum;
                    }
                    return output;
                }

                private List<String> labels(int labelCount) {
                    if (labelCount == 2) {
                        return List.of("SAFE", "INJECTION");
                    }
                    List<String> output = new ArrayList<>(labelCount);
                    for (int i = 0; i < labelCount; i++) {
                        output.add("LABEL_" + i);
                    }
                    return output;
                }
            };
        }
    }
}
