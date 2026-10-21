package ba.telco.ocs.subscriber.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeTariffRequest(@NotBlank(message = "Tariff code is required.") String tariffCode) {
}
