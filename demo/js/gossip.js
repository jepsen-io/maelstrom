#!/usr/bin/node

// A gossip system which supports the Maelstrom broadcast workload
var node = require('./node');

// Our local peers: an array of nodes.
var peers;

// Our set of messages received.
var messages = new Set();

// Save our topology when it arrives
node.on('topology', function(req) {
  peers = req.body.topology[node.nodeId()];
  console.warn("My peers are", peers);
  node.reply(req, {type: 'topology_ok'});
});

// When we get a read request, return our messages
node.on('read', function(req) {
  node.reply(req, {type: 'read_ok', messages: Array.from(messages)});
});

// When we get a broadcast, add it to the message set and broadcast it to peers
node.on('broadcast', function(req) {
  let m = req.body.message;
  if (! messages.has(m)) {
    // We haven't seen this yet; save it
    messages.add(m);
    // Broadcast to peers except the one who sent it to us
    for (const peer of peers) {
      if (peer !== req.src) {
        node.retryRPC(peer, {type: 'broadcast', message: m});
      }
    }
  }
  node.reply(req, {type: 'broadcast_ok'});
});

node.main();
