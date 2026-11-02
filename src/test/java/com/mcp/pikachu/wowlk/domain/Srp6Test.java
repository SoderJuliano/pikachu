package com.mcp.pikachu.wowlk.domain;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class Srp6Test {

    /** Mesmo vetor do site (tests/core.test.js), conferido contra uma implementacao independente do SRP6.cpp. */
    @Test
    void verifierMatchesSiteVector() {
        byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) salt[i] = (byte) ((i * 7 + 3) & 255);
        assertEquals("36c9bb05645eec334887a844d6a00ffc9f161d5311fe1c5cf19ccc1ad7cbc90e",
                HexFormat.of().formatHex(Srp6.verifier("Arthas", "frostMourne1", salt)));
    }

    @Test
    void caseInsensitiveLikeTheClient() {
        byte[] salt = Srp6.randomSalt();
        byte[] v = Srp6.verifier("ARTHAS", "FROSTMOURNE1", salt);
        assertTrue(Srp6.matches("arthas", "frostmourne1", salt, v));
        assertFalse(Srp6.matches("arthas", "frostmourne2", salt, v));
    }

    @Test
    void validation() {
        assertThrows(ApiException.class, () -> NewAccount.fromSrp6("ab", "a@b.com", "00".repeat(32), "00".repeat(32), 2, null));
        assertThrows(ApiException.class, () -> NewAccount.fromSrp6("RNDBOT99", "a@b.com", "00".repeat(32), "00".repeat(32), 2, null));
        assertThrows(ApiException.class, () -> NewAccount.fromSrp6("ARTHAS", "nope", "00".repeat(32), "00".repeat(32), 2, null));
        assertThrows(ApiException.class, () -> NewAccount.fromSrp6("ARTHAS", "a@b.com", "zz", "00".repeat(32), 2, null));
        assertThrows(ApiException.class, () -> NewAccount.fromSrp6("ARTHAS", "a@b.com", "00".repeat(32), "00".repeat(32), 1, null));
        assertThrows(ApiException.class, () -> NewAccount.fromPassword("ARTHAS", "a@b.com", "short1", null));
        assertThrows(ApiException.class, () -> NewAccount.fromPassword("ARTHAS", "a@b.com", "arthas", null));
        NewAccount ok = NewAccount.fromSrp6(" arthas ", " Arthas@Lordaeron.COM ", "ab".repeat(32), "cd".repeat(32), 2, "pt-BR");
        assertEquals("ARTHAS", ok.username());
        assertEquals("arthas@lordaeron.com", ok.email());
        assertTrue(ok.prefersPortuguese());
        NewAccount pw = NewAccount.fromPassword("arthas", "a@b.com", "Frostmourne1", "en");
        assertTrue(Srp6.matches("ARTHAS", "Frostmourne1", pw.salt(), pw.verifier()));
    }
}
