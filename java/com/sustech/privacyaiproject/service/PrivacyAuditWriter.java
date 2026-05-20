package com.sustech.privacyaiproject.service;

import com.sustech.privacyaiproject.domain.entity.PrivacyAuditEventEntity;
import com.sustech.privacyaiproject.repository.PrivacyAuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 隐私审计异步写入器。
 * <p>
 * 独立 Bean 承载 @Async，避免同类方法自调用导致异步注解不生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivacyAuditWriter {

    private final PrivacyAuditEventRepository privacyAuditEventRepository;

    /**
     * 异步保存审计事件。
     *
     * @param event 审计事件实体
     */
    @Async("auditTaskExecutor")
    public void saveAsync(PrivacyAuditEventEntity event) {
        try {
            privacyAuditEventRepository.save(event);
        } catch (Exception ex) {
            log.error("隐私审计日志异步写入失败 eventId={}", event == null ? null : event.getId(), ex);
        }
    }
}
