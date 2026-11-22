package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Donation;
import com.mcp.pikachu.donation.port.DonationStore;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store em memoria para testes locais sem banco ({@code donation.store: memory}). Some ao reiniciar. */
public class InMemoryDonationStore implements DonationStore {

    private final ConcurrentHashMap<String, Donation> byPayment = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(Donation donation) {
        return byPayment.putIfAbsent(donation.paymentId(), donation) == null;
    }

    @Override
    public Optional<Donation> findByPaymentId(String paymentId) {
        return Optional.ofNullable(paymentId == null ? null : byPayment.get(paymentId));
    }

    @Override
    public List<Donation> findByAccount(long accountId) {
        return byPayment.values().stream()
                .filter(d -> d.accountId() == accountId)
                .sorted(Comparator.comparing(Donation::paidAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
