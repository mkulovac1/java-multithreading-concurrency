package ba.telco.ocs.charging.repository;

import ba.telco.ocs.charging.entity.ChargingSession;
import ba.telco.ocs.charging.entity.SessionStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class ChargingSessionRepository {

    private final ConcurrentMap<String, ChargingSession> sessions = new ConcurrentHashMap<>();

    public void save(ChargingSession session) {
        sessions.put(session.getId(), session);
    }

    public Optional<ChargingSession> findById(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<ChargingSession> findExpired(Instant now) {
        return sessions.values().stream().filter(session -> session.isExpired(now)).toList();
    }

    public int removeClosedBefore(Instant threshold) {
        int before = sessions.size();
        sessions.values().removeIf(session -> {
            ChargingSession.State state = session.getState();
            return state.status() != SessionStatus.ACTIVE && state.closedAt().isBefore(threshold);
        });
        return Math.max(0, before - sessions.size());
    }

    public long countActive() {
        return sessions.values().stream().filter(session -> session.getStatus() == SessionStatus.ACTIVE).count();
    }
}
