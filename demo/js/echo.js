#!/usr/bin/node

var readline = require('readline');
var rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false
});

// Local state
var nodeId;
var nodeIds;
var nextMsgId = 0;

// Mainloop
rl.on('line', function(line) {
  console.warn("got", line);
  handle(JSON.parse(line));
});

// Send a message body to the given destination STDOUT
function sendMsg(dest, body) {
  let msg = {src: nodeId, dest: dest, body: body};
  console.log(JSON.stringify(msg));
}

// Reply to the given request message with the given response body.
function reply(req, resBody) {
  let body = {... resBody, in_reply_to: req.body.msg_id};
  sendMsg(req.src, body);
}

// Handle a request message from stdin
function handle(req) {
  switch (req.body.type) {
    case 'init': handleInit(req); break;
    case 'echo': handleEcho(req); break;
    default:
      console.warn("Don't know how to handle message of type", req.body.type,
        req);
  }
}

// Handle an initialization message
function handleInit(req) {
  let body = req.body;
  nodeId = body.node_id;
  nodeIds = body.node_ids;
  console.warn('I am node', nodeId);
  reply(req, {type: 'init_ok'});
}

// Handle an echo message
function handleEcho(req) {
  reply(req, {type: 'echo_ok', echo: req.body.echo});
}
