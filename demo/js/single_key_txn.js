#!/usr/bin/node

// A simple list-append transaction service which stores data in a single key
// in lin-kv.
var node = require('./node');

// The service we store the state in
const storage = 'lin-kv';
// The key we store state in
const storageKey = 'state';

// Our in-memory state: a map of keys to values.
var state = new Map();

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

// Fetches k from storage, returning a default state if it does not exist.
async function getKey(k, notFound) {
  try {
    const body = await node.rpc(storage, {type: 'read', key: k});
    return body.value;
  } catch (err) {
    if (err.code === 20) {
      return notFound;
    } else {
      throw err;
    }
  }
}

// Fetch the DB state from the storage service
async function getState() {
  const pairs = await getKey(storageKey, []);
  return deserializeMap(pairs);
}

// Atomically change the state from state to state2.
async function casState(state, state2) {
  try {
    await node.rpc(storage, {
      type:                 'cas',
      key:                  storageKey,
      from:                 serializeMap(state),
      to:                   serializeMap(state2),
      create_if_not_exists: true
    });
  } catch (err) {
    if (err.code === 22) {
      throw {code: 30, text: "state changed during txn"};
    } else {
      throw err;
    }
  }
}

// Apply a transaction to a state, returning a [state', txn'] pair.
function applyTxn(state, txn) {
  const state2 = copyMap(state);
  const txn2 = [];
  for (mop of txn) {
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

node.on('txn', async function(req) {
  const state = await getState();
  const [state2, txn2] = await applyTxn(state, req.body.txn);
  await casState(state, state2);
  node.reply(req, {type: 'txn_ok', 'txn': txn2});
});

node.main();
