# Leader Election

With the basics of network communication and key-value operations done, it's
time to implement the Raft protocol. We'll be basing our implementation off of
the [Raft Paper](https://raft.github.io/raft.pdf), so if you haven't read it
carefully, now's a great time.

## Becoming a Candidate

The first step in leader election is becoming a *candidate*.

```py
class RaftNode():
		...

    # Role transitions
    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        log('Became candidate')
```

Now let's have our mainloop alternate between starting the election process and
processing incoming messages:

```py
    def main(self):
        """Mainloop."""
        log('Online.')
                
        while True:
            try:
                self.net.process_msg()
                self.become_candidate()
            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())
```

Hmm. This isn't going to work. `process_msg` blocks, which means if we don't
receive a network message, we'll never become a candidate. We need to make
these operations asynchronous.

We'll change `process_msg` to use `select`. If we don't have any stdin, we'll
return `None`, and if we do get a message and process it, we'll return True.
Having each asynchronous action in the mainloop return whether it did work
allows us to have a default action--sleeping--which prevents us from spinning
the CPU constantly.

```py
import select

...

class Net():
		...

    def process_msg(self):
        """Handles a message from stdin, if one is currently available."""
        if sys.stdin not in select.select([sys.stdin], [], [], 0)[0]:
            return None

        line = sys.stdin.readline()
        if not line:
            return None

        msg = json.loads(line)
        log("Received\n" + pformat(msg))
        body = msg['body']

        handler = None
        # Look up reply handler
        if 'in_reply_to' in body:
            m = body['in_reply_to']
            handler = self.callbacks[m]
            del self.callbacls[m]

        # Fall back based on message type
        elif body['type'] in self.handlers:
            handler = self.handlers[body['type']]

        else:
            raise RuntimeError('No callback or handler for\n' + pformat(msg))

        handler(msg)
        return True
```

We'll also have an action that becomes a candidate.

```py
class RaftNode():
		...

    # Actions for followers/candidates

    def election(self):
        "If it's been long enough, trigger a leader election."""
        self.become_candidate()
        return True
```

And in our mainloop, we'll try *either* processing a message, becoming a
candidate, or sleeping for a bit.

```py
    def main(self):
        """Mainloop."""
        log('Online.')

        while True:
            try:
                self.net.process_msg() or \
                    self.election() or \
                    time.sleep(0.001)

            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())
```

If you run this, you'll find that it still responds to requests, but also
*constantly* becomes a candidate.

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 1 --time-limit 10
...
$ cat store/latest/n1.log
2018-06-07 15:22:55.455023 Became candidate
2018-06-07 15:22:55.456173 Became candidate
2018-06-07 15:22:55.457294 Became candidate
...
```

Let's tone that down a bit, by only becoming a candidate *once enough time
has passed*.

```py
import random
...

class RaftNode():
    def __init__(self):
        # Heartbeats & timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.election_deadline = 0      # Next election, in epoch seconds

    def reset_election_deadline(self):
        """Don't start an election for a little while."""
        self.election_deadline = time.time() + (self.election_timeout * (random.random() + 1))
```

When we become a candidate, we'll push out the deadline for a new election

```py
    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        self.reset_election_deadline()
        log('Became candidate')
```

And when we hold an election, we'll check to see if it's actually time to start
an election, and make sure that we're either a follower or a candidate. If
we're a leader, we shouldn't flip back to candidate status, and if we're still
nascent, we don't know the cluster membership and won't be able to hold an
election.

```py
    def election(self):
        "If it's been long enough, trigger a leader election."""
        if self.election_deadline < time.time():
            if self.state == 'follower' or self.state == 'candidate':
                # Let's go!
                self.become_candidate()
            else:
                # We're a leader, or initializing; sleep again
                self.reset_election_deadline()
            return True
```

How do we get into the `follower` state to start? Once we've completed the
initialization process.

```py
    def become_follower(self):
        """Become a follower"""
        self.state = 'follower'
        self.reset_election_deadline()
        log('Became follower')

    def setup_handlers(self):
				...

        # Handle initialization message
        def raft_init(msg):
            if self.state != 'nascent':
                raise RuntimeError("Can't init twice!")

            body = msg['body']
            self.set_node_id(body['node_id'])
            self.node_ids = body['node_ids']

            self.become_follower()

            log('I am:', self.node_id)
            self.net.reply(msg, {'type': 'raft_init_ok'})
```

Let's give that a shot:

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ cat store/latest/n1.log 
2018-06-07 15:45:26.951853 Online.
2018-06-07 15:45:26.952197 Received
{u'body': {u'msg_id': 1,
           u'node_id': u'n1',
           u'node_ids': [u'n1', u'n2', u'n3'],
           u'type': u'raft_init'},
 u'dest': u'n1',
 u'src': u'c1'}
2018-06-07 15:45:26.952239 Became follower
2018-06-07 15:45:26.952260 I am: n1
2018-06-07 15:45:26.952404 Sent
{'body': {'in_reply_to': 1, 'type': 'raft_init_ok'},
 'dest': u'c1',
 'src': u'n1'}
2018-06-07 15:45:29.358356 Became candidate
2018-06-07 15:45:33.348463 Became candidate
2018-06-07 15:45:36.194498 Became candidate
```

Much better. Now we only establish our candidacy every 2+ seconds. Next up,
we'll add Raft terms and logs to our node.

```Terms

Raft's election system is coupled to the *term*: a monotonicall increasing integer, incremented on each election.

```py
class RaftNode():
    def __init__(self):
				...

        self.current_term = 0   # Our current Raft term
```

Terms are monotonic; they should only rise. To enforce this, we'll have a function for advancing the term:

```py
    def advance_term(self, term):
        """Advance our term to `term`, resetting who we voted for."""
        if not self.current_term < term:
            raise RuntimeError("Can't go backwards")

        self.current_term = term
```

We'll add the term to our state transition logging, just for clarity:

```py
    def become_follower(self):
        """Become a follower"""
        self.state = 'follower'
        self.reset_election_deadline()
        log('Became follower for term', self.current_term)
```

And when we become a candidate, we'll increment the term:

```py
    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        self.advance_term(self.current_term + 1) 	# <--
        self.reset_election_deadline()
        log('Became candidate for term', self.current_term)
```

If we try this out, we can see each node becomes a candidate only once per term:

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ cat store/latest/n1.log
...
2018-06-07 16:00:03.354165 Became follower for term 0
2018-06-07 16:00:03.354187 I am: n1
2018-06-07 16:00:03.354323 Sent
{'body': {'in_reply_to': 1, 'type': 'raft_init_ok'},
 'dest': u'c1',
 'src': u'n1'}
2018-06-07 16:00:06.515313 Became candidate for term 1
2018-06-07 16:00:08.817430 Became candidate for term 2
2018-06-07 16:00:12.447980 Became candidate for term 3
```

With terms ready, we'll create a simple implementation of the Raft log.

## Logs

Logs in Raft are 1-indexed arrays, which we'll represent as a Python list
internally. Since Python lists are 0-indexed, we'll remap indices in a Log
class, and provide some common log operations. We know from the Raft paper that
we'll need to append entries to the log, get entries at a particular index,
investigate the most recent entry, and inspect the overall size.

```py
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
				log("Log:\n" + pformat(self.entries))

    def last(self):
        """Returns the most recent entry"""
        return self.entries[-1]

    def size(self):
        "How many entries are in the log?"
        return len(self.entries)
```

We're doing something a bit odd here--every log we constructs comes with a
hardcoded single entry by default. This simplifies error handling by getting
rid of the base case for some of Raft's inductive algorithms. We can always
assume there's a previous element, when it comes time to compare entries, and
the last entry of the log is always well-defined.

We'll add an instance of the log to RaftNode:

```py
class RaftNode():
    def __init__(self):
        # Heartbeats & timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.election_deadline = 0      # Next election, in epoch seconds

        # Node & cluster IDS
        self.node_id = None     # Our node ID
        self.node_ids = None    # The set of node IDs

        # Raft state
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
        self.current_term = 0   # Our current Raft term

        # Components
        self.net = Net()
        self.log = Log() # <==
        self.state_machine = KVStore()
        self.setup_handlers()
```

And that's it for now! We'll actually add entries to the log later on, but an
empty (or in this case, trivial) log is enough to get leader election going.

## Requesting Votes

When we become a candidate, we need to broadcast a request for votes to all other nodes in the cluster. To get the other nodes in the cluster...

```py
class RaftNode():
		...

    def other_nodes(self):
        """All nodes except this one."""
        nodes = list(self.node_ids)
        nodes.remove(self.node_id)
        return nodes
```

We know how to send messages to other nodes, but we don't yet know how to
listen for *responses*. We built part of this machinery earlier though, in
`Net`: there's a `callbacks` dict that maps message IDs to code to execute on
responses. But before we can use *that*, we need to generate unique message IDs
in the first place.

```py
class Net():
		...

    def __init__(self):
        """Constructs a new network client."""
        self.node_id = None     # Our local node ID
        self.next_msg_id = 0    # The next message ID we're going to allocate
        self.handlers = {}      # A map of message types to handler functions
        self.callbacks = {}     # A map of message IDs to response handlers

		...

    def new_msg_id(self):
        """Generate a fresh message ID"""
        id = self.next_msg_id
        self.next_msg_id += 1
        return id
```

Next, we want to send a message, and register a function to call when a reply
arrives.

```py
class Net():
		...

    def rpc(self, dest, body, handler):
        """Sends an RPC request to dest and handles the response with handler."""
        msg_id = self.new_msg_id()
        self.callbacks[msg_id] = handler
        body['msg_id'] = msg_id
        self.send(dest, body)
```

We're not going to clean up these callbacks if we don't get a response. This is
absolutely a memory leak, but remember, we're building a *toy* consensus system
here, and it only has to run for a few minutes.

Now we can glue together `other_nodes` and `rpc` into a *broadcast RPC*
mechanism:

```py
class RaftNode():
		...

    def brpc(self, body, handler):
        """Broadcast an RPC message to all other nodes, and call handler with each response."""
        for node in self.other_nodes():
            self.net.rpc(node, body, handler)
```

One final piece--whenever we receive a message with a higher term than us, we
need to step down. We'll need to do that if a node responds to our vote request!

```py
    def maybe_step_down(self, remote_term):
        """If remote_term is bigger than ours, advance our term and become a follower."""
        if self.current_term < remote_term:
            log("Stepping down: remote term", remote_term, "higher than our term", self.current_term)
            self.advance_term(remote_term)
            self.become_follower()
```

Now let's sketch out the vote-requesting system:

```py
    def request_votes(self):
        """Request that other nodes vote for us as a leader"""

        # We vote for ourself
        votes = set([self.node_id])
        term = self.current_term

        def handler(res):
            body = res['body']
            self.maybe_step_down(body['term'])

            if self.state == 'candidate' and \
                    self.current_term == term and \
                    body['term'] == self.current_term and \
                    body['vote_granted']:

                # We have a vote for our candidacy
                votes.add(res['src'])
                log('Have votes:', pformat(votes))

        # Broadcast vote request
        self.brpc({
            'type':             'request_vote',
            'term':             self.current_term,
            'candidate_id':     self.node_id,
            'last_log_index':   self.log.size(),
            'last_log_term':    self.log.last()['term']
            },
            handler)
```

This is straight from the paper, but it's incomplete: we're not doing anything
with the votes yet, just tallying them up. We'll come back to this in a second.

When we become a candidate, we should request votes:

```py
    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        self.advance_term(self.current_term + 1)
        self.reset_election_deadline()
        log('Became candidate for term', self.current_term)
        self.request_votes()
```

Let's give that a shot!

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ cat store/latest/n1.log
...
2018-06-07 16:36:45.058869 Became candidate for term 4
2018-06-07 16:36:45.059251 Sent
{'body': {'candidate_id': u'n1',
          'last_log_index': 1,
          'last_log_term': 0,
          'msg_id': 6,
          'term': 4,
          'type': 'request_vote'},
 'dest': u'n2',
 'src': u'n1'}
2018-06-07 16:36:45.059542 Sent
{'body': {'candidate_id': u'n1',
          'last_log_index': 1,
          'last_log_term': 0,
          'msg_id': 7,
          'term': 4,
          'type': 'request_vote'},
 'dest': u'n3',
 'src': u'n1'}
```

Sure enough, we can see our term increasing, and for each term, two RequestVotes messages--one for each of the other two nodes--go out. They have unique message IDs, which allows us to collect responses. However, there's a problem:

```
RuntimeError: No callback or handler for
{u'body': {u'candidate_id': u'n2',
           u'last_log_index': 1,
           u'last_log_term': 0,
           u'msg_id': 4,
           u'term': 3,
           u'type': u'request_vote'},
 u'dest': u'n1',
 u'src': u'n2'}
```

We haven't implemented anything that *handles* a RequestVote message yet! Let's
figure out how to respond.

## Granting Votes

Nodes need to keep track of who they vote for in the current term. Let's add a
votedFor field:

```py
class RaftNode():
    def __init__(self):
        # Heartbeats & timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.election_deadline = 0      # Next election, in epoch seconds

        # Node & cluster IDS
        self.node_id = None     # Our node ID
        self.node_ids = None    # The set of node IDs

        # Raft state
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
        self.current_term = 0   # Our current Raft term
        self.voted_for = None   # What node did we vote for in this term?

        # Components
        self.net = Net()
        self.log = Log()
        self.state_machine = KVStore()
        self.setup_handlers()
```

When the term advances, we're allowed to vote for someone new:

```py
    def advance_term(self, term):
        """Advance our term to `term`, resetting who we voted for."""
        if not self.current_term < term:
            raise RuntimeError("Can't go backwards")

        self.current_term = term
        self.voted_for = None
```

Candidates vote for themselves:

```py
    def become_candidate(self):
        """Become a candidate, advance our term, and request votes."""
        self.state = 'candidate'
        self.advance_term(self.current_term + 1)
        self.voted_for = self.node_id
        self.leader = None
        log("Became candidate for term ", self.current_term)
        self.reset_election_deadline()
        self.request_votes()
```

Now, we'll introduce a handler for RequestVote messages:

```py
    def setup_handlers(self):
			...

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
            elif body['last_log_term'] < self.log.last()['term']:
                log("have log entries from term", self.log.last()['term'], \
                        "which is newer than remote term", \
                        body['last_log_term'], "not granting vote")
            elif body['last_log_term'] == self.log.last()['term'] and \
                    body['last_log_index'] < self.log.size():
                log("Our logs are both at term", self.log.last()['term'], \
                        "but our log is", self.log.size(), \
                        "and theirs is only", body['last_log_index'])
            else:
                log("Granting vote to", msg['src'])
                grant = True
                self.voted_for = body['candidate_id']
                self.reset_election_deadline()

            self.net.reply(msg, {
                'type': 'request_vote_res',
                'term': self.current_term,
                'vote_granted': grant
                })
        self.net.on('request_vote', request_vote)
```

These constraints are taken directly from the paper. We want to make sure we
only grant votes for higher or equal terms, that we don't vote twice in the
same term, and that the candidate's log is at least as big as ours--that's
detailed in section 5.4 of the paper.

If we run this, we can observe nodes granting votes to one another:

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ grep 'votes' store/latest/n1.log 
2018-06-07 17:12:15.862958 Have votes: set([u'n1', u'n3'])
```

## Becoming a Leader

Once we have a majority of votes, we can declare ourselves a leader for that term.

```py
import math
...

def majority(n):
    """What number would constitute a majority of n nodes?"""
    return int(math.floor((n / 2.0) + 1))
```

When we get a majority of votes, we'll convert to leader:

```py
    def request_votes(self):
        """Request that other nodes vote for us as a leader"""

        # We vote for ourself
        votes = set([self.node_id])
        term = self.current_term

        def handler(res):
            body = res['body']
            self.maybe_step_down(body['term'])

            if self.state == 'candidate' and \
                    self.current_term == term and \
                    body['term'] == self.current_term and \
                    body['vote_granted']:

                # We have a vote for our candidacy
                votes.add(res['src'])
                log('Have votes:', pformat(votes))

                if majority(len(self.node_ids)) <= len(votes):
                    # We have a majority of votes for this term
                    self.become_leader()

				...
```

And we'll need a `become_leader` transition:

```py
class RaftNode():
		...

    def become_leader(self):
        """Become a leader"""
        if not self.state == 'candidate':
            raise RuntimeError('Should be a candidate')

        self.state = 'leader'
        log('Became leader for term', self.current_term)
```

Let's give that a shot, and see who becomes a leader!

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ grep -i 'leader' store/latest/n*.log
store/latest/n1.log:2018-06-07 17:32:36.443166 Became leader for term 2
store/latest/n2.log:2018-06-07 17:32:41.170605 Became leader for term 4
store/latest/n3.log:2018-06-07 17:32:33.817196 Became leader for term 1
store/latest/n3.log:2018-06-07 17:32:39.081435 Became leader for term 3
```

Note that not only do nodes become leaders, but every term has *at most one*
leader! That's an important invariant in Raft!

## Stepping Down

We have timeouts that *trigger* elections, but if a network partition occurs,
or if a leader is isolated for too long, that leader should also *step down*.
To support this, we'll add a new deadline to RaftNode: `step_down_deadline`.

```py
class RaftNode():
    def __init__(self):
        # Heartbeats & timeouts
        self.election_timeout = 2       # Time before election, in seconds
        self.election_deadline = 0      # Next election, in epoch seconds
        self.step_down_deadline = 0     # When to step down automatically

				...
```

Just like we can push out our election deadline, we'll be able to defer stepdown:

```py
    def reset_step_down_deadline(self):
        """We got communication, don't step down for a while."""
        self.step_down_deadline = time.time() + self.election_timeout
```

When nodes become a candidate or a leader, they shouldn't step down
immediately:

```py
    def become_candidate(self):
        "Become a candidate"
        self.state = 'candidate'
        self.advance_term(self.current_term + 1)
        self.reset_election_deadline()
        self.reset_step_down_deadline()
        log('Became candidate for term', self.current_term)
        self.request_votes()

    def become_leader(self):
        """Become a leader"""
        if not self.state == 'candidate':
            raise RuntimeError('Should be a candidate')

        self.state = 'leader'
        self.reset_step_down_deadline()
        log('Became leader for term', self.current_term)
```

And we'll avoid stepping down shortly after an election:

```py
    def request_votes(self):
        """Request that other nodes vote for us as a leader"""

        # We vote for ourself
        votes = set([self.node_id])
        term = self.current_term

        def handle(response):
            self.reset_step_down_deadline()
						...
```

Now, we'll have leaders step down once their deadline is up:

```py
    # Actions for leaders

    def step_down_on_timeout(self):
        """If we haven't received any acks for a while, step down."""
        if self.state == 'leader' and self.step_down_deadline < time.time():
            log("Stepping down: haven't received any acks recently")
            self.become_follower()
            return True
```

And fold that into our mainloop:

```py
    def main(self):
        """Mainloop."""
        log('Online.')

        while True:
            try:
                self.net.process_msg() or \
                    self.step_down_on_timeout() or \
                    self.election() or \
                    time.sleep(0.001)

						...
```

Now we can observe leaders politely stepping down a few seconds after their
election:

```
$ lein run test --bin raft.py --nodes n1,n2,n3 --rate 0 --time-limit 10
...
$ cat store/latest/n1.log
...
2018-06-07 17:58:07.932546 Became leader for term 2
2018-06-07 17:58:09.933523 Stepping down: haven't received any acks recently
...
```

There we have it! A functioning leader election system. Next, we'll push
operations into our log, and replicate logs between nodes.
