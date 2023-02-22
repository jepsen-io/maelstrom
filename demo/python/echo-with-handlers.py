#!/usr/bin/env python

"""A basic echo server"""

from node import *

@handler
def echo(req):
    reply(req, {'type': 'echo_ok', 'echo': req.body.echo})

    # alternative using keyword arguments
    # reply(req, type='echo_ok', echo=req.body.echo)

# alternatives using on

# on('echo', lambda req: reply(req, {'type': 'echo_ok', 'echo': req.body.echo}))
# on('echo', lambda req: reply(req, type='echo_ok', echo=req.body.echo))

receive()

