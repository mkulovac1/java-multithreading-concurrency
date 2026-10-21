package ba.telco.ocs.campaign.repository;

import ba.telco.ocs.campaign.entity.Campaign;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class CampaignRepository {

    private final ConcurrentMap<String, Campaign> campaigns = new ConcurrentHashMap<>();

    public void save(Campaign campaign) {
        campaigns.put(campaign.getId(), campaign);
    }

    public Optional<Campaign> findById(String id) {
        return Optional.ofNullable(campaigns.get(id));
    }

    public List<Campaign> findAll() {
        return campaigns.values().stream().sorted(Comparator.comparing(Campaign::getCreatedAt).reversed()).toList();
    }
}
