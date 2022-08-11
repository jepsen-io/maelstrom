#!/usr/bin/node

// The node object provides support for reading messages from STDIN, writing
// them to STDOUT, keeping track of basic state, writing pluggable handlers for
// client RPC requests, and sending our own RPCs.

exports.nodeId = "";

// For console IO
var readline = require('readline');
var rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false
});


// STATE ///////////////////////////////////////////////////////////////////

// The local node ID
var nodeId;
// All nodes in the cluster
var nodeIds;
// Our RPC request timeout, in millis
var rpcTimeout = 1000;

// The next message ID we'll emit
var nextMsgId = 0;
// A map of RPC request message IDs to {resolve, reject} functions to handle
// their replies.
var replyHandlers = new Map();

// A map of request message types to functions which handle them.
var handlers = {};

// UTILITIES //////////////////////////////////////////////////////////////

// Our own node ID
exports.nodeId = function() {
  return nodeId;
};

// All node IDs
exports.nodeIds = function() {
  return nodeIds;
};

// Generate a new message ID
function newMsgId() {
  let id = nextMsgId;
  nextMsgId += 1;
  return id;
};
exports.newMsgId = newMsgId;

// IO /////////////////////////////////////////////////////////////////////

// Send a message body to the given node.
function send(dest, body) {
  let msg = {src: nodeId, dest: dest, body: body};
  console.warn("Sending", msg);
  console.log(JSON.stringify(msg));
};
exports.send = send;

// Reply to a request with a given response body.
function reply(req, body) {
  if (req.body.msg_id == undefined) {
    throw {code: 13,
           text: "Can't reply to request without message id: " +
                 JSON.stringify(req)};
  }
  let body2 = {... body, in_reply_to: req.body.msg_id};
  send(req.src, body2);
};
exports.reply = reply;

// Send an RPC request, returning a promise of a response which will be
// delivered a response body.
function rpc(dest, body) {
  let msgId = newMsgId();
  let body2 = {... body, msg_id: msgId};
  let promise = new Promise((resolve, reject) => {
    // Save promise delivery functions for later
    replyHandlers.set(msgId, {resolve: resolve, reject: reject});
    // Time out RPC
    setTimeout(() => {
      replyHandlers.delete(msgId);
      reject({type: 'error',
        in_reply_to: msgId,
        code: 0,
        text: 'RPC request timed out'});
    }, rpcTimeout);
  });
  // And send request
  send(dest, body2);
  return promise;
}
exports.rpc = rpc;

// Send an RPC request, and if it fails, retry it forever.
function retryRPC(dest, body) {
  return rpc(dest, body).catch((err) => {
    console.warn("Retrying RPC request to", dest, body);
    return retryRPC(dest, body);
  });
}
exports.retryRPC = retryRPC;

// REQUEST HANDLING ///////////////////////////////////////////////////////

// Register a handler for a given message type.
exports.on = function(type, handler) {
  handlers[type] = handler;
};

// Handle an init request
function handleInit(req) {
  body = req.body;
  nodeId = body.node_id;
  nodeIds = body.node_ids;
  console.warn("Node", nodeId, "initialized");
};

// Sends an error back to the client
function maybeReplyError(req, err) {
  if (body.msg_id != undefined) {
    // We can reply
    if (err.code != undefined) {
      // We have an explicitly tagged error code
      reply(req, {... err, type: 'error'});
    } else {
      // Reply with a generic error
      reply(req, {
        type: 'error',
        code: 13,
        text: String(err)
      });
    }
  }
}

// Handle a request
function handle(req) {
  try {
    let body = req.body;
    if (body.in_reply_to != undefined) {
      // This is an RPC reply; look up the promise resolve/reject functions
      let handler = replyHandlers.get(body.in_reply_to);
      // If there's no handler, this might be a duplicate message--we'll quietly
      // ignore it.
      if (handler != undefined) {
        replyHandlers.delete(body.in_reply_to);
        // If we get an error, reject the promise; otherwise deliver it.
        if (body.type === 'error') {
          handler.reject(body);
        } else {
          handler.resolve(body);
        }
      }
      return;
    }

    let type = body.type;
    if (type == "init") {
      // Special case: we call our special init handler, then a custom handler
      // if available, then ack.
      handleInit(req);
      let handler = handlers.init;
      if (handler != undefined) {
        handler(req);
      }
      reply(req, {type: 'init_ok'});
      return;
    }

    // Look up a handler for this type of message
    let handler = handlers[type];
    if (handler == undefined) {
      console.warn("Don't know how to handle msg type", type, "(", req, ")");
      reply(req, {type: 'error',
        code: 10,
        text: 'unsupported request type ' + type
      });
    } else {
      // Good, we have a handler; invoke it with our request.
      if (handler.constructor.name === 'AsyncFunction') {
        // For async handlers, attach an exception handler
        handler(req).catch((err) => {
          console.warn("Error processing async request", req, err);
          maybeReplyError(req, err);
        });
      } else {
        // Just call it directly; it'll explode.
        handler(req);
      }
    }
  } catch (err) {
    console.warn("Error processing request", err);
    maybeReplyError(req, err);
  }
};

exports.main = function() {
  rl.on('line', function(line) {
    console.warn("Got", line);
    handle(JSON.parse(line));
  });
};
