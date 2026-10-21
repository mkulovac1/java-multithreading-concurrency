package ba.telco.ocs.cdr.controller;

import ba.telco.ocs.cdr.dto.CdrBatchResponse;
import ba.telco.ocs.cdr.dto.ReratingRequest;
import ba.telco.ocs.cdr.dto.ReratingResponse;
import ba.telco.ocs.cdr.dto.SubmitCdrBatchRequest;
import ba.telco.ocs.cdr.dto.UsageResponse;
import ba.telco.ocs.cdr.mapper.CdrMapper;
import ba.telco.ocs.cdr.rating.ReratingSimulationService;
import ba.telco.ocs.cdr.service.CdrBatchService;
import ba.telco.ocs.cdr.service.UsageAggregator;
import ba.telco.ocs.common.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cdrs")
@Tag(name = "CDR Mediation", description = "CDR intake and rating (producer-consumer, back-pressure) and parallel re-rating")
@RequiredArgsConstructor
public class CdrController {

    private final CdrBatchService batchService;
    private final UsageAggregator usageAggregator;
    private final ReratingSimulationService reratingService;
    private final CdrMapper cdrMapper;

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit a CDR batch for asynchronous rating")
    public CdrBatchResponse submit(@Valid @RequestBody SubmitCdrBatchRequest request,
                                   @Parameter(description = "How many ms to wait for completion (0 = do not wait)")
                                   @RequestParam(defaultValue = "0") @Min(0) @Max(30_000) long waitMillis)
            throws InterruptedException {
        return cdrMapper.toResponse(batchService.submit(
                request.cdrs().stream().map(cdrMapper::toEntity).toList(), waitMillis));
    }

    @GetMapping("/batches/{batchId}")
    @Operation(summary = "Batch status (optional long polling up to waitMillis)")
    public CdrBatchResponse getBatch(@PathVariable String batchId,
                                     @RequestParam(defaultValue = "0") @Min(0) @Max(30_000) long waitMillis)
            throws InterruptedException {
        return cdrMapper.toResponse(batchService.get(batchId, waitMillis));
    }

    @GetMapping("/usage/{msisdn}")
    @Operation(summary = "Aggregated subscriber usage from CDRs")
    public UsageResponse getUsage(@PathVariable String msisdn) {
        return usageAggregator.find(msisdn).map(cdrMapper::toResponse)
                .orElseThrow(() -> new NotFoundException("No recorded usage for number " + msisdn + "."));
    }

    @GetMapping("/usage/top")
    @Operation(summary = "Subscribers with the highest revenue")
    public List<UsageResponse> getTopUsage(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return usageAggregator.topByRevenue(limit).stream().map(cdrMapper::toResponse).toList();
    }

    @PostMapping("/rerating-simulations")
    @Operation(summary = "What-if re-rating: sequential vs. parallel (latency optimization)")
    public ReratingResponse simulate(@Valid @RequestBody ReratingRequest request) throws InterruptedException {
        return cdrMapper.toResponse(reratingService.simulate(
                request.tariffCode(), request.cdrCount(), request.threads(), request.seed()));
    }
}
