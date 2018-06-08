#!/usr/bin/python
# coding=utf-8

from __future__ import unicode_literals
import json
import math
from pprint import pformat
import random
import select
import sys
import termios
import time
import traceback

# Utility stuff

def log(*args):
    """Helper function for logging stuff to stderr"""
    first = True
    sys.stderr.write(time.strftime("%Y-%m-%d %H:%M:%S ", time.localtime()))
    for i in range(len(args)):
        sys.stderr.write(str(args[i]))
        if i < (len(args) - 1):
            sys.stderr.write(" ")
    sys.stderr.write('\n')

def majority(n):
    """What number would constitute a majority of n nodes?"""
    return int(math.floor((n / 2.0) + 1))

def median(xs):
    """Given a collection of elements, finds the median, biasing towards lower values if there's a tie."""
    xs = list(xs)
    xs.sort()
    return xs[len(xs) - majority(len(xs))]

class Log():
    """Stores Raft entries, which are dicts with a :term field."""

    def __init__(self):
        """Construct a new Log"""
        # Note that we provide a default entry here, which simplifies
        # some default cases involving empty logs.
        self.entries = [{'term': 0, 'op': None}]

    def get(self, i):
        """Return a log entry by index. Note that Raft's log is 1-indexed."""
        return self.entries[i - 1]

    def append(self, entries):
        """Appends multiple entries to the log."""
        self.entries.extend(entries)
        #log("Log:\n" + pformat(self.entries))

    def last(self):
        """Returns the most recent entry"""
        return self.entries[-1]

    def last_term(self):
        "What's the term of the last entry in the log?"
        l = self.last()
        if l:
            return l['term']
        else:
            return 0

    def size(self):
        "How many entries are in the log?"
        return len(self.entries)

    def truncate(self, size):
        """Truncate the log to this many entries."""
        self.entries = self.entries[0:size]

    def from_index(self, i):
        "All entries from index i on"
        if i <= 0:
            raise LookupError('illegal index ' + i)
        return self.entries[i - 1:]

class Client():
    """Handles console IO for sending and receiving messages"""

    def __init__(self):
        """Construct a new network client."""
        self.node_id = None
        self.next_msg_id = 0
        self.handlers = {}
        self.callbacks = {}
        self.in_buffer = ""

    def set_node_id(self, id):
        self.node_id = id

    def new_msg_id(self):
        """Generate a fresh message ID"""
        id = self.next_msg_id
        self.next_msg_id += 1
        return id

    def on(self, msg_type, handler):
        """Register a callback for a message of the given type."""
        if msg_type in self.handlers:
            raise RuntimeError('already have a handler for message type ' + type)

        self.handlers[msg_type] = handler

    def send_msg(self, msg):
        """Sends a raw message object."""
        log('Sent\n' + pformat(msg))
        json.dump(msg, sys.stdout)
        sys.stdout.write('\n')
        sys.stdout.flush()

    def send(self, dest, body):
        """Sends a message body to the given node id. Fills in src with our own node_id."""
        self.send_msg({'src': self.node_id, 'dest': dest, 'body': body})

    def reply(self, req, body):
        """Replies to a request with a response body."""
        body['in_reply_to'] = req['body']['msg_id']
        self.send(req['src'], body)

    def rpc(self, dest, body, handler):
        """Sends an RPC request to dest and handles the response with handler."""
        msg_id = self.new_msg_id()
        self.callbacks[msg_id] = handler
        body['msg_id'] = msg_id
        self.send(dest, body)

    def readline_nonblock(self):
        """Consumes available input from stdin, filling our in_buffer. Returns a line if ready, or None if no line is ready yet."""
        # I *think* we can get away with just using readline directly here,
        # instead of building up the buffer and scanning through it for
        # newlines, but I dimly remember that in the ruby version, readline got
        # me into SOME sort of bug involving blocking/buffering, and I had to
        # back off to reading characters one at a time, and reimplementing
        # line parsing myself. I don't know if that's necessary here.
        while sys.stdin in select.select([sys.stdin], [], [], 0)[0]:
            line = sys.stdin.readline()
            if line:
                return line
            else:
                return None
        else:
            return None

    def process_msg(self):
        """Processes a message from stdin, if one is available."""
        line = self.readline_nonblock()
        if line:
            # Parse
            msg = json.loads(line)
            log("Received\n" + pformat(msg))
            body = msg['body']

            handler = None
            # Look up handler for this as an RPC reply
            if 'in_reply_to' in body:
                in_reply_to = body['in_reply_to']
                handler = self.callbacks[in_reply_to]
                del self.callbacks[in_reply_to]

            if not handler:
                # Look up handler for this type of message
                handler = self.handlers[msg['body']['type']]

            if not handler:
                raise RuntimeError('No callback or handler for', pformat(msg))

            handler(msg)
            return True

class KVStore():
    """A state machine providing a key-value store."""
    def __init__(self):
        self.state = {}

    def apply(self, op):
        """Applies an op to the state machine, and returns a response message"""
        res = None
        k = op['key']
        t = op['type']

        # Handle state transition
        if t == 'read':
            if k in self.state:
                res = {'type': 'read_ok', 'value': self.state[k]}
            else:
                res = {'type': 'error', 'code': 20, 'text': 'not found'}
        elif t == 'write':
            self.state[k] = op['value']
            res = {'type': 'write_ok'}
        elif t == 'cas':
            if k not in self.state:
                res = {'type': 'error', 'code': 20, 'text': 'not found'}
            elif self.state[k] != op['from']:
                res = {
                    'type': 'error',
                    'code': 22,
                    'text': 'expected ' + str(op['from']) + ' but had ' + str(self.state[k])
                    }
            else:
                self.state[k] = op['to']
                res = {'type': 'cas_ok'}

        log('KV:\n' + pformat(self.state))

        # Construct response
        res['in_reply_to'] = op['msg_id']
        return {'dest': op['client'], 'body': res}

class RaftNode():
    def __init__(self):
        # Heartbeats & Timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.heartbeat_interval = 1     # Time between heartbeats, in seconds
        self.min_replication_interval = 0.05    # Don't replicate too often
        self.election_deadline = 0      # Next election, in epoch seconds
        self.step_down_deadline = 0     # When to step down automatically
        self.last_replication = 0       # Last replication, in epoch seconds

        # Node & cluster ids
        self.node_id = None     # Our node ID
        self.node_ids = None    # List of all node IDs

        # Raft state
        self.state = 'nascent'  # nascent, follower, candidate, or leader
        self.current_term = 0   # Our current term
        self.voted_for = None   # What node did we vote for?
        self.commit_index = 0   # The index of the highest committed entry
        self.last_applied = 1   # The last entry we applied to the state machine
        self.leader = None      # Which node do we think the leader is?

        # Leader state
        self.next_index = None   # A map of nodes to the next index to replicate
        self._match_index = None # Map of nodes to the highest log entry known
                                 # to be replicated on that node.

        # Set up network and state machine
        self.log = Log()
        self.state_machine = KVStore()
        self.client = Client()
        self.setup_handlers()


    def other_nodes(self):
        """All nodes except this one."""
        nodes = list(self.node_ids)
        nodes.remove(self.node_id)
        return nodes

    def next_index(self):
        """Returns the map of next indices, including an entry for ourselves, based on our log size."""
        m = dict(self.next_index)
        m[self.node_id] = self.log.size() + 1
        return m

    def match_index(self):
        m = dict(self._match_index)
        m[self.node_id] = self.log.size()
        return m

    def set_node_id(self, id):
        """Assign our node ID."""
        self.node_id = id
        self.client.set_node_id(id)

    def brpc(self, body, handler):
        """Broadcast an RPC message to all other nodes, and call handler with each response."""
        for node in self.other_nodes():
            self.client.rpc(node, body, handler)

    def reset_election_deadline(self):
        """Oh, our leader is alive, don't start an election for a little while."""
        self.election_deadline = time.time() + (self.election_timeout * (random.random() + 1))

    def reset_step_down_deadline(self):
        """We got communication, don't step down for a while."""
        self.step_down_deadline = time.time() + self.election_timeout

    def advance_term(self, term):
        """Advance our term to `term`, resetting who we voted for."""
        if not self.current_term < term:
            raise RuntimeError("Can't go backwards")

        self.current_term = term
        self.voted_for = None

    def maybe_step_down(self, remote_term):
        """If remote_term is bigger than ours, advance our term and become a follower."""
        if self.current_term < remote_term:
            log("Stepping down: remote term", remote_term, "higher than our term", self.current_term)
            self.advance_term(remote_term)
            self.become_follower()

    def request_votes(self):
        """Request that other nodes vote for us as a new leader."""

        # We vote for ourself
        votes = set([self.node_id])
        term = self.current_term

        def handle(response):
            self.reset_step_down_deadline()
            body = response['body']
            self.maybe_step_down(body['term'])
            if self.state == 'candidate' and \
                    self.current_term == term and \
                    body['term'] == self.current_term and \
                    body['vote_granted']:

                # We have a vote for our candidacy
                votes.add(response['src'])
                log("Have votes:", pformat(votes))

                if majority(len(self.node_ids)) <= len(votes):
                    # We have a majority of votes for this term
                    self.become_leader()

        # Broadcast vote request
        self.brpc({
            'type':             'request_vote',
            'term':             self.current_term,
            'candidate_id':     self.node_id,
            'last_log_index':   self.log.size(),
            'last_log_term':    self.log.last_term()
            },
            handle)

    # State transitions

    def become_follower(self):
        """Become a follower"""
        self.state = 'follower'
        self.leader = None
        self.next_index = None
        self._match_index = None
        self.reset_election_deadline()
        log("Became follower for term ", self.current_term)

    def become_candidate(self):
        """Become a candidate, advance our term, and request votes."""
        self.state = 'candidate'
        self.advance_term(self.current_term + 1)
        self.voted_for = self.node_id
        self.leader = None
        log("Became candidate for term ", self.current_term)
        self.reset_election_deadline()
        self.request_votes()

    def become_leader(self):
        """Become a leader"""
        if not self.state == 'candidate':
            raise RuntimeError('Should be a candidate')

        log("Became leader for term", self.current_term)
        self.state = 'leader'
        self.leader = None
        self.last_replication = 0 # Start replicating immediately
        # We'll start by trying to replicate our most recent entry
        self.next_index = {n: self.log.size() + 1 for n in self.other_nodes()}
        self._match_index = {n: 0 for n in self.other_nodes()}

    # Actions for all nodes

    def advance_state_machine(self):
        """If we have unapplied committed entries in the log, apply one to the state machine."""
        if self.last_applied < self.commit_index:
            # Advance the applied index and apply that op
            self.last_applied += 1
            res = self.state_machine.apply(self.log.get(self.last_applied)['op'])
            if self.state == 'leader':
                # We were the leader, let's respond to the client.
                self.client.send(res['dest'], res['body'])

        # We did something!
        return True

    # Actions for leaders

    def step_down_on_timeout(self):
        """If we haven't received any acks for a while, step down."""
        if self.state == 'leader' and self.step_down_deadline < time.time():
            log("Stepping down: haven't received any acks recently")
            self.become_follower()
            return True

    def replicate_log(self, force):
        """If we're the leader, replicate unacknowledged log entries to followers. Also serves as a heartbeat."""

        # How long has it been since we replicated?
        elapsed_time = time.time() - self.last_replication
        # We'll set this to true if we replicate to anyone
        replicated = False
        # We'll need this to make sure we process responses in *this* term
        term = self.current_term

        if self.state == 'leader' and self.min_replication_interval < elapsed_time:
            # We're a leader, and enough time elapsed
            for node in self.other_nodes():
                # What entries should we send this node?
                ni = self.next_index[node]
                entries = self.log.from_index(ni)
                if 0 < len(entries) or self.heartbeat_interval < elapsed_time:
                    log('replicating ' + str(ni) + '+ to', node)
                    def handler(res):
                        body = res['body']
                        self.maybe_step_down(body['term'])
                        if self.state == 'leader' and term == self.current_term:
                            self.reset_step_down_deadline()
                            if body['success']:
                                # Record that this follower received the entries
                                self.next_index[node] = max(self.next_index[node], ni + len(entries))
                                self._match_index[node] = max(self._match_index[node], ni - 1 + len(entries))
                            else:
                                # Back up
                                self.next_index[node] -= 1

                    self.client.rpc(node, {
                        'type': 'append_entries',
                        'term': self.current_term,
                        'leader_id': self.node_id,
                        'prev_log_index': ni - 1,
                        'prev_log_term': self.log.get(ni - 1)['term'],
                        'entries': entries,
                        'leader_commit': self.commit_index
                        }, handler)
                    replicated = True

        if replicated:
            # We did something!
            self.last_replication = time.time()
            return True

    def leader_advance_commit_index(self):
        """If we're the leader, advance our commit index based on what other nodes match us."""
        if self.state == 'leader':
            n = median(self.match_index().values())
            if self.commit_index < n and self.log.get(n)['term'] == self.current_term:
                self.commit_index = n
                return True

    # Actions for followers/candidates

    def election(self):
        """If it's been long enough, trigger a leader election."""
        if self.election_deadline < time.time():
            if self.state == 'follower' or self.state == 'candidate':
                # It's time!
                self.become_candidate()
            else:
                # We're a leader or initializing, sleep again
                self.reset_election_deadline()
            return True

    # Message handlers
    def setup_handlers(self):
        """Registers message handlers with this node's client"""

        # Handle our cluster initialization message
        def raft_init(msg):
            if self.state != 'nascent':
                raise RuntimeError("can't init twice!")

            body = msg['body']
            self.set_node_id(body['node_id'])
            self.node_ids = body['node_ids']

            self.become_follower()

            log("Raft init: I am", self.node_id)
            self.client.reply(msg, {'type': 'raft_init_ok'})
        self.client.on('raft_init', raft_init)

        # When a node requests our vote...
        def request_vote(msg):
            body = msg['body']
            self.maybe_step_down(body['term'])
            grant = False
            if body['term'] < self.current_term:
                log('candidate term', body['term'], 'lower than', \
                        self.current_term, 'not granting vote')
            elif self.voted_for is not None:
                log('already voted for', self.voted_for, 'not granting vote')
            elif body['last_log_term'] < self.log.last_term():
                log("have log entries from term", self.log.last_term(), \
                        "which is newer than remote term", \
                        body['last_log_term'], "not granting vote")
            elif body['last_log_term'] == self.log.last_term() and \
                    body['last_log_index'] < self.log.size():
                log("Our logs are both at term", self.log.last_term(), \
                        "but our log is", self.log.size(), \
                        "and theirs is only", body['last_log_index'])
            else:
                log("Granting vote to", msg['src'])
                grant = True
                self.voted_for = body['candidate_id']
                self.reset_election_deadline()

            self.client.reply(msg, {
                'type': 'request_vote_res',
                'term': self.current_term,
                'vote_granted': grant
                })
        self.client.on('request_vote', request_vote)

        # When we're given entries by a leader
        def append_entries(msg):
            body = msg['body']
            self.maybe_step_down(body['term'])

            res = {'type': 'append_entries_res', 'term': self.current_term}

            if body['term'] < self.current_term:
                # Leader is behind us
                res['success'] = False
                self.client.reply(msg, res)
                return None

            # This leader is valid; remember them and don't try to run our own
            # election for a bit
            self.leader = body['leader_id']
            self.reset_election_deadline()

            # Check previous entry to see if it matches
            if 0 < body['prev_log_index']:
                try:
                    e = self.log.get(body['prev_log_index'])
                except IndexError:
                    e = None

                if (not e) or e['term'] != body['prev_log_term']:
                    # We disagree on the previous term
                    res['success'] = False
                    self.client.reply(msg, res)
                else:
                    # We agree on the previous log term; truncate and append
                    self.log.truncate(body['prev_log_index'])
                    self.log.append(body['entries'])

                    # Advance commit pointer
                    if self.commit_index < body['leader_commit']:
                        self.commit_index = min(body['leader_commit'], self.log.size(),)

                    # Acknowledge
                    res['success'] = True
                    self.client.reply(msg, res)
        self.client.on('append_entries', append_entries)

        # Handle client requests
        def add_log_entry(msg):
            body = msg['body']
            if self.state == 'leader':
                # Record who we should tell about the completion of this op
                body['client'] = msg['src']
                self.log.append([{'term': self.current_term, 'op': body}])
            elif self.leader:
                # We're not the leader, but we can proxy to them
                log("Proxying to", self.leader)
                msg['dest'] = self.leader
                self.client.send_msg(msg)
            else:
                self.client.reply(msg, {'type': 'error', 'code': 11, 'text': "not a leader, and don't know who is"})
        self.client.on('read',  add_log_entry)
        self.client.on('write', add_log_entry)
        self.client.on('cas',   add_log_entry)

    def main(self):
        """Mainloop. Alternates between actions."""
        log("Online.")
        while True:
            try:
                self.client.process_msg() or \
                        self.step_down_on_timeout() or \
                        self.replicate_log(False) or \
                        self.election() or \
                        self.leader_advance_commit_index() or \
                        self.advance_state_machine() or \
                        time.sleep(0.001)
            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())

RaftNode().main()
