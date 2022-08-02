#!/usr/bin/node

// A basic echo server
var node = require('./node');

node.on('echo', function(req) {
  node.reply(req, {type: 'echo_ok', 'echo': req.body.echo});
});

node.main();
