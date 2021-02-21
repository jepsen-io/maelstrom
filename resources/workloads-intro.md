# Workloads

A *workload* specifies the semantics of a distributed system: what
operations are performed, how clients submit requests to the system, what
those requests mean, what kind of responses are expected, which errors can
occur, and how to check the resulting history for safety.

For instance, the *broadcast* workload says that clients submit `broadcast`
messages to arbitrary servers, and can send a `read` request to obtain the
set of all broadcasted messages. Clients mix reads and broadcast operations
throughout the history, and at the end of the test, perform a final read
after allowing a brief period for convergence. To check broadcast histories,
Maelstrom looks to see how long it took for messages to be broadcast, and
whether any were lost.

This is a reference document, automatically generated from Maelstrom's source
code by running `lein run doc`. For each workload, it describes the general
semantics of that workload, what errors are allowed, and the structure of RPC
messages that you'll need to handle.
