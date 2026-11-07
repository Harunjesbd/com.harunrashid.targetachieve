package com.harunrashid.targetachieve;

public class UserModel {
    private String userId;
    private long trialExpiryMillis;
    private long subscriptionExpiryMillis;

    public UserModel() {}

    public UserModel(String userId, long trialExpiryMillis, long subscriptionExpiryMillis) {
        this.userId = userId;
        this.trialExpiryMillis = trialExpiryMillis;
        this.subscriptionExpiryMillis = subscriptionExpiryMillis;
    }

    public String getUserId() { return userId; }
    public long getTrialExpiryMillis() { return trialExpiryMillis; }
    public long getSubscriptionExpiryMillis() { return subscriptionExpiryMillis; }
}