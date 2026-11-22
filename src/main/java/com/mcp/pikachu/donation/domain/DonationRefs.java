package com.mcp.pikachu.donation.domain;

/** Formato do external_reference: {@code wowlk-<id da conta no AzerothCore>} (sem dois-pontos para compatibilidade com Mercado Pago Orders API). */
public final class DonationRefs {

    private static final String PREFIX = "wowlk-";
    private static final String LEGACY_PREFIX = "wowlk:";

    private DonationRefs() {
    }

    public static String forAccount(long accountId) {
        return PREFIX + accountId;
    }

    public static Long accountIdOf(String externalReference) {
        if (externalReference == null) return null;
        if (externalReference.startsWith(PREFIX)) {
            try {
                long id = Long.parseLong(externalReference.substring(PREFIX.length()));
                return id > 0 ? id : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (externalReference.startsWith(LEGACY_PREFIX)) {
            try {
                long id = Long.parseLong(externalReference.substring(LEGACY_PREFIX.length()));
                return id > 0 ? id : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
