package com.mcp.pikachu.donation.port;

import com.mcp.pikachu.donation.domain.Donation;

import java.util.List;
import java.util.Optional;

/** Porta de saida para onde as doacoes confirmadas ficam (Mongo: banco dev-wowlk, collection devwowlk). */
public interface DonationStore {

    /** Grava se ainda nao existe. Retorna false se o pagamento ja estava registrado (idempotente). */
    boolean saveIfAbsent(Donation donation);

    Optional<Donation> findByPaymentId(String paymentId);

    /** Doacoes da conta, da mais antiga para a mais nova. */
    List<Donation> findByAccount(long accountId);
}
