package ba.telco.ocs.tariff.service;

import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.repository.TariffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TariffService {

    private final TariffRepository tariffRepository;

    public TariffPlan create(TariffPlan tariff) {
        if (!tariffRepository.insert(tariff)) {
            throw new InvalidStateException("Tariff with code " + tariff.code() + " already exists.");
        }
        return tariff;
    }

    public TariffPlan update(TariffPlan tariff) {
        if (!tariffRepository.replace(tariff)) {
            throw notFound(tariff.code());
        }
        return tariff;
    }

    public TariffPlan getByCode(String code) {
        return tariffRepository.findByCode(code).orElseThrow(() -> notFound(code));
    }

    public boolean exists(String code) {
        return tariffRepository.findByCode(code).isPresent();
    }

    public List<TariffPlan> getAll() {
        return tariffRepository.findAll();
    }

    private static NotFoundException notFound(String code) {
        return new NotFoundException("Tariff with code " + code + " does not exist.");
    }
}
