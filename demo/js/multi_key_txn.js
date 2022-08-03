#!/usr/bin/node

// A simple list-append transaction service which stores data in multiple
// lww-kv thunks referenced by a single root map in lin-kv.
var node = require('./node');

// The services we store the root and thunks in
const rootStore = 'lin-kv';
const thunkStore = 'lww-kv';
// The key we store the root state in
const rootKey = 'root';

// The next unique local ID we generate
var nextId = 0;

// A map of thunk IDs to thunk values
var thunkCache = new Map();

// A cached root
var rootCache = new Map();

// Generate a fresh globally unique ID
function newId() {
  const id = node.nodeId() + '.' + nextId;
  nextId += 1;
  return id;
}

// Serialize a map to JSON
function serializeMap(m) {
  const pairs = [];
  m.forEach((v, k) => {
    pairs.push(k);
    pairs.push(v);
  });
  return pairs;
}

// Deserialize a map from JSON
function deserializeMap(pairs) {
  const m = new Map();
  for (var i = 0; i < pairs.length; i += 2) {
    m.set(pairs[i], pairs[i + 1]);
  }
  return m;
}

// Copies a map
function copyMap(m) {
  const m2 = new Map();
  m.forEach((v, k) => {
    m2.set(k, v);
  });
  return m2;
}

// Merges map m2 into map m1, returning a merged copy
function merge(m1, m2) {
  const m = copyMap(m1);
  m2.forEach((v, k) => {
    m.set(k, v);
  });
  return m;
}

// Fetches k from service svc, returning a default state if it does not exist.
async function getKey(service, k, notFound) {
  try {
    const body = await node.rpc(service, {type: 'read', key: k});
    return body.value;
  } catch (err) {
    if (err.code === 20) {
      return notFound;
    } else {
      throw err;
    }
  }
}

// Fetch a thunk from cache or storage, retrying until it's found
async function getThunk(id) {
  // Try cache
  const cached = thunkCache.get(id);
  if (cached != undefined) {
    return cached;
  }

  // Fetch from storage
  try {
    const body = await node.rpc(thunkStore, {type: 'read', key: id});
    thunkCache.set(id, body.value);
    return body.value;
  } catch (err) {
    if (err.code === 20) {
      return getThunk(id);
    } else {
      throw err;
    }
  }
}

// Write a thunk to storage
function writeThunk(id, v) {
  thunkCache.set(id, v);
  return node.rpc(thunkStore, {
    type:  'write',
    key:   id,
    value: v
  });
}

// Fetch the DB root from the storage service
async function getRoot() {
  const pairs = await getKey(rootStore, rootKey, []);
  return deserializeMap(pairs);
}

// Atomically change the root state from root to root2.
async function casRoot(root, root2) {
  try {
    await node.rpc(rootStore, {
      type:                 'cas',
      key:                  rootKey,
      from:                 serializeMap(root),
      to:                   serializeMap(root2),
      create_if_not_exists: true
    });
  } catch (err) {
    if (err.code === 22) {
      throw {code: 30, text: "root changed during txn"};
    } else {
      throw err;
    }
  }
}

// Fetch a partial state for a given root and transaction.
async function getState(root, txn) {
  const reads = [];
  const state = new Map();
  const keys = readSet(txn);
  root.forEach((id, k) => {
    if (keys.has(k)) {
      const read = getThunk(id).then((v) => {
        state.set(k, v);
      });
      reads.push(read);
    }
  });
  await Promise.all(reads);
  return state;
}

// Write all thunks for a given state and transaction, returning a partial root
// map reflecting just the updated keys.
async function writeThunks(state, txn) {
  const writes = [];
  const root = new Map();
  const keys = writeSet(txn);
  state.forEach((v, k) => {
    if (keys.has(k)) {
      const id = newId();
      const write = writeThunk(id, v).then((res) => {
        root.set(k, id);
      });
      writes.push(write);
    }
  });
  await Promise.all(writes);
  return root;
}

// Returns a set of keys written by a transaction
function writeSet(txn) {
  const keys = new Set();
  for (const [f, k, v] of txn) {
    if (f !== 'r') {
      keys.add(k);
    }
  }
  return keys;
}

// Returns a set of keys read by a transaction
function readSet(txn) {
  const keys = new Set();
  for (const [f, k, v] of txn) {
    keys.add(k);
  }
  return keys;
}

// Apply a transaction to a state, returning a [state', txn'] pair.
function applyTxn(state, txn) {
  const state2 = copyMap(state);
  const txn2 = [];
  for (const mop of txn) {
    const [f, k, v] = mop;
    const value = state2.get(k);
    switch (f) {
      case "r":
        txn2.push([f, k, value]);
        break;
      case "append":
        let value2;
        if (value === undefined) {
          value2 = [v];
        } else {
          value2 = [... value, v];
        }
        state2.set(k, value2);
        txn2.push(mop);
        break;
    }
  }
  return [state2, txn2];
}

// Execute a transaction, returning a completed txn.
async function transact(txn) {
  const root = rootCache;
  const state = await getState(root, txn);
  const [state2, txn2] = await applyTxn(state, txn);
  const root2 = merge(root, await writeThunks(state2, txn));
  // For read-only transactions, we can skip writing the root altogether--at
  // the cost of allowing stale reads.
  // if (writeSet(txn).size === 0) {
  //   return txn2;
  // }
  try {
    await casRoot(root, root2);
    rootCache = root2;
    return txn2;
  } catch (err) {
    console.warn("Root outdated; refreshing and retrying");
    rootCache = await getRoot();
    return transact(txn);
  }
}

node.on('txn', async function(req) {
  const txn2 = await transact(req.body.txn);
  node.reply(req, {type: 'txn_ok', 'txn': txn2});
});

node.main();
