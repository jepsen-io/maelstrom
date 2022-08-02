#!/usr/bin/node

// A CRDT PN-counter
var node = require('./node');

class GCounter {
  // Takes a map of node names to the count on that node.
  constructor(counts) {
    this.counts = counts;
  }

  // Returns the effective value of the counter.
  value() {
    var total = 0;
    for (const node in this.counts) {
      total += this.counts[node];
    }
    return total;
  }

  // Merges another GCounter into this one
  merge(other) {
    const counts = {... this.counts};
    for (const node in other.counts) {
      if (counts[node] == undefined) {
        counts[node] = other.counts[node];
      } else {
        counts[node] = Math.max(this.counts[node], other.counts[node]);
      }
    }
    return new GCounter(counts);
  }

  // Convert to a JSON-serializable object
  toJSON() {
    return this.counts;
  }

  // Inflates a JSON-serialized object back into a fresh GCounter
  fromJSON(json) {
    return new GCounter(json);
  }

  // Increment by delta
  increment(node, delta) {
    var count = this.counts[node];
    if (count == undefined) {
      count = 0;
    }
    var counts = {... this.counts};
    counts[node] = count + delta;
    return new GCounter(counts);
  }
}

class PNCounter {
  // Takes an increment GCounter and a decrement GCounter
  constructor(plus, minus) {
    this.plus = plus;
    this.minus = minus;
  }

  // The effective value is all increments minus decrements
  value() {
    return this.plus.value() - this.minus.value();
  }

  // Merges another PNCounter into this one
  merge(other) {
    return new PNCounter(
      this.plus.merge(other.plus),
      this.minus.merge(other.minus)
    );
  }

  // Converts to a JSON-serializable object
  toJSON() {
    return {plus: this.plus, minus: this.minus};
  }

  // Inflates a JSON-serialized object back into a fresh PNCounter
  fromJSON(json) {
    return new PNCounter(
      this.plus.fromJSON(json.plus),
      this.minus.fromJSON(json.minus)
    );
  }

  // Increment by delta
  increment(node, delta) {
    if (0 < delta) {
      return new PNCounter(this.plus.increment(node, delta), this.minus);
    } else {
      return new PNCounter(this.plus, this.minus.increment(node, delta * -1));
    }
  }
}

// Our CRDT state
var crdt = new PNCounter(new GCounter({}), new GCounter({}));

// Add new elements to our local state
node.on('add', function(req) {
  crdt = crdt.increment(node.nodeId(), req.body.delta);
  console.warn ("state after add:", crdt);
  node.reply(req, {type: 'add_ok'});
});

// When we get a read request, return our messages
node.on('read', function(req) {
  node.reply(req, {type: 'read_ok', value: crdt.value()});
});

// When we receive a replication message, merge it into our CRDT
node.on('replicate', (req) => {
  crdt = crdt.merge(crdt.fromJSON(req.body.value));
  console.warn("state after replicate:", crdt);
});

// When we initialize, start a replication loop
node.on('init', (req) => {
  setInterval(() => {
    console.warn('Replicate!');
    for (const peer of node.nodeIds()) {
      if (peer !== node.nodeId()) {
        node.send(peer, {type: 'replicate', value: crdt.toJSON()});
      }
    }
  }, 5000);
});

node.main();
