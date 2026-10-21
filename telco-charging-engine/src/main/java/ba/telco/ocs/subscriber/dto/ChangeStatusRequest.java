package ba.telco.ocs.subscriber.dto;

import ba.telco.ocs.subscriber.entity.SubscriberStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(@NotNull(message = "Status is required.") SubscriberStatus status) {
}
