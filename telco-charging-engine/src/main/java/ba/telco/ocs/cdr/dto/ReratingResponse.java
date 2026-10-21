package ba.telco.ocs.cdr.dto;

import java.math.BigDecimal;

public record ReratingResponse(
        String tariffCode,
        int cdrCount,
        int threads,
        int availableProcessors,
        BigDecimal totalRevenue,
        double sequentialMillis,
        double parallelMillis,
        double speedup,
        boolean resultsMatch) {
}
