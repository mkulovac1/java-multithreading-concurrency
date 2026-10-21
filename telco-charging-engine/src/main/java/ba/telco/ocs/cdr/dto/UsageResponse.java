package ba.telco.ocs.cdr.dto;

import java.math.BigDecimal;

public record UsageResponse(String msisdn, long cdrCount, long voiceSeconds, long smsCount, long dataKb,
                            BigDecimal revenue) {
}
