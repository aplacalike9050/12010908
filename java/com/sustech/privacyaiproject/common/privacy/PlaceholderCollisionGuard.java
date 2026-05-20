package com.sustech.privacyaiproject.common.privacy;

import com.sustech.privacyaiproject.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 伪造隐私占位符检测器。
 * <p>
 * 用户输入中不允许出现 [PII_{TYPE}_{HASH}_{SEQ}] 格式文本，避免调用方伪造占位符干扰映射和响应还原。
 */
@Component
public class PlaceholderCollisionGuard {

    private static final Pattern RESERVED_PLACEHOLDER_PATTERN =
            Pattern.compile("\\[PII_[A-Z0-9_]+_[a-f0-9]{4,}_[0-9]+]");

    /**
     * 校验输入中是否包含网关保留占位符格式。
     *
     * @param text 用户可控输入
     */
    public void assertNoReservedPlaceholder(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (RESERVED_PLACEHOLDER_PATTERN.matcher(text).find()) {
            throw BizException.badRequest("输入包含网关保留隐私占位符格式，疑似伪造占位符，已拒绝处理");
        }
    }
}
