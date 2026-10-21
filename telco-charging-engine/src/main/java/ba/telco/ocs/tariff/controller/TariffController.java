package ba.telco.ocs.tariff.controller;

import ba.telco.ocs.tariff.dto.CreateTariffRequest;
import ba.telco.ocs.tariff.dto.TariffResponse;
import ba.telco.ocs.tariff.dto.UpdateTariffRequest;
import ba.telco.ocs.tariff.mapper.TariffMapper;
import ba.telco.ocs.tariff.service.TariffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tariffs")
@Tag(name = "Tariffs", description = "Tariff catalog (ReentrantReadWriteLock - many reads, rare writes)")
@RequiredArgsConstructor
public class TariffController {

    private final TariffService tariffService;
    private final TariffMapper tariffMapper;

    @GetMapping
    @Operation(summary = "List all tariffs")
    public List<TariffResponse> getAll() {
        return tariffService.getAll().stream().map(tariffMapper::toResponse).toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Tariff details")
    public TariffResponse getByCode(@PathVariable String code) {
        return tariffMapper.toResponse(tariffService.getByCode(code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new tariff")
    public TariffResponse create(@Valid @RequestBody CreateTariffRequest request) {
        return tariffMapper.toResponse(tariffService.create(tariffMapper.toEntity(request)));
    }

    @PutMapping("/{code}")
    @Operation(summary = "Update tariff prices (atomic replacement of the whole price list)")
    public TariffResponse update(@PathVariable String code, @Valid @RequestBody UpdateTariffRequest request) {
        return tariffMapper.toResponse(tariffService.update(tariffMapper.toEntity(code, request)));
    }
}
