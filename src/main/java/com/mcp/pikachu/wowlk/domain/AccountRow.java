package com.mcp.pikachu.wowlk.domain;

/** Linha de {@code acore_auth.account} com o que o modulo precisa. */
public record AccountRow(long id, String username, String email, byte[] salt, byte[] verifier, boolean locked) {
}
