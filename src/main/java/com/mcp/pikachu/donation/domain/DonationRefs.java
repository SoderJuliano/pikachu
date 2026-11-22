package com.mcp.pikachu.donation.domain;

/** Formato do external_reference: {@code wowlk:<id da conta no AzerothCore>}. */
public final class DonationRefs {

    private static final String PREFIX = "wowlk:";

    private DonationRefs() {
    }

    public static String forAccount(long accountId) {
        return PREFIX + accountId;
    }

    public static Long accountIdOf(String externalReference) {
        if (externalReference == null || !externalReference.startsWith(PREFIX)) return null;
        try {
            long id = Long.parseLong(externalReference.substring(PREFIX.length()));
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
