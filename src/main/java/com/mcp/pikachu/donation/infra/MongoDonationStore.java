package com.mcp.pikachu.donation.infra;

import com.mcp.pikachu.donation.domain.Donation;
import com.mcp.pikachu.donation.port.DonationStore;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * Doacoes confirmadas no MongoDB — banco {@code dev-wowlk}, collection {@code devwowlk}.
 * Um documento por pagamento aprovado; {@code _id} = id do pagamento no Mercado Pago, entao uma
 * reentrega do webhook bate na chave duplicada e nao cria outro registro.
 *
 * <pre>
 * { _id: "1234567890", paymentId: "1234567890", accountId: 42, username: "ARTHAS",
 *   email: "arthas@x.com", amount: 25.00, currency: "BRL", method: "pix",
 *   paidAt: ISODate(...), recordedAt: ISODate(...), liveMode: false }
 * </pre>
 */
@Slf4j
public class MongoDonationStore implements DonationStore {

    private final MongoCollection<Document> col;
    private volatile boolean indexed;

    public MongoDonationStore(MongoCollection<Document> col) {
        this.col = col;
    }

    @Override
    public boolean saveIfAbsent(Donation d) {
        ensureIndexes();
        try {
            col.insertOne(toDoc(d));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) return false;
            throw e;
        }
    }

    @Override
    public Optional<Donation> findByPaymentId(String paymentId) {
        return Optional.ofNullable(col.find(eq("_id", paymentId)).first()).map(MongoDonationStore::fromDoc);
    }

    @Override
    public List<Donation> findByAccount(long accountId) {
        List<Donation> out = new ArrayList<>();
        col.find(eq("accountId", accountId)).sort(Sorts.ascending("paidAt")).forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    /** Indice por conta (consulta do selo de apoiador). Criado na primeira escrita: Mongo fora nao derruba a subida. */
    private void ensureIndexes() {
        if (indexed) return;
        col.createIndex(Indexes.ascending("accountId"), new IndexOptions().name("idx_accountId"));
        indexed = true;
    }

    static Document toDoc(Donation d) {
        return new Document("_id", d.paymentId())
                .append("paymentId", d.paymentId())
                .append("accountId", d.accountId())
                .append("username", d.username())
                .append("email", d.email())
                .append("amount", d.amount() == null ? null : new Decimal128(d.amount()))
                .append("currency", d.currency())
                .append("method", d.method())
                .append("paidAt", d.paidAt() == null ? null : Date.from(d.paidAt()))
                .append("recordedAt", d.recordedAt() == null ? null : Date.from(d.recordedAt()))
                .append("liveMode", d.liveMode());
    }

    static Donation fromDoc(Document d) {
        Object amount = d.get("amount");
        BigDecimal value = amount instanceof Decimal128 dec ? dec.bigDecimalValue()
                : amount instanceof Number n ? new BigDecimal(n.toString()) : null;
        Date paid = d.getDate("paidAt"), recorded = d.getDate("recordedAt");
        Number accountId = d.get("accountId", Number.class);
        return new Donation(d.getString("paymentId"), accountId == null ? 0 : accountId.longValue(), d.getString("username"),
                d.getString("email"), value, d.getString("currency"), d.getString("method"),
                paid == null ? null : paid.toInstant(), recorded == null ? null : recorded.toInstant(),
                Boolean.TRUE.equals(d.getBoolean("liveMode")));
    }
}
