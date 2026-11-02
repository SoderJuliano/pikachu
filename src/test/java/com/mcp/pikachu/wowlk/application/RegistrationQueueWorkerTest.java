package com.mcp.pikachu.wowlk.application;

import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import com.mcp.pikachu.wowlk.infra.AbraClient.QueueMessage;
import com.mcp.pikachu.wowlk.infra.QueuePayloadDecryptor;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationQueueWorkerTest {

    static final String KEY = "reg-key";

    AbraClient abra;
    WowlkRepository repo;
    AccountRegistrationService registration;
    AccountMailer mailer;
    RegistrationQueueWorker worker;

    @BeforeEach
    void setUp() {
        abra = mock(AbraClient.class);
        repo = mock(WowlkRepository.class);
        registration = mock(AccountRegistrationService.class);
        mailer = mock(AccountMailer.class);
        WowlkProperties props = new WowlkProperties(true, "https://x", 120000, 0, 3, 100, 900, 2592000, "0.1.0", false, "", "/tmp", 20, 8085);
        worker = new RegistrationQueueWorker(abra, repo, registration, mailer, new QueuePayloadDecryptor(), props);
        when(repo.registrationStatus(any())).thenReturn(Optional.empty());
    }

    static QueueMessage msg(String id, String username) {
        String content = """
                {"schema":1,"type":"account.register","id":"p-%s","account":{"username":"%s","email":"%s@test.com",
                 "salt":"%s","verifier":"%s","expansion":2,"locale":"pt-BR"}}""".formatted(id, username, username.toLowerCase(), "ab".repeat(32), "cd".repeat(32));
        return new QueueMessage(id, "account.register", KEY, content, null);
    }

    @Test
    void emptyQueueDoesNothing() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of());
        worker.runOnce(KEY);
        verifyNoInteractions(repo, registration, mailer);
    }

    @Test
    void successCreatesDeletesOnlyThatMessageAndEmails() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of(msg("n1", "ARTHAS")));
        when(registration.register(any())).thenReturn(new AccountRegistrationService.Result(AccountRegistrationService.Outcome.CREATED, 77L));
        worker.runOnce(KEY);
        verify(abra).delete("n1", KEY);
        verify(abra, times(1)).delete(anyString(), anyString());
        verify(repo).saveRegistrationEvent(eq("p-n1"), eq("n1"), eq("ARTHAS"), eq("arthas@test.com"), eq("CREATED"), anyInt(), isNull(), eq(77L));
        verify(mailer).sendActivated("arthas@test.com", "ARTHAS", true);
        verify(mailer, never()).sendFailed(any(), any(), anyBoolean());
    }

    @Test
    void transientErrorRetriesAndAppologisesOnThirdAttempt() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of(msg("n1", "ARTHAS")));
        when(registration.register(any())).thenThrow(new RuntimeException("boom"));
        worker.runOnce(KEY);
        worker.runOnce(KEY);
        verify(abra, never()).delete(anyString(), anyString());
        verify(mailer, never()).sendFailed(any(), any(), anyBoolean());
        worker.runOnce(KEY);
        verify(mailer).sendFailed("arthas@test.com", "ARTHAS", true);
        verify(abra).delete("n1", KEY);
    }

    @Test
    void databaseDownFailsFastWithoutTryingEachMessage() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of(msg("n1", "ARTHAS"), msg("n2", "THRALL")));
        doThrow(new RuntimeException("db down")).when(repo).ping();
        for (int i = 0; i < 3; i++) worker.runOnce(KEY);
        verify(registration, never()).register(any());
        verify(mailer).sendFailed("arthas@test.com", "ARTHAS", true);
        verify(mailer).sendFailed("thrall@test.com", "THRALL", true);
    }

    @Test
    void alreadyProcessedMessageIsOnlyDeleted() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of(msg("n1", "ARTHAS")));
        when(repo.registrationStatus("p-n1")).thenReturn(Optional.of("CREATED"));
        worker.runOnce(KEY);
        verify(abra).delete("n1", KEY);
        verifyNoInteractions(registration, mailer);
    }

    @Test
    void conflictIsNotRetried() throws Exception {
        when(abra.retrieve(KEY)).thenReturn(List.of(msg("n1", "ARTHAS")));
        when(registration.register(any())).thenReturn(new AccountRegistrationService.Result(AccountRegistrationService.Outcome.CONFLICT, null));
        worker.runOnce(KEY);
        verify(abra).delete("n1", KEY);
        verify(mailer).sendConflict("arthas@test.com", "ARTHAS", true);
    }

    @Test
    void invalidPayloadIsDiscardedWithApology() throws Exception {
        QueueMessage bad = new QueueMessage("n9", "account.register", KEY,
                "{\"type\":\"account.register\",\"id\":\"p9\",\"account\":{\"username\":\"x\",\"email\":\"x@test.com\"}}", null);
        when(abra.retrieve(KEY)).thenReturn(List.of(bad));
        worker.runOnce(KEY);
        verify(registration, never()).register(any());
        verify(abra).delete("n9", KEY);
        verify(mailer).sendFailed(eq("x@test.com"), any(), anyBoolean());
    }
}
