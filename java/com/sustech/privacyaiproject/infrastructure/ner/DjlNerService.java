package com.sustech.privacyaiproject.infrastructure.ner;

import com.sustech.privacyaiproject.domain.entity.Entity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DjlNerService implements NerService {

    /**
     * 兼容旧类名，内部委托给新的 BertNerServiceImpl。
     */
    private final BertNerServiceImpl bertNerService;

    @Override
    public List<Entity> extractEntities(String text) {
        return bertNerService.extractEntities(text);
    }
}