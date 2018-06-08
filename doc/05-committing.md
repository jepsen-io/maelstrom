# Committing

If you test more aggressively, you should be able to observe nonlinearizable behaviors in this system:

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --concurrency 10n --time-limit 60 --test-count 5
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

This is because our server still applies operations locally and returns results immediately--all our fancy log replication isn't actually being used to drive the state machine. In this section, we'll close the loop, and let Raft run the state machine. But before we do that, we're going to make a brief performance optimization that will make our changes much easier to demonstrate.

## Proxying to Leaders

If you look at `latency-raw.svg`, or have a glance at Maelstrom's logs during a test, you'll notice that almost all of our requests fail:

```
2018-06-07 20:12:08,733{GMT}  INFO  [jepsen worker 5] jepsen.util: 5  :fail :write  [1 2] [11 "not a leader"]
```

When we added the leader check in `kv_req`, we made it so *most* of the time,
only one node would actually execute client requests. When leaders change (e.g.
due to network partitions in longer tests), then we have a chance to observe
inconsistency between those two nodes. But fundamentally, the reason our tests
may have passed so far is because we simply weren't performing enough
operations, on enough different machines, to see anything interesting.

To change that, and just because most users expect requests to *any* node to succeed, we're going to internally proxy requests to whatever node we think is the current leader. We'll have RaftNode keep track of a `leader` variable...

```py
class RaftNode():
    def __init__(self):
				...

        # Raft state
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
        self.current_term = 0   # Our current Raft term
        self.voted_for = None   # What node did we vote for in this term?
        self.commit_index = 0   # The index of the highest committed entry
        self.leader = None      # Who do we think the leader is?

				...
```

On any state transition, we'll reset the leader.

```py
    def become_follower(self):
        """Become a follower"""
        self.state = 'follower'
        self.next_index = None
        self._match_index = None
        self.leader = None
        self.reset_election_deadline()
        log('Became follower for term', self.current_term)

    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        self.advance_term(self.current_term + 1)
        self.leader = None
        self.reset_election_deadline()
        self.reset_step_down_deadline()
        log('Became candidate for term', self.current_term)
        self.request_votes()

    def become_leader(self):
        """Become a leader"""
        if not self.state == 'candidate':
            raise RuntimeError('Should be a candidate')

        self.state = 'leader'
        self.leader = None
        self.last_replication = 0 # Start replicating immediately
        # We'll start by trying to replicate our most recent entry
        self.next_index = {n: self.log.size() + 1 for n in self.other_nodes()}
        self._match_index = {n: 0 for n in self.other_nodes()}
        self.reset_step_down_deadline()
        log('Became leader for term', self.current_term)
```

And when we accept an `append_entries` request, we'll remember that node as the
current leader.

```py
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

            if body['term'] < self.current_term:
                # Leader is behind us
                self.net.reply(msg, res)
                return None

            # This leader is valid; remember them and don't try to run our own
            # election for a bit
            self.leader = body['leader_id']
						...
```

Finally, we'll update `kv_req` with a new clause: if we're not the leader, but
we think we know who is, we'll proxy to them instead.

```py
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
            elif self.leader:
                # We're not the leader, but we can proxy to one
                msg['dest'] = self.leader
                self.net.send_msg(msg)
            else:
                self.net.reply(msg, {
                    'type': 'error',
                    'code': 11,
                    'text': 'not a leader'
                    })
```

Re-running our tests with this proxy layer, you'll see that instead of 2/3 of all requests failing, we can run more!

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --concurrency 10n --time-limit 60 --test-count 5
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

## Improving Latency

The second optimization we're going to make is really simple. If you look at
`latency-raw.png`, you'll notice a steady increase in request latencies over
time. The plot is logarithmic, so this increase is actually linear--and there's
a simple cause. We pretty-print the *entire* log every time it changes, and
that's slow.

```py
class Log():
		...

    def append(self, entries):
        """Appends multiple entries to the log."""
        self.entries.extend(entries)
        # log("Log:\n" + pformat(self.entries))
```

With the log line commented out, operations go dramatically faster. That'll
make it easier for us to test.

## Advancing the Commit Index

With our leaders elected, and log replicated, it's time to advance our commit
index. We can consider any entries that are committed on a majority of nodes committed--that's the median of the matching indices, rounding down.

```py
def median(xs):
    """Given a collection of elements, finds the median, biasing towards lower values if there's a tie."""
    xs = list(xs)
    xs.sort()
    return xs[len(xs) - majority(len(xs))]
```

Now we'll add an action to advance the commit index, based on the matching
index: wherever an index is committed on a majority of servers, and that log
index is in the current term, we can advance our commit index.

```py
class RaftNode():
    def advance_commit_index(self):
        """If we're the leader, advance our commit index based on what other nodes match us."""
        if self.state == 'leader':
            n = median(self.match_index().values())
            if self.commit_index < n and self.log.get(n)['term'] == self.current_term:
								log("Commit index now", n)
                self.commit_index = n
                return True
```

Let's integrate this action into the mainloop:

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
                    self.advance_commit_index() or \
                    time.sleep(0.001)
						...
```

And give it a shot! We'll run for 20 seconds to get a network partition; with luck there'll be a leader election, and we can observe two leaders incrementing their commit index.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --time-limit 20
...
$ grep -i 'commit index' store/latest/n*.log
store/latest/n2.log:2018-06-07 19:39:07.364446 Commit index now 10
store/latest/n2.log:2018-06-07 19:39:08.910013 Commit index now 11
store/latest/n2.log:2018-06-07 19:39:09.329154 Commit index now 12
store/latest/n2.log:2018-06-07 19:39:10.741716 Commit index now 13
store/latest/n2.log:2018-06-07 19:39:11.842613 Commit index now 14
store/latest/n2.log:2018-06-07 19:39:13.531075 Commit index now 15
store/latest/n2.log:2018-06-07 19:39:13.585516 Commit index now 16
store/latest/n2.log:2018-06-07 19:39:15.401975 Commit index now 17
store/latest/n3.log:2018-06-07 19:38:58.020056 Commit index now 2
store/latest/n3.log:2018-06-07 19:38:59.925751 Commit index now 3
store/latest/n3.log:2018-06-07 19:39:01.171993 Commit index now 4
store/latest/n3.log:2018-06-07 19:39:02.110645 Commit index now 6
store/latest/n3.log:2018-06-07 19:39:03.432639 Commit index now 7
store/latest/n3.log:2018-06-07 19:39:03.700480 Commit index now 8
store/latest/n3.log:2018-06-07 19:39:05.039762 Commit index now 9
```

Here, n3 was the initial leader, and n2 took over at 19:39:07. Looks good! Now
that we know what entries are committed, we can apply those entries to the
state machine.

## Applying Entries

We're going to add a new action for the mainloop--if there are unapplied,
committed entries in the log, we can apply one of those entries to the state
machine. First, we'll need a `last_applied` index, which identifies the most
recently applied entry:

```py
class RaftNode():
    def __init__(self):
				...

        # Raft state
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
        self.current_term = 0   # Our current Raft term
        self.voted_for = None   # What node did we vote for in this term?
        self.commit_index = 0   # The index of the highest committed entry
        self.last_applied = 1   # The last entry we applied to the state machine
```

Then we'll define an action which advances the state machine to the next
committed operation.

```py
class RaftNode():
		...

    # Actions for all nodes

    def advance_state_machine(self):
        """If we have unapplied committed entries in the log, apply one to the state machine."""
        if self.last_applied < self.commit_index:
            # Advance the applied index and apply that op
            self.last_applied += 1
            res = self.state_machine.apply(self.log.get(self.last_applied)['op'])
            if self.state == 'leader':
                # We were the leader, let's respond to the client.
                self.net.send(res['dest'], res['body'])

        # We did something!
        return True
```

Note that we're doing something a little odd here--once we've computed the
result of applying that operation, we send a result message back to the client.
We can do this because our `kv_req` handler made sure to save the client and
reply_to information in the log message.

To avoid duplicate messages, we only reply from leaders--but there's no
guarantee that the leader which sends that message will be the same one the
client started the operation on. Normally you'd respond via a TCP channel, but
we can play fast and loose because our network is message-, not
stream-oriented.

We'll add this as an action to the mainloop:

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
                    self.advance_commit_index() or \
                    self.advance_state_machine() or \
                    time.sleep(0.001)
```

Now that the state machine and responses are generated from the log, we can
drop the code that executes those operations locally, in the request handler:

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
            elif self.leader:
                # We're not the leader, but we can proxy to one
                msg['dest'] = self.leader
                self.net.send_msg(msg)
            else:
                self.net.reply(msg, {
                    'type': 'error',
                    'code': 11,
                    'text': 'not a leader'
                    })
				...
```

With these changes in place, you should be able to observe repeated
linearizable test results.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --concurrency 5n --time-limit 60 --test-count 10
...
```

Well, *mostly*.

```
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```
