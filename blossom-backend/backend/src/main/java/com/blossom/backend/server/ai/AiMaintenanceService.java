package com.blossom.backend.server.ai;

import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 接入短期状态维护。审计记录不在此删除。 */
@Service
@AllArgsConstructor
public class AiMaintenanceService {

    private final AiMapper mapper;

    @Scheduled(cron = "0 17 4 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void deleteExpiredIdempotency() {
        mapper.deleteAllExpiredIdempotency();
    }
}
