package ba.telco.ocs.charging.service;

import ba.telco.ocs.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChargingSessionReaperTest {

    private final ChargingService chargingService = mock(ChargingService.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ChargingSessionReaper reaper = new ChargingSessionReaper(chargingService, scheduler,
            TestFixtures.properties(java.time.Duration.ofSeconds(1)));

    @Test
    void schedulesItselfWithAFixedDelay() {
        reaper.start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(5_000L), eq(5_000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void runExpiresAndPurges() {
        when(chargingService.expireSessions()).thenReturn(2);
        when(chargingService.purgeClosedSessions(any())).thenReturn(1);

        reaper.runOnce();

        verify(chargingService).expireSessions();
        verify(chargingService).purgeClosedSessions(java.time.Duration.ofHours(1));
    }

    @Test
    void quietRunDoesNothingSpecial() {
        reaper.runOnce();

        verify(chargingService).purgeClosedSessions(any());
    }

    @Test
    void purgeOnlyRunIsStillReported() {
        when(chargingService.purgeClosedSessions(any())).thenReturn(3);

        reaper.runOnce();

        verify(chargingService).purgeClosedSessions(any());
    }

    @Test
    void failingRunIsSwallowedSoTheScheduleSurvives() {
        when(chargingService.expireSessions()).thenThrow(new IllegalStateException("boom"));

        reaper.runOnce();

        verify(chargingService).expireSessions();
    }
}
