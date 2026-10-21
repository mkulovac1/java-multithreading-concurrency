package ba.telco.ocs.cdr.entity;

import ba.telco.ocs.tariff.entity.ServiceType;

import java.time.Instant;

public record Cdr(String recordId, String msisdn, ServiceType serviceType, long units, Instant eventTime) {
}
