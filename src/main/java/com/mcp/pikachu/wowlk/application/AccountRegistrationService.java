package com.mcp.pikachu.wowlk.application;

import com.mcp.pikachu.wowlk.domain.AccountRow;
import com.mcp.pikachu.wowlk.domain.NewAccount;
import com.mcp.pikachu.wowlk.infra.WowlkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cria a conta no AzerothCore. Usado pelo worker da fila e pela rota POST /auth/register.
 *
 * Concorrencia: o AzerothCore so tem indice unico em username; o e-mail nao. Por isso
 * checagem + insert rodam sob um lock (uma instancia do Pikachu), e o indice unico
 * cobre o resto (DuplicateKeyException → conflito).
 */
@Slf4j
@Service
public class AccountRegistrationService {

    public enum Outcome { CREATED, ALREADY_CREATED, CONFLICT }

    public record Result(Outcome outcome, Long accountId) {
    }

    private final WowlkRepository repo;
    private final ReentrantLock lock = new ReentrantLock();

    public AccountRegistrationService(WowlkRepository repo) {
        this.repo = repo;
    }

    public Result register(NewAccount a) {
        lock.lock();
        try {
            List<AccountRow> conflicts = repo.findConflicts(a.username(), a.email());
            if (!conflicts.isEmpty()) return classify(a, conflicts);
            try {
                long id = repo.insertAccount(a);
                return new Result(Outcome.CREATED, id);
            } catch (DuplicateKeyException e) {
                return classify(a, repo.findConflicts(a.username(), a.email()));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mesma mensagem processada duas vezes (ou duplo clique no site): mesma conta, mesmo
     * e-mail e mesmo verifier → nao e conflito, e a conta que ja criamos.
     */
    private Result classify(NewAccount a, List<AccountRow> rows) {
        if (rows.size() == 1) {
            AccountRow r = rows.getFirst();
            if (r.username().equals(a.username()) && a.email().equalsIgnoreCase(r.email())
                    && Arrays.equals(r.salt(), a.salt()) && Arrays.equals(r.verifier(), a.verifier())) {
                return new Result(Outcome.ALREADY_CREATED, r.id());
            }
        }
        return new Result(Outcome.CONFLICT, null);
    }
}
