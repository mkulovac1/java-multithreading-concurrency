package ba.telco.ocs.campaign.service;

public final class CampaignContext {

    static final ScopedValue<String> CAMPAIGN_ID = ScopedValue.newInstance();

    private CampaignContext() {
    }

    public static String currentCampaignId() {
        return CAMPAIGN_ID.isBound() ? CAMPAIGN_ID.get() : "-";
    }
}
