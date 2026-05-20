package com.sustech.privacyaiproject.common.exception;

import java.util.List;
import java.util.Map;

/**
 * 极高风险内容异常。
 * 当文本中命中 API Key/Secret 等高危信息时抛出。
 */
public class HighRiskContentException extends BizException {

    private final List<Map<String, Object>> findingDetails;

    public HighRiskContentException(String message) {
        super(422, message);
        this.findingDetails = List.of();
    }

    public HighRiskContentException(String message, List<Map<String, Object>> findingDetails) {
        super(422, message);
        this.findingDetails = findingDetails == null ? List.of() : findingDetails;
    }

    public List<Map<String, Object>> getFindingDetails() {
        return findingDetails;
    }
}
