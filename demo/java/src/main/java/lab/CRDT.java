package lab;

import com.eclipsesource.json.JsonValue;

// This bit of recursive type hackery is how we convince Java that
// gset.merge(...) returns specifically a gset, not a generic CRDT.
public interface CRDT<T extends CRDT<T>> extends IJson {
    // IJson is how we serialize the CRDT for replication

    // CRDTs can be read off as a current value, which we represent as a JSON value
    // of some kind.
    public JsonValue read();

    // They can also be merged with a JSON value from replication
    public T merge(JsonValue other);
}