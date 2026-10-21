package ba.telco.ocs.tariff.mapper;

import ba.telco.ocs.tariff.dto.CreateTariffRequest;
import ba.telco.ocs.tariff.dto.TariffResponse;
import ba.telco.ocs.tariff.dto.UpdateTariffRequest;
import ba.telco.ocs.tariff.entity.TariffPlan;
import org.springframework.stereotype.Component;

@Component
public class TariffMapper {

    public TariffPlan toEntity(CreateTariffRequest request) {
        return new TariffPlan(request.code(), request.name(), request.voicePricePerMinute(),
                request.smsPrice(), request.dataPricePerMb(), request.voiceBillingIncrementSeconds());
    }

    public TariffPlan toEntity(String code, UpdateTariffRequest request) {
        return new TariffPlan(code, request.name(), request.voicePricePerMinute(),
                request.smsPrice(), request.dataPricePerMb(), request.voiceBillingIncrementSeconds());
    }

    public TariffResponse toResponse(TariffPlan tariff) {
        return new TariffResponse(tariff.code(), tariff.name(), tariff.voicePricePerMinute(),
                tariff.smsPrice(), tariff.dataPricePerMb(), tariff.voiceBillingIncrementSeconds());
    }
}
