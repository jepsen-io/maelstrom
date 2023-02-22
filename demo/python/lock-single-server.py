#!/usr/bin/env python

"""Single server (centralized) lock for Maelstrom

For 'lock' workload.  Will pass the test using, e.g.,
"--node-count 1 --concurrency 3n", but will fail using, .e.g.,
"--node-count 3".
"""

from node import *

acquired = None
requests = []

while msg := receive():
    match msg.body.type:
        case 'lock':
            if not acquired:
                reply(msg, type='lock_ok')
                acquired = msg
            else:
                requests.append(msg)
        case 'unlock':
            if acquired and acquired.src == msg.src:
                reply(msg, type='unlock_ok')
                if requests:
                    acquired = requests.pop(0)
                    reply(acquired, type='lock_ok')
                else:
                    acquired = None
            else:
                reply(msg, type='error', code=22,
                      text='lock not owned by ' + msg.src)


