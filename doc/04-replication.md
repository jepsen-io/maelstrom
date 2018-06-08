# Replicating Logs

First, we'll need nontrivial logs to replicate! Right now, all our logs have a single, hardcoded entry. Let's add a function to append more entries to the log.

```py
class Log():
		...

    def append(self, entries):
        """Appends multiple entries to the log."""
        self.entries.extend(entries)
        log("Log:\n" + pformat(self.entries))
```

Now we need some entries to append! For the time being, we'll keep processing
all key-value operations locally, but as a side effect, we'll also append those
operations to the leader's log. Later, when we've got a working commit index,
we'll advance the state machine based on log entries.

```py
class RaftNode():
    ...

    def setup_handlers(self):
        ...

        # Handle client KV requests
        def kv_req(msg):
            if self.state == 'leader':
                op = msg['body']
                op['client'] = msg['src']
                self.log.append([{'term': self.current_term, 'op': op}])
                res = self.state_machine.apply(op)
                self.net.send(res['dest'], res['body'])
            else:
                self.net.reply(msg, {
                    'type': 'error',
                    'code': 11,
                    'text': 'not a leader'
                    })
				...
```

If we run some requests through this, we can observe the log on leaders filling
up.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ cat store/latest/n3.log
2018-06-07 18:14:02.610791 Log:
[{'op': None, 'term': 0},
 {'op': {'client': u'c6',
         u'from': 3,
         u'key': 0,
         u'msg_id': 2,
         u'to': 0,
         u'type': u'cas'},
  'term': 1}]
```

## Sending Logs

Now that we have log entries, it's time to replicate them to other nodes. To do
this, we'll need to keep track of two maps--one telling us the last known
identical log entry on each node, and one for the next entry we want to send to
each node. We also need to remember the last time we sent appendEntries to
other nodes, because that's our heartbeat mechanism, for keeping a stable
leadership. We'll add a commit index, which tracks the highest known committed log entry. Finally, we'll add a lower threshold for replication, to keep us
from spamming the network with messages where we could efficiently batch
instead.

```py
class RaftNode():
    def __init__(self):
        # Heartbeats & timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.heartbeat_interval = 1     # Time between heartbeats, in seconds
        self.min_replication_interval = 0.05    # Don't replicate TOO frequently
        self.election_deadline = 0      # Next election, in epoch seconds
        self.step_down_deadline = 0     # When to step down automatically
        self.last_replication = 0       # Last replication, in epoch seconds

        # Node & cluster IDS
        self.node_id = None     # Our node ID
        self.node_ids = None    # The set of node IDs

        # Raft state
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
        self.current_term = 0   # Our current Raft term
        self.voted_for = None   # What node did we vote for in this term?

        # Leader state
                # Leader state
        self.next_index = None   # A map of nodes to the next index to replicate
        self._match_index = None # Map of nodes to the highest log entry known
                                 # to be replicated on that node.
```

We're putting an underscore in front of `match_index` because it's not going to
be total--it'll cache the values for other nodes, and we'll fill in a
value for ourselves on demand.

```py
    def match_index(self):
        """Returns the map of match indices, including an entry for ourselves, based on our log size."""
        m = dict(self._match_index)
        m[self.node_id] = self.log.size()
        return m
```

When we become a follower, we'll clear our match_index and next_index:

```py
    def become_follower(self):
        """Become a follower"""
        self.state = 'follower'
        self.next_index = None
        self._match_index = None
        self.reset_election_deadline()
        log('Became follower for term', self.current_term)
```

And when we become a leader, we'll initialize match_index and next_index, as
per the paper. We'll also set last_replication to 0, so that we'll
*immediately* broadcast heartbeat to other nodes.

```py
    def become_leader(self):
        """Become a leader"""
        if not self.state == 'candidate':
            raise RuntimeError('Should be a candidate')

        self.state = 'leader'
        self.last_replication = 0 # Start replicating immediately
        # We'll start by trying to replicate our most recent entry
        self.next_index = {n: self.log.size() + 1 for n in self.other_nodes()}
        self._match_index = {n: 0 for n in self.other_nodes()}
        self.reset_step_down_deadline()
        log('Became leader for term', self.current_term)
```

Now, we need to periodically replicate leader log entries to followers. To do that, we'll need to obtain slices of the log from one index to the end, so let's add a small helper function to Log:

```py
class Log():
		...

    def from_index(self, i):
        "All entries from index i on"
        if i <= 0:
            raise LookupError('illegal index ' + i)
        return self.entries[i - 1:]
```

With that ready, we can create an action for leaders to replicate. Hang on; this one's a doozy.

```py
class RaftNode():
		...

    def replicate_log(self, force):
        """If we're the leader, replicate unacknowledged log entries to followers. Also serves as a heartbeat."""

        # How long has it been since we replicated?
        elapsed_time = time.time() - self.last_replication
        # We'll set this to true if we replicate to anyone
        replicated = False
        # We'll need this to make sure we process responses in *this* term
        term = self.current_term
```

We only want to replicate if we're a leader, and avoid replicating too frequently:

```py
        if self.state == 'leader' and self.min_replication_interval < elapsed_time:
            # We're a leader, and enough time elapsed
```

Then, for every other node, we'll look at the next index we want to send that
node, take all higher entries from our log.

```py
            for node in self.other_nodes():
                # What entries should we send this node?
                ni = self.next_index[node]
                entries = self.log.from_index(ni)
```

Whenever there are new entries to replicate, or, for heartbeats, if we haven't
replicated in the heartbeat interval, we'll send this node an `appendEntries`
message.

```py
                if 0 < len(entries) or self.heartbeat_interval < elapsed_time:
                    log('replicating ' + str(ni) + '+ to', node)
```

On receiving a response, we may need to step down--but if we're still a leader
for the same term, we'll reset our step-down deadline, since we're still in
touch with some followers. If the node acknowledges our request, we'll advance
their next and matching index; otherwise, we'll back up and try again.

```py
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
```

Then we'll issue the appendEntries request, and remember that we sent at least one message.

```py
                    self.net.rpc(node, {
                        'type': 'append_entries',
                        'term': self.current_term,
                        'leader_id': self.node_id,
                        'prev_log_index': ni - 1,
                        'prev_log_term': self.log.get(ni - 1)['term'],
                        'entries': entries,
                        'leader_commit': self.commit_index
                        }, handler)
                    replicated = True
```

To finish up, if we replicated anything, we'll record this replication time,
and return True, so the mainloop knows we did something.

```py
        if replicated:
            # We did something!
            self.last_replication = time.time()
            return True
```

Finally, we'll add `replicate_log` as an action in the mainloop.

```py
    def main(self):
        """Mainloop."""
        log('Online.')

        while True:
            try:
                self.net.process_msg() or \
                    self.step_down_on_timeout() or \
                    self.replicate_log() or \
                    self.election() or \
                    time.sleep(0.001)
						...
```

Let's give that a shot! If we look in the logs, we should be able to see nodes
issuing `append_entries` messages with entries from their local log.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --time-limit 10
...
$ cat store/latest/n1.log
2018-06-07 18:48:23.362933 replicating 2+ to n1
2018-06-07 18:48:23.363431 Sent
{'body': {'entries': [{'op': {'client': u'c6',
                              u'key': 0,
                              u'msg_id': 2,
                              u'type': u'read'},
                       'term': 1}],
          'leader_commit': 0,
          'leader_id': u'n3',
          'msg_id': 4,
          'prev_log_index': 1,
          'prev_log_term': 0,
          'term': 1,
          'type': 'append_entries'},
 'dest': u'n1',
 'src': u'n3'}
```

Next, we'll interpret those appendEntries messages, and figure out how to
respond.

## Receiving Logs

When we're given entries by a leader, we need to possibly step down, check to
make sure the leader is higher than us, then check to see if the leader's
previous log index and term match our log, before truncating and appending
entries to our local log.

Log truncation is important--we need to delete all mismatching log entries after the given index. We'll add that functionality to our Log:

```py
class Log():
		...
    def truncate(self, size):
        """Truncate the log to this many entries."""
        self.entries = self.entries[0:size]
```

With that, we can add a message handler to RaftNode which responds to incoming
`append_entries` requests. We begin by pulling out the message body, and
stepping down & adopting the new term if the leader that sent this request had
a higher term than us.

```py
class RaftNode():
		...

		def setup_handlers(self):
				...

        # When we're given entries by a leader
        def append_entries(msg):
            body = msg['body']
            self.maybe_step_down(body['term'])

            res = {
                    'type': 'append_entries_res',
                    'term': self.current_term,
                    'success': False
                    }
```

This default response is unsuccessful; we'll fill in `success = True` if all
the preconditions are satisfied. First, we reject messages from older leaders:

```py
            if body['term'] < self.current_term:
                # Leader is behind us
                self.net.reply(msg, res)
                return None
```

If we get a message from a valid leader, we'll defer our election deadline--we
don't want to compete with that leader. Then we'll check the previous log
entry (provided one exists):

```py
						self.reset_election_deadline()

            # Check previous entry to see if it matches
            if body['prev_log_index'] <= 0:
                raise RuntimeError("Out of bounds previous log index" + \
                        str(body['prev_log_index'])

            try:
                e = self.log.get(body['prev_log_index'])
            except IndexError:
                e = None
```

If the previous entry doesn't exist, or if we disagree on its term, we'll
reject this request.

```py
					 if (not e) or e['term'] != body['prev_log_term']:
								# We disagree on the previous term
								self.net.reply(msg, res)
								return None
```

If we made it this far, then we do agree on the previous log term! We'll
truncate our log to that point, and append the given entries. We'll also
advance our commit pointer to the leader's (or however high we can go).

```py
						# We agree on the previous log term; truncate and append
						self.log.truncate(body['prev_log_index'])
						self.log.append(body['entries'])

						# Advance commit pointer
						if self.commit_index < body['leader_commit']:
								self.commit_index = min(body['leader_commit'], self.log.size(),)
```

Finally, we'll acknowledge the append to the leader:

```py
           # Acknowledge
            res['success'] = True
            self.net.reply(msg, res)
```

And register this handler for incoming `append_entries` messages:

```py
        self.net.on('append_entries', append_entries)
```

If we try running this, we should be able to see successful `append_entries`
results in the log!

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --time-limit 10
...
$ grep -C 6 'append_entries_res' store/latest/n*.log
...
store/latest/n3.log-2018-06-07 19:16:33.726932 Sent
store/latest/n3.log-{'body': {'in_reply_to': 134,
store/latest/n3.log-          'success': True,
store/latest/n3.log-          'term': 1,
store/latest/n3.log:          'type': 'append_entries_res'},
store/latest/n3.log- 'dest': u'n1',
store/latest/n3.log- 'src': u'n3'}
```

Moreover, with append_entries messages resetting the election deadline, leadership is now *stable*:

```
$ grep -i 'became leader' store/latest/n*.log
store/latest/n1.log:2018-06-07 19:16:26.590502 Became leader for term 1
```

Node `n1` was the leader for this entire test. We can test timeouts and
elections in the face of network partitions by running the test for
longer--Jepsen introduces partitions every 20 seconds.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --time-limit 60
...
$ grep -i 'became leader' store/latest/n*.log
store/latest/n1.log:2018-06-07 19:21:54.522271 Became leader for term 1
store/latest/n1.log:2018-06-07 19:22:14.356116 Became leader for term 6
store/latest/n1.log:2018-06-07 19:22:34.233333 Became leader for term 11
store/latest/n2.log:2018-06-07 19:22:45.066359 Became leader for term 13
$ grep -i "haven't" store/latest/n*.log
store/latest/n1.log:2018-06-07 19:22:43.095562 Stepping down: haven't received any acks recently

```

We observe leaders changing at approximately the same frequency as network
partitions change.

Next up, we'll have leaders advance their commit index, and update the state
machine with committed log entries.
