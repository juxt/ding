package crux.api.tx;

import crux.api.CruxDocument;

import java.util.Date;
import java.util.Objects;

public final class MatchOperation extends TransactionOperation {
    public static MatchOperation create(Object id) {
        return new MatchOperation(id, null, null);
    }

    public static MatchOperation create(Object id, Date validTime) {
        return new MatchOperation(id, null, validTime);
    }

    public static MatchOperation create(CruxDocument document) {
        return new MatchOperation(document.getId(), document, null);
    }

    public static MatchOperation create(CruxDocument document, Date atValidTime) {
        return new MatchOperation(document.getId(), document, atValidTime);
    }

    public Object getId() {
        return id;
    }

    public CruxDocument getDocument() {
        return document;
    }

    public Date getAtValidTime() {
        return atValidTime;
    }

    private final Object id;
    private final CruxDocument document;
    private final Date atValidTime;

    private MatchOperation(Object id, CruxDocument document, Date atValidTime) {
        this.id = id;
        this.document = document;
        this.atValidTime = atValidTime;
    }

    @Override
    public final Type getType() {
        return Type.MATCH;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchOperation that = (MatchOperation) o;
        return id.equals(that.id)
                && Objects.equals(document, that.document)
                && Objects.equals(atValidTime, that.atValidTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Type.MATCH, id, document, atValidTime);
    }
}
