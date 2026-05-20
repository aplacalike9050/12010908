package com.sustech.privacyaiproject.infrastructure.ner;

import com.sustech.privacyaiproject.domain.entity.Entity;
import java.util.List;

/**
 * 命名实体识别 (NER) 服务接口
 * 定义了所有 NER 引擎（无论是本地 BERT 还是云端 API）的标准行为
 */
public interface NerService {

    /**
     * 从文本中提取实体
     * @param text 原始文本 (例如 "我叫张三，住在深圳")
     * @return 实体列表 ([{text="张三", type="PER"}, {text="深圳", type="LOC"}])
     */
    List<Entity> extractEntities(String text);
}