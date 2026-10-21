package ba.telco.ocs.cdr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitCdrBatchRequest(
        @NotEmpty(message = "A batch must contain at least one CDR.")
        @Size(max = 10_000, message = "A batch can contain at most 10,000 CDRs.")
        List<@Valid CdrRequest> cdrs) {
}
