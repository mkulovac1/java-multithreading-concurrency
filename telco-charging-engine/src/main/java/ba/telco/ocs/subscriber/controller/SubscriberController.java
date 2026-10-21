package ba.telco.ocs.subscriber.controller;

import ba.telco.ocs.subscriber.dto.AmountRequest;
import ba.telco.ocs.subscriber.dto.BalanceResponse;
import ba.telco.ocs.subscriber.dto.ChangeStatusRequest;
import ba.telco.ocs.subscriber.dto.ChangeTariffRequest;
import ba.telco.ocs.subscriber.dto.CreateSubscriberRequest;
import ba.telco.ocs.subscriber.dto.SubscriberResponse;
import ba.telco.ocs.subscriber.dto.TransferRequest;
import ba.telco.ocs.subscriber.dto.TransferResponse;
import ba.telco.ocs.subscriber.mapper.SubscriberMapper;
import ba.telco.ocs.subscriber.service.SubscriberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscribers")
@Tag(name = "Subscribers", description = "Prepaid accounts (per-account ReentrantLock, tryLock with timeout, ordered locking)")
@RequiredArgsConstructor
public class SubscriberController {

    private final SubscriberService subscriberService;
    private final SubscriberMapper subscriberMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Activate a new prepaid subscriber")
    public SubscriberResponse create(@Valid @RequestBody CreateSubscriberRequest request) {
        return subscriberMapper.toResponse(subscriberService.create(
                request.msisdn(), request.name(), request.tariffCode(), request.initialBalance()));
    }

    @GetMapping
    @Operation(summary = "List subscribers (sorted by MSISDN)")
    public List<SubscriberResponse> getPage(@RequestParam(defaultValue = "0") @Min(0) int offset,
                                            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return subscriberService.getPage(offset, limit).stream().map(subscriberMapper::toResponse).toList();
    }

    @GetMapping("/{msisdn}")
    @Operation(summary = "Subscriber details and account balance")
    public SubscriberResponse get(@PathVariable String msisdn) {
        return subscriberMapper.toResponse(subscriberService.getByMsisdn(msisdn));
    }

    @PostMapping("/{msisdn}/top-up")
    @Operation(summary = "Top up credit (voucher / online top-up)")
    public BalanceResponse topUp(@PathVariable String msisdn, @Valid @RequestBody AmountRequest request) {
        return subscriberMapper.toBalanceResponse(msisdn, subscriberService.topUp(msisdn, request.amount()));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Me2U credit transfer between subscribers (deadlock-free)")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return subscriberMapper.toTransferResponse(request.fromMsisdn(), request.toMsisdn(),
                subscriberService.transfer(request.fromMsisdn(), request.toMsisdn(), request.amount()));
    }

    @PatchMapping("/{msisdn}/status")
    @Operation(summary = "Change status (ACTIVE / SUSPENDED)")
    public SubscriberResponse changeStatus(@PathVariable String msisdn, @Valid @RequestBody ChangeStatusRequest request) {
        return subscriberMapper.toResponse(subscriberService.changeStatus(msisdn, request.status()));
    }

    @PatchMapping("/{msisdn}/tariff")
    @Operation(summary = "Change tariff")
    public SubscriberResponse changeTariff(@PathVariable String msisdn, @Valid @RequestBody ChangeTariffRequest request) {
        return subscriberMapper.toResponse(subscriberService.changeTariff(msisdn, request.tariffCode()));
    }
}
