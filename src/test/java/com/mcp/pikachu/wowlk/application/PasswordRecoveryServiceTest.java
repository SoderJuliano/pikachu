package com.mcp.pikachu.wowlk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.AbraClient.QueueMessage;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordRecoveryServiceTest {

    static final String KEY = "events";
    static final AccountRow ARTHAS = new AccountRow(7, "ARTHAS", "arthas@test.com", new byte[32], new byte[32], false);

    AbraClient abra;
    WowlkRepository repo;
    AccountMailer mailer;
    PasswordRecoveryService service;

    @BeforeEach
    void setUp() {
        abra = mock(AbraClient.class);
        repo = mock(WowlkRepository.class);
        mailer = mock(AccountMailer.class);
        WowlkProperties props = new WowlkProperties(true, "https://x", 120000, 0, 3, 100, 900, 2592000, "0.1.0", false, "");
        service = new PasswordRecoveryService(abra, repo, mailer, props);
        when(repo.findByUsername("ARTHAS")).thenReturn(Optional.of(ARTHAS));
    }

    static JsonNode payload(String username, String email, String salt) throws Exception {
        return new ObjectMapper().readTree("""
                {"type":"password.recover","account":{"username":"%s","email":"%s","salt":"%s","verifier":"%s","locale":"pt-BR"}}"""
                .formatted(username, email, salt, "cd".repeat(32)));
    }

    static QueueMessage msg(String id) {
        return new QueueMessage(id, "password.recover", KEY, "{}", null);
    }

    @Test
    void matchingAccountChangesPasswordRevokesSessionsDeletesAndNotifies() throws Exception {
        service.process(msg("n1"), payload("arthas", "Arthas@Test.com", "ab".repeat(32)), KEY);
        verify(repo).updatePassword(eq(7L), any(), any());
        verify(repo).revokeAllSessions(7L);
        verify(abra).delete("n1", KEY);
        verify(mailer).sendPasswordChanged("arthas@test.com", "ARTHAS", true);
    }

    @Test
    void wrongEmailIsDiscardedWithoutChange() throws Exception {
        service.process(msg("n1"), payload("ARTHAS", "other@test.com", "ab".repeat(32)), KEY);
        verify(repo, never()).updatePassword(anyLong(), any(), any());
        verify(abra).delete("n1", KEY);
        verifyNoInteractions(mailer);
    }

    @Test
    void privilegedAccountIsNeverChanged() throws Exception {
        when(repo.isPrivileged(7L)).thenReturn(true);
        service.process(msg("n1"), payload("ARTHAS", "arthas@test.com", "ab".repeat(32)), KEY);
        verify(repo, never()).updatePassword(anyLong(), any(), any());
        verify(abra).delete("n1", KEY);
    }

    @Test
    void invalidPayloadAndBotAccountsAreDiscarded() throws Exception {
        service.process(msg("n1"), payload("ARTHAS", "arthas@test.com", "zz"), KEY);
        service.process(msg("n2"), payload("RNDBOT1", "a@test.com", "ab".repeat(32)), KEY);
        verify(repo, never()).updatePassword(anyLong(), any(), any());
        verify(abra).delete("n1", KEY);
        verify(abra).delete("n2", KEY);
    }

    @Test
    void attemptsPerUsernameAreLimited() throws Exception {
        for (int i = 0; i < 6; i++) service.process(msg("w" + i), payload("ARTHAS", "guess" + i + "@test.com", "ab".repeat(32)), KEY);
        verify(repo, times(5)).findByUsername("ARTHAS");
        service.process(msg("ok"), payload("ARTHAS", "arthas@test.com", "ab".repeat(32)), KEY);
        verify(repo, never()).updatePassword(anyLong(), any(), any());
        verify(abra).delete("ok", KEY);
    }

    @Test
    void temporaryErrorRetriesThenApologisesOnThirdAttempt() throws Exception {
        doThrow(new RuntimeException("db down")).when(repo).updatePassword(anyLong(), any(), any());
        JsonNode p = payload("ARTHAS", "arthas@test.com", "ab".repeat(32));
        service.process(msg("n1"), p, KEY);
        service.process(msg("n1"), p, KEY);
        verify(abra, never()).delete(anyString(), anyString());
        verifyNoInteractions(mailer);
        service.process(msg("n1"), p, KEY);
        verify(abra).delete("n1", KEY);
        verify(mailer).sendPasswordFailed("arthas@test.com", "ARTHAS", true);
    }
}
