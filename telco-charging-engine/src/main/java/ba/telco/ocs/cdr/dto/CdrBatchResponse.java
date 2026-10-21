package ba.telco.ocs.cdr.dto;

import ba.telco.ocs.cdr.entity.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CdrBatchResponse(
        String batchId,
        BatchStatus status,
        int totalRecords,
        int accepted,
        int rejected,
        int rated,
        int failed,
        BigDecimal revenue,
        List<String> failureSamples,
        Instant createdAt,
        Instant completedAt) {
}
