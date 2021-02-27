# Datomic Transactor

In this chapter, we implement a strict-serializable key-value store by following [Datomic's transactor approach](https://docs.datomic.com/cloud/transactions/acid.html): data is stored in an eventually consistent key-value store, and a single mutable reference to that data is stored in a linearizable key-value store.

1. [A single-node transactor](01-single-node.md)
2. [Shared state](02-shared-state.md)
3. [Persistent Trees](03-persistent-trees.md)
4. [Optimization](04-optimization.md)
