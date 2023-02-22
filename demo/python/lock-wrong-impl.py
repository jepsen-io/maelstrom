#!/usr/bin/env python

"""Obviously wrong implementation for 'lock' workload for Maelstrom"""

from node import *

on('lock', lambda req: reply(req, type='lock_ok'))
on('unlock', lambda req: reply(req, type='unlock_ok'))

receive()
