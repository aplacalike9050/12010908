package com.sustech.privacyaiproject.domain.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略工厂
 * 负责根据用户请求的类型（MASK/SYNTHESIS）分发对应的策略实现类
 */
@Service
public class PrivacyStrategyFactory {

    // Spring 会自动把所有实现了 PrivacyStrategy 接口的 Bean 注入到这个 Map 中
    // Key 是 @Component 指定的名字 ("MASK"), Value 是对象
    @Autowired
    private Map<String, PrivacyStrategy> strategyMap = new ConcurrentHashMap<>();

    /**
     * 获取策略
     */
    public PrivacyStrategy getStrategy(String type) {
        PrivacyStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            // 默认兜底策略：使用掩码策略
            return strategyMap.get("MASK");
        }
        return strategy;
    }
}