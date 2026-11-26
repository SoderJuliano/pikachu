package com.mcp.pikachu.donation.domain;

/**
 * Formato do external_reference:
 * <ul>
 *   <li>{@code wowlk-<id>} (ou legado {@code wowlk:<id>}): doacao tradicional ({@code wowlk_site.donation})</li>
 *   <li>{@code wowsixtyfor-<id>}: doacao do cliente 64-bit ({@code wowlk_site.wowsixtyfordev})</li>
 * </ul>
 * Sem dois-pontos nos formatos novos para compatibilidade com a Mercado Pago Orders API.
 */
public final class DonationRefs {

    private static final String PREFIX = "wowlk-";
    private static final String LEGACY_PREFIX = "wowlk:";
    private static final String CLIENT64_PREFIX = "wowsixtyfor-";

    private DonationRefs() {
    }

    public static String forAccount(long accountId) {
        return PREFIX + accountId;
    }

    public static String forClient64(long accountId) {
        return CLIENT64_PREFIX + accountId;
    }

    public static boolean isClient64(String externalReference) {
        return externalReference != null && externalReference.startsWith(CLIENT64_PREFIX);
    }

    public static Long accountIdOf(String externalReference) {
        if (externalReference == null) return null;
        if (externalReference.startsWith(PREFIX)) return parsePositive(externalReference.substring(PREFIX.length()));
        if (externalReference.startsWith(LEGACY_PREFIX)) return parsePositive(externalReference.substring(LEGACY_PREFIX.length()));
        if (externalReference.startsWith(CLIENT64_PREFIX)) return parsePositive(externalReference.substring(CLIENT64_PREFIX.length()));
        return null;
    }

    private static Long parsePositive(String raw) {
        try {
            long id = Long.parseLong(raw);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
