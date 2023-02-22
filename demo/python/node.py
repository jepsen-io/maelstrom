#!/usr/bin/env python

"""Basic support to implement a Maelstrom node.

Allows initializing node (providing a default init handler), storing node_id
and node_ids, sending or replying to messages, writing message handlers or
using explicit receive (or some combination).

Messages can be provided as a dictionary or SimpleNamespace and are returned
as SimpleNamespace to allow dot notation. The message body in send and reply
functions can be defined by a dict/SimpleNamespace or/and keyword arguments.
"""

import json
import sys
from types import SimpleNamespace as sn

_node_id = None
_node_ids = None
_msg_id = 0
_handlers = {}

def node_id():
    """Returns node id"""
    return _node_id

def node_ids():
    """Returns list of ids of nodes in the cluster"""
    return _node_ids

def log(data, end='\n'):
    """Writes to stderr."""
    print(data, file=sys.stderr, flush=True, end=end)

def send(dest, body={}, /, **kwds):
    """Sends message to dest."""
    global _msg_id
    _msg_id += 1
    if isinstance(body, dict):
        body = body.copy()
    else:
        body = dict(vars(body))
    body.update(kwds, msg_id=_msg_id)
    msg = dict(src=_node_id, dest=dest, body=body)
    data = json.dumps(msg, default=vars)
    log("Sent " + data)
    print(data, flush=True)

def reply(req, body={}, /, **kwds):
    """Sends reply message to given request."""
    send(req.src, body, in_reply_to=req.body.msg_id, **kwds)

def on(type, handler):
    """Register handler for message type."""
    _handlers[type] = handler

def handler(func):
    """Decorator: makes function as handler for namesake message type."""
    _handlers[func.__name__] = func
    return func

@handler
def init(msg):
    """Default handler for init message."""
    global _node_id, _node_ids
    _node_id = msg.body.node_id
    _node_ids = msg.body.node_ids
    reply(msg, type='init_ok')

def _receive():
    data = sys.stdin.readline()
    if data:
        log("Received " + data, end='')
        return json.loads(data, object_hook=lambda x: sn(**x))
    else:
        return None

def receive():
    """Returns next message received with no handler defined.

    Messages with handlers defined are handled and not returned.
    Returns None on EOF.
    """
    while True:
        msg = _receive()
        if msg is None:
            return None
        elif (t := msg.body.type) in _handlers:
            _handlers[t](msg)
        else:
            return msg

