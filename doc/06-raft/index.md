# Chapter 5: Raft

In this chapter, we write a distributed, linearizable key-value store using the
Raft consensus algorithm.

1. [Bootstrapping](01-bootstrapping.md)
2. [A Key-Value Store](02-key-value.md)
3. [Leader Election](03-leader-election.md)
4. [Replicating Logs](04-replication.md)
5. [Committing](05-committing.md)

## Protocol

### Error Codes

These tests use the following error codes:

```
20   The given key does not exist
21   The given key already exists
22   A precondition (e.g. a compare-and-set comparison) failed
```

All three are definite failures: they indicate the operation did not take
place.


### Writes

Maelstrom will simulate client writes by sending messages like:

```edn
{"type"     "write"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "value"    A string: the value the client would like to write}
```

Keys should be created if they do not already exist. Respond to writes by
returning:

```edn
{"type"         "write_ok"
 "in_reply_to"  The msg_id of the write request}
```


### Reads

Maelstrom will simulate client reads by sending messages like:

```edn
{"type"       "read"
 "msg_id"     An integer
 "key"        A string: the key the client would like to read}
```

Respond to reads by returning:

```edn
{"type"         "read_ok"
 "in_reply_to"  The msg_id of the read request
 "value"        The string value for that key
```

If the key does not exist, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```


### Compare and Set

Maelstrom will simulate client compare-and-set operations by sending messages
like:

```edn
{"type"     "cas"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "from"     A string: the value that the client expects to be present
 "to"       A string: the value to write if and only if the value is `from`}
```

If the current value of the given key is `from`, set the key's value to `to`, and return:

```edn
{"type"         "cas_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```

If the current value is *not* `from`, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         22}
```

### Delete

Maelstrom will simulate client deletes by sending messages like:

```edn
{"type"       "delete"
 "msg_id"     An integer
 "key"        A string: the key to delete}
```

Delete the key from your table, and return:

```edn
{"type"         "delete_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return:

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```
