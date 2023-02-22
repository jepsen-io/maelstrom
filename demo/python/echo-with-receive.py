#!/usr/bin/env python

"""A basic echo server"""

from node import *

while req := receive():
    reply(req, {'type': 'echo_ok', 'echo': req.body.echo})

    # alternative using keyword arguments
    # reply(req, type='echo_ok', echo=req.body.echo)


