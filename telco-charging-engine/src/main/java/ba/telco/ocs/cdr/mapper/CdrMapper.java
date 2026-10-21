package ba.telco.ocs.cdr.mapper;

import ba.telco.ocs.cdr.dto.CdrBatchResponse;
import ba.telco.ocs.cdr.dto.CdrRequest;
import ba.telco.ocs.cdr.dto.ReratingResponse;
import ba.telco.ocs.cdr.dto.UsageResponse;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.entity.SubscriberUsage;
import ba.telco.ocs.cdr.rating.ReratingSimulationService.ReratingResult;
import org.springframework.stereotype.Component;

@Component
public class CdrMapper {

    public Cdr toEntity(CdrRequest request) {
        return new Cdr(request.recordId(), request.msisdn(), request.serviceType(), request.units(), request.eventTime());
    }

    public CdrBatchResponse toResponse(CdrBatch batch) {
        return new CdrBatchResponse(batch.getId(), batch.getStatus(), batch.getTotalRecords(), batch.getAccepted(),
                batch.getRejected(), batch.getRated(), batch.getFailed(), batch.getRevenue(),
                batch.getFailureSamples(), batch.getCreatedAt(), batch.getCompletedAt());
    }

    public UsageResponse toResponse(SubscriberUsage usage) {
        return new UsageResponse(usage.getMsisdn(), usage.getCdrCount(), usage.getVoiceSeconds(),
                usage.getSmsCount(), usage.getDataKb(), usage.getRevenue());
    }

    public ReratingResponse toResponse(ReratingResult result) {
        return new ReratingResponse(result.tariffCode(), result.cdrCount(), result.threads(),
                result.availableProcessors(), result.totalRevenue(), result.sequentialMillis(),
                result.parallelMillis(), result.speedup(), result.resultsMatch());
    }
}
