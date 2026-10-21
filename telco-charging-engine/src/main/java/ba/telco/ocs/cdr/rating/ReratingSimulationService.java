package ba.telco.ocs.cdr.rating;

import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.ResourceBusyException;
import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.service.RatingEngine;
import ba.telco.ocs.tariff.service.TariffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.Semaphore;

import static ba.telco.ocs.tariff.service.RatingEngine.MONEY_SCALE;

@Service
@RequiredArgsConstructor
public class ReratingSimulationService {

    public static final int MAX_CDR_COUNT = 10_000_000;
    private static final int WARMUP_CDRS = 200_000;

    private final TariffService tariffService;
    private final RatingEngine ratingEngine;
    private final Semaphore simulationPermit = new Semaphore(1);

    public ReratingResult simulate(String tariffCode, int cdrCount, int threads, long seed) throws InterruptedException {
        int maxThreads = Runtime.getRuntime().availableProcessors() * 2;
        if (threads < 1 || threads > maxThreads) {
            throw new BadRequestException("Thread count must be between 1 and " + maxThreads + ".");
        }
        if (cdrCount < threads || cdrCount > MAX_CDR_COUNT) {
            throw new BadRequestException("CDR count must be between the thread count and " + MAX_CDR_COUNT + ".");
        }
        TariffPlan tariff = tariffService.getByCode(tariffCode);
        if (!simulationPermit.tryAcquire()) {
            throw new ResourceBusyException("A simulation is already running. Please try again later.");
        }
        try {
            rateRange(tariff, seed, 0, Math.min(cdrCount, WARMUP_CDRS));

            long sequentialStart = System.nanoTime();
            long sequentialTotal = rateRange(tariff, seed, 0, cdrCount);
            long sequentialNanos = System.nanoTime() - sequentialStart;

            long parallelStart = System.nanoTime();
            long parallelTotal = rateInParallel(tariff, seed, cdrCount, threads);
            long parallelNanos = System.nanoTime() - parallelStart;

            return new ReratingResult(tariffCode, cdrCount, threads, Runtime.getRuntime().availableProcessors(),
                    BigDecimal.valueOf(parallelTotal, MONEY_SCALE), sequentialNanos / 1_000_000.0,
                    parallelNanos / 1_000_000.0, sequentialNanos / (double) parallelNanos,
                    sequentialTotal == parallelTotal);
        } finally {
            simulationPermit.release();
        }
    }

    private long rateInParallel(TariffPlan tariff, long seed, int cdrCount, int threads) throws InterruptedException {
        long[] partialTotals = new long[threads];
        Thread[] workers = new Thread[threads];
        int chunk = cdrCount / threads;

        for (int i = 0; i < threads; i++) {
            int partition = i;
            int from = i * chunk;
            int to = (i == threads - 1) ? cdrCount : from + chunk;
            workers[i] = Thread.ofPlatform()
                    .name("rerating-" + i)
                    .unstarted(() -> partialTotals[partition] = rateRange(tariff, seed, from, to));
        }
        for (Thread worker : workers) {
            worker.start();
        }
        try {
            for (Thread worker : workers) {
                worker.join();
            }
        } catch (InterruptedException e) {
            for (Thread worker : workers) {
                worker.interrupt();
            }
            throw e;
        }

        long total = 0;
        for (long partial : partialTotals) {
            total += partial;
        }
        return total;
    }

    private long rateRange(TariffPlan tariff, long seed, int from, int to) {
        long total = 0;
        for (int index = from; index < to; index++) {
            if ((index & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) {
                return total;
            }
            ServiceType type = SyntheticCdrGenerator.serviceType(seed, index);
            long units = SyntheticCdrGenerator.units(seed, index, type);
            total += ratingEngine.price(tariff, type, units).unscaledValue().longValueExact();
        }
        return total;
    }

    public record ReratingResult(String tariffCode, int cdrCount, int threads, int availableProcessors,
                                 BigDecimal totalRevenue, double sequentialMillis, double parallelMillis,
                                 double speedup, boolean resultsMatch) {
    }
}
