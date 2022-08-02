#!/usr/bin/node

// A CRDT grow-only set
var node = require('./node');

// Our set of elements
var crdt = new Set();

// Serialize a CRDT to something that'll transport as JSON
function serialize(crdt) {
  return Array.from(crdt);
}

// Deserialize a JSON-transported structure to a CRDT
function deserialize(obj) {
  return new Set(obj);
}

// Merge two CRDTs
function merge(a, b) {
  const merged = new Set();
  for (const x of a) {
    merged.add(x);
  }
  for (const x of b) {
    merged.add(x);
  }
  return merged;
};

// Add new elements to our local state
node.on('add', function(req) {
  crdt.add(req.body.element);
  console.warn ("state after add:", crdt);
  node.reply(req, {type: 'add_ok'});
});

// When we get a read request, return our messages
node.on('read', function(req) {
  node.reply(req, {type: 'read_ok', value: Array.from(crdt)});
});

// When we receive a replication message, merge it into our CRDT
node.on('replicate', (req) => {
  crdt = merge(crdt, deserialize(req.body.value));
  console.warn("state after replicate:", crdt);
});

// When we initialize, start a replication loop
node.on('init', (req) => {
  setInterval(() => {
    console.warn('Replicate!');
    for (const peer of node.nodeIds()) {
      if (peer !== node.nodeId()) {
        node.send(peer, {type: 'replicate', value: serialize(crdt)});
      }
    }
  }, 5000);
});

node.main();
