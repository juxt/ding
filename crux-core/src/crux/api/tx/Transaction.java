package crux.api.tx;

import clojure.lang.IPersistentVector;
import clojure.lang.PersistentVector;
import crux.api.CruxDocument;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class Transaction {
    public static Transaction buildTx(Consumer<Builder> f) {
        Builder builder = new Builder();
        f.accept(builder);
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private final List<TransactionOperation> operations;

    private Transaction(List<TransactionOperation> operations) {
        this.operations = operations;
    }

    public static final class Builder {
        private final ArrayList<TransactionOperation> operations = new ArrayList<>();

        private Builder() {}

        public final Builder add(TransactionOperation operation) {
            operations.add(operation);
            return this;
        }

        public final Builder put(CruxDocument document) {
            return add(PutOperation.create(document));
        }

        public final Builder put(CruxDocument document, Date startValidTime) {
            return add(PutOperation.create(document, startValidTime));
        }

        public final Builder put(CruxDocument document, Date startValidTime, Date endValidTime) {
            return add(PutOperation.create(document, startValidTime, endValidTime));
        }

        public final Builder delete(Object id) {
            return add(DeleteOperation.create(id));
        }

        public final Builder delete(Object id, Date startValidTime) {
            return add(DeleteOperation.create(id, startValidTime));
        }

        public final Builder delete(Object id, Date startValidTime, Date endValidTime) {
            return add(DeleteOperation.create(id, startValidTime, endValidTime));
        }

        public final Builder evict(Object id) {
            return add(EvictOperation.create(id));
        }

        public final Builder matchNotExists(Object id) {
            return add(MatchOperation.create(id));
        }

        public final Builder match(CruxDocument document) {
            return add(MatchOperation.create(document));
        }

        public final Builder matchNotExists(Object id, Date atValidTime) {
            return add(MatchOperation.create(id, atValidTime));
        }

        public final Builder match(CruxDocument document, Date atValidTime) {
            return add(MatchOperation.create(document, atValidTime));
        }

        public final Builder invokeFunction(Object id, Object... arguments) {
            return add(InvokeFunctionOperation.create(id, arguments));
        }

        public Transaction build() {
            return new Transaction(operations);
        }
    }

    private static class EdnVisitor implements TransactionOperation.Visitor {
        private IPersistentVector vector = PersistentVector.EMPTY;

        private void add(IPersistentVector toAdd) {
            vector = vector.cons(toAdd);
        }

        @Override
        public void visit(PutOperation operation) {
            IPersistentVector toAdd = PersistentVector.EMPTY
                    .cons(TransactionOperation.Type.PUT.getKeyword())
                    .cons(operation.getDocument().toMap());

            Date startValidTime = operation.getStartValidTime();
            if (startValidTime == null) {
                add(toAdd);
                return;
            }

            toAdd = toAdd.cons(startValidTime);

            Date endValidTime = operation.getEndValidTime();
            if (endValidTime == null) {
                add(toAdd);
                return;
            }
            add(toAdd.cons(endValidTime));
        }

        @Override
        public void visit(DeleteOperation operation) {
            IPersistentVector toAdd = PersistentVector.EMPTY
                    .cons(TransactionOperation.Type.DELETE.getKeyword())
                    .cons(operation.getId());

            Date startValidTime = operation.getStartValidTime();
            if (startValidTime == null) {
                add(toAdd);
                return;
            }

            toAdd = toAdd.cons(startValidTime);

            Date endValidTime = operation.getEndValidTime();
            if (endValidTime == null) {
                add(toAdd);
                return;
            }
            add(toAdd.cons(endValidTime));
        }

        @Override
        public void visit(EvictOperation operation) {
            IPersistentVector toAdd = PersistentVector.EMPTY
                    .cons(TransactionOperation.Type.EVICT.getKeyword())
                    .cons(operation.getId());
            add(toAdd);
        }

        @Override
        public void visit(MatchOperation operation) {
            IPersistentVector toAdd = PersistentVector.EMPTY
                    .cons(TransactionOperation.Type.MATCH.getKeyword())
                    .cons(operation.getId());

            CruxDocument document = operation.getDocument();
            if (document == null) {
                toAdd = toAdd.cons(null);
            }
            else {
                toAdd = toAdd.cons(document.toMap());
            }

            Date atValidTime = operation.getAtValidTime();
            if (atValidTime == null) {
                add(toAdd);
                return;
            }

            add(toAdd.cons(atValidTime));
        }

        @Override
        public void visit(InvokeFunctionOperation operation) {
            IPersistentVector toAdd = PersistentVector.EMPTY
                    .cons(TransactionOperation.Type.FN.getKeyword())
                    .cons(operation.getId());

            for (Object argument: operation.getArguments()) {
                toAdd = toAdd.cons(argument);
            }

            add(toAdd);
        }
    }

    public final IPersistentVector toVector() {
        EdnVisitor visitor = new EdnVisitor();
        accept(visitor);
        return visitor.vector;
    }

    public void accept(TransactionOperation.Visitor visitor) {
        for (TransactionOperation operation: operations) {
            operation.accept(visitor);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return operations.equals(that.operations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operations);
    }
}
