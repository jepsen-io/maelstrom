# Leader Election

With the basic key-value operations working, it's time to implement the Raft
protocol. We'll be basing our implementation off of the [Raft
Paper](https://raft.github.io/raft.pdf), so if you haven't read it carefully,
now's a great time.

## Becoming a Candidate

Raft nodes have *states* like leader, candidate, and follower. We'll add a
variable to track this state.

```rb
class Raft
  attr_reader :node
  def initialize
    ...
    @state = :follower
```

The first step in leader election is becoming a *candidate*. Let's add a method
to `Raft` which makes us one.

```rb
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      @node.log "Became candidate"
    end
  end
```

We want our nodes to periodically become candidates, so let's schedule a task
to do that:

```rb
class Raft
  def initialize
    ...
    @node.every 2 do
      become_candidate!
    end
```

If we run this, we can see that our nodes periodically become candidates:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 10
...
$ grep candidate store/latest/node-logs/n0.lo
Became candidate
Became candidate
Became candidate
Became candidate
Became candidate
```

But we don't actually want to become candidates *continuously*. We only want to
do this when we haven't heard from a leader in a while. Let's set an election
deadline variable.

```rb
  def initialize
    ...
    @election_timeout = 2         # Time before next election, in seconds
    @election_deadline = Time.now # Next election, in epoch seconds
```

Our election task should check the deadline, and only fire if it's past. In
addition, we only want to begin an election if we're *not* already a leader:

```rb
    @node.every 1 do
      @lock.synchronize do
        if @election_deadline < Time.now
          if @state != :leader
            become_candidate!
          else
            reset_election_deadline!
          end
        end
      end
    end
```

We'll add a method that extends the election deadline, so we can keep pushing
it back when things are fine:

```rb
  # Don't start an election for a while
  def reset_election_deadline!
    @lock.synchronize do
      @election_deadline = Time.now + (@election_timeout * (rand + 1))
    end
  end
```

And when we become a candidate, we'll reset that deadline again. That way we
don't constantly trigger new elections *during* the current one.

```rb
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      reset_election_deadline!
      @node.log "Became candidate"
    end
  end
```

Finally, we'll want a way to become a follower again.

```rb
  def become_follower!
    @lock.synchronize do
      @state = :follower
      reset_election_deadline!
      @node.log "Became follower"
    end
  end
```

Let's give that a shot:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n
...
$ grep candidate store/latest/node-logs/n0.log
Became candidate
Became candidate
Became candidate
```

Very good! Now we only establish our candidacy periodically. Next up, we'll add
Raft terms and logs to our node.

## Terms

Raft's election system is coupled to the *term*: a monotonically increasing
integer, incremented on each election.

```rb
class Raft
  attr_reader :node
  def initialize
    ...
    @term = 0
```

Terms are monotonic; they should only rise. To enforce this, we'll have a
method for advancing the term:

```rb
  # Advance our term to `term`.
  def advance_term!(term)
    @lock.synchronize do
      unless @term < term
        raise "Term can't go backwards!"
      end
    end
    @term = term
  end
```

When we become a candidate, we need to advance our term.

```rb
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      advance_term!(@term + 1) # New!
      reset_election_deadline!
      @node.log "Became candidate for term #{@term}"
    end
  end
```

Let's log that term when we become a follower too.

```rb
  def become_follower!
    @lock.synchronize do
      @state = :follower
      reset_election_deadline!
      @node.log "Became follower for term #{@term}" # Changed!
    end
  end
```

If we try this out, we can see each node becomes a candidate only once per term:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n
...
$ cat store/latest/node-logs/n0.log | grep term
Became candidate for term 1
Became candidate for term 2
Became candidate for term 3
```

With terms ready, we'll create a simple implementation of the Raft log. We need
a log first because voting requires looking at the log!

## Logs

Logs in Raft are 1-indexed arrays, which we'll represent as a Ruby array
internally. Since Ruby arrays are 0-indexed, we'll remap indices in a Log
class, and provide some common log operations. We know from the Raft paper that
we'll need to append entries to the log, get entries at a particular index,
investigate the most recent entry, and inspect the overall size.

```rb
# Stores Raft entries, which are maps with a {:term} field. Not thread-safe; we
# handle locking in the Raft class.
class Log
  # When we construct a fresh log, we add a default entry. This eliminates some
  # special cases for empty logs.
  def initialize(node)
    @node = node
    @entries = [{term: 0, op: nil}]
  end

  # Returns a log entry by index. Note that Raft's log is 1-indexed!
  def [](i)
    @entries[i - 1]
  end

  # Appends multiple entries to the log.
  def append!(entries)
    entries.each do |e|
      # Coerce strings keys to keywords
      e = e.transform_keys(&:to_sym)
      e[:op] = e[:op].transform_keys(&:to_sym)
      e[:op][:body] = e[:op][:body].transform_keys(&:to_sym)
      @entries << e
    end
    @node.log "Log: #{@entries.inspect}"
  end

  # The most recent entry
  def last
    @entries[-1]
  end

  # How many entries in the log?
  def size
    @entries.size
  end
end
```

We're doing something a bit odd here--every log we constructs comes with a
hardcoded single entry by default. This simplifies error handling by getting
rid of the base case for some of Raft's inductive algorithms. We can always
assume there's a previous element, when it comes time to compare entries, and
the last entry of the log is always well-defined.

We'll add an instance of the log to the `Raft` class:

```rb
class Raft
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Monitor.new
    @log = Log.new @node
```

And that's it for now! We'll actually add entries to the log later on, but an
empty (or in this case, trivial) log is enough to get leader election going.

## Requesting Votes

When we become a candidate, we need to broadcast a request for votes to all
other nodes in the cluster, and accumulate responses as they arrive. Let's
modify our `Node` class to include a broadcast rpc method, which does just
that:

```rb
class Node
  ...
  def other_node_ids
    @node_ids.reject do |id|
      id == @node_id
    end
  end

  # Sends a broadcast RPC request. Invokes block with a response message for
  # each response that arrives.
  def brpc!(body, &handler)
    other_node_ids.each do |node|
      rpc! node, body, &handler
    end
  end
```

Back to our Raft class: whenever we receive a message with a higher term than
us, we need to step down. We'll need to do that if a node responds to our vote
request!

```rb
class Node
  ...
  # If remote_term is bigger than ours, advance our term and become a follower
  def maybe_step_down!(remote_term)
    @lock.synchronize do
      if @term < remote_term
        @node.log "Stepping down: remote term #{remote_term} higher than our ter
m #{@term}"
        advance_term! remote_term
        become_follower!
      end
    end
  end
```

Now, following the paper, let's sketch out the vote-requesting system. We'll need to pull in Ruby's `Set` class:

```rb
require 'set'
```

And in `Node`, we'll add a method for requesting votes from other nodes.

```rb
  # Request that other nodes vote for us as a leader.
  def request_votes!
    @lock.synchronize do
      # We vote for ourselves
      votes = Set.new [@node.node_id]
      term = @term

      @node.brpc!(
        type: 'request_vote',
        term: term,
        candidate_id: @node.node_id,
        last_log_index: @log.size,
        last_log_term: @log.last[:term]
      ) do |res|
        @lock.synchronize do
          body = res[:body]
          maybe_step_down! body[:term]
          if @state == :candidate and
              @term == term and
              @term == body[:term] and
              body[:vote_granted]
            # We have a vote for our candidacy, and we're still in the term we
            # requested! Record the vote
            votes << res[:src]
            @node.log "Have votes: #{votes}"
          end
        end
      end
    end
  end
```

This is straight from the paper, but it's incomplete: we're not doing anything
with the votes yet, just tallying them up. We'll come back to this in a second.

When we become a candidate, we should request votes:

```rb
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      advance_term!(@term + 1)
      reset_election_deadline!
      @node.log "Became candidate for term #{@term}"
      request_votes! # New!
    end
  end
```

Let's give that a try!

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n
...
Node n2 initialized
Became candidate for term 1
Sent {:dest=>"n0", :src=>"n2", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n2", :last_log_index=>1, :last_log_term=>0, :msg_id=>1}}
Sent {:dest=>"n1", :src=>"n2", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n2", :last_log_index=>1, :last_log_term=>0, :msg_id=>2}}
Received {:dest=>"n2", :src=>"n0", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n0", :last_log_index=>1, :last_log_term=>0, :msg_id=>2}, :id=>10}
/home/aphyr/maelstrom/node.rb:173:in `block in main!': No handler for {:dest=>"n2", :src=>"n0", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n0", :last_log_index=>1, :last_log_term=>0, :msg_id=>2}, :id=>10} (RuntimeError)
	from /home/aphyr/maelstrom/node.rb:168:in `synchronize'
	from /home/aphyr/maelstrom/node.rb:168:in `main!'
	from /home/aphyr/maelstrom/raft.rb:191:in `<main>'
```

The node crashes--it doesn't know how to handle a `request_vote` message
yet--but that's OK. The important part is that it became a candidate for term
1, and sent well-formed `request_vote` messages to the other two nodes in the
cluster. Let's figure out how to respond to the vote request next.

## Granting Votes

Nodes need to keep track of who they vote for in the current term. Let's add a
`voted_for` instance variable.

```rb
class Raft
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Monitor.new
    @log = Log.new @node
    @state_machine = Map.new

    @state = 'follower'   # Either follower, candidate, or leader
    @term = 0             # What's our current term?
    @voted_for = nil      # Which node did we vote for in this term?
```

When the term advances, we're allowed to vote for someone new.

```rb
  # Advance our term to `term`, resetting who we voted for.
  def advance_term!(term)
    @lock.synchronize do
      unless @term < term
        raise "Term can't go backwards!"
      end
    end
    @term = term
    @voted_for = nil  # New!
  end
```

When we become a candidate, we record our vote for ourselves.

```rb
  # Become a candidate, advance our term, and request votes.
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      advance_term!(@term + 1)
      @voted_for = @node.node_id    # New!
      reset_election_deadline!
      request_votes!
      @node.log "Became candidate for term #{@term}"
    end
  end
```

Now we're ready to respond to vote requests!

```rb
    @node.on 'request_vote' do |msg|
      body = msg[:body]
      @lock.synchronize do
        maybe_step_down! body[:term]
        grant = false

        if body[:term] < @term
          @node.log "Candidate term #{body[:term]} lower than #{@term}, not gran
ting vote."
        else if @voted_for
          @node.log "Already voted for #{@voted_for}; not granting vote."
        else if body[:last_log_term] < @log.last[:term]
          @node.log "Have log entries from term #{@log.last[:term]}, which is ne
wer than remote term #{body[:last_log_term]}; not granting vote."
        else if body[:last_log_term] == @log.last[:term] and
          body[:last_log_index] < @log.size
          @node.log "Our logs are both at term #{@log.last[:term]}, but our log i
s #{@log.size} and theirs is only #{body[:last_log_index]} long; not granting vo
te."
        else
          @node.log "Granting vote to #{body[:candidate_id]}"
          grant = true
          @voted_for = body[:candidate_id]
          reset_election_deadline!
        end

        @node.reply! msg, {
          type: 'request_vote_res'
          term: @term
          vote_granted: grant
        }
      end
    end
```

These constraints are taken directly from the paper. We want to make sure we
only grant votes for higher or equal terms, that we don't vote twice in the
same term, and that the candidate's log is at least as big as ours--that's
detailed in section 5.4 of the paper.

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 0 --log-stderr
...
$ cat store/latest/node-logs/n2.log
Received {:dest=>"n2", :body=>{:type=>"init", :node_id=>"n2", :node_ids=>["n0", "n1", "n2"], :msg_id=>1}, :src=>"c1", :id=>0}
Sent {:dest=>"c1", :src=>"n2", :body=>{:type=>"init_ok", :in_reply_to=>1}}
Node n2 initialized
Sent {:dest=>"n0", :src=>"n2", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n2", :last_log_index=>1, :last_log_term=>0, :msg_id=>1}}
Sent {:dest=>"n1", :src=>"n2", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n2", :last_log_index=>1, :last_log_term=>0, :msg_id=>2}}
Became candidate for term 1
Received {:dest=>"n2", :src=>"n1", :body=>{:type=>"request_vote", :term=>1, :candidate_id=>"n1", :last_log_index=>1, :last_log_term=>0, :msg_id=>2}, :id=>9}
Already voted for n2; not granting vote.
```

Ah, so this is a bit of a problem. Every node wakes up exactly every 1 seconds
to run their election process, which means they all tend to run elections
concurrently. Everyone votes for themselves and nobody wins. Let's replace that
with a shorter interval, but also add a random `sleep` in the election process.

```rb
    @node.every 0.1 do
      sleep(rand / 10)  # New!
      @lock.synchronize do
        if @election_deadline < Time.now
          if @state == :leader
            reset_election_deadline!
          else
            become_candidate!
          end
        end
      end
    end
```

If we run this, we can observe nodes granting votes to one another:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 0 --log-stderr
...
INFO [2021-02-27 01:23:26,549] n1 stderr - maelstrom.process Became candidate for term 1
INFO [2021-02-27 01:23:26,550] n1 stderr - maelstrom.process Received {:dest=>"n1", :src=>"n0", :body=>{:type=>"request_vote_res", :term=>1, :vote_granted=>true, :in_reply_to=>1}, :id=>8}
INFO [2021-02-27 01:23:26,551] n1 stderr - maelstrom.process Received {:dest=>"n1", :src=>"n2", :body=>{:type=>"request_vote_res", :term=>1, :vote_granted=>true, :in_reply_to=>2}, :id=>9}
INFO [2021-02-27 01:23:26,551] n1 stderr - maelstrom.process Have votes: #<Set: {"n1", "n0"}>
INFO [2021-02-27 01:23:26,552] n1 stderr - maelstrom.process Have votes: #<Set: {"n1", "n0", "n2"}>
```

Success! Now we need to use these votes to become a leader.

## Becoming a Leader

Once we have a majority of votes, we can declare ourselves a leader for that
term.

```rb
  # What number would constitute a majority of n nodes?
  def majority(n)
    (n / 2.0).floor + 1
  end
```

When we get a majority of votes, we'll convert to leader:

```rb
  # Request that other nodes vote for us as a leader.
  def request_votes!
    @lock.synchronize do
      # We vote for ourselves
      votes = Set.new [@node.node_id]
      term = @term

      @node.brpc!(
        type: 'request_vote',
        term: term,
        candidate_id: @node.node_id,
        last_log_index: @log.size,
        last_log_term: @log.last[:term]
      ) do |res|
        @lock.synchronize do
          body = res[:body]
          maybe_step_down! body[:term]
          if @state == :candidate and
              @term == term and
              @term == body[:term] and
              body[:vote_granted]
            # We have a vote for our candidacy, and we're still in the term we
            # requested! Record the vote
            votes << res[:src]
            @node.log "Have votes: #{votes}"

            # New!
            if majority(@node.node_ids.size) <= votes.size
              # We have a majority of votes for this term!
              become_leader!
            end
```

And we'll need a `become_leader` transition:

```rb
  # Become a leader
  def become_leader!
    @lock.synchronize do
      unless @state == :candidate
        raise "Should be a candidate!"
      end

      @state = :leader
      @node.log "Became leader for term #{@term}"
    end
  end
```

Let's give that a shot, and see who becomes a leader!

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 0 --log-stderr
...
$ grep Became store/latest/node-logs/*
store/latest/node-logs/n0.log:Became follower for term 1
store/latest/node-logs/n0.log:Became follower for term 2
store/latest/node-logs/n0.log:Became candidate for term 3
store/latest/node-logs/n0.log:Became leader for term 3
store/latest/node-logs/n1.log:Became candidate for term 1
store/latest/node-logs/n1.log:Became leader for term 1
store/latest/node-logs/n1.log:Became follower for term 2
store/latest/node-logs/n1.log:Became follower for term 3
store/latest/node-logs/n2.log:Became follower for term 1
store/latest/node-logs/n2.log:Became candidate for term 2
store/latest/node-logs/n2.log:Became leader for term 2
store/latest/node-logs/n2.log:Became follower for term 3
```

Note that not only do nodes become leaders, but every term has *at most one*
leader! That's an important invariant in Raft!

## Stepping Down

We have timeouts that *trigger* elections, but if a network partition occurs,
or if a leader is isolated for too long, that leader should also *step down*.
To support this, we'll add a new deadline to RaftNode: `step_down_deadline`.

```rb
class Raft
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Monitor.new
    @log = Log.new @node
    @state_machine = Map.new

    @state = 'follower'   # Either follower, candidate, or leader
    @term = 0             # What's our current term?
    @voted_for = nil      # Which node did we vote for in this term?

    @election_timeout = 2           # Time before next election, in seconds
    @election_deadline = Time.now   # Next election, in epoch seconds
    @step_down_deadline = Time.now  # When to step down automatically.
    ...
```

Just like we can push out our election deadline, we'll be able to defer
stepdown:

```rb
  # We got communication; don't step down for a while
  def reset_step_down_deadline!
    @lock.synchronize do
      @step_down_deadline = Time.now + @election_timeout
    end
  end
```

When nodes become a candidate or a leader, they shouldn't step down
immediately:

```rb
  # Become a candidate, advance our term, and request votes.
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      advance_term!(@term + 1)
      @voted_for = @node.node_id
      reset_election_deadline!
      reset_step_down_deadline! # New!
      request_votes!
      @node.log "Became candidate for term #{@term}"
    end
  end

  # Become a leader
  def become_leader!
    @lock.synchronize do
      unless @state == :candidate
        raise "Should be a candidate!"
      end

      @state = :leader
      reset_step_down_deadline! # New!
      @node.log "Became leader for term #{@term}"
    end
  end
```

And we'll avoid stepping down shortly after an election:

```rb
  # Request that other nodes vote for us as a leader.
  def request_votes!
    @lock.synchronize do
      # We vote for ourselves
      votes = Set.new [@node.node_id]
      term = @term

      @node.brpc!(
        type: 'request_vote',
        term: term,
        candidate_id: @node.node_id,
        last_log_index: @log.size,
        last_log_term: @log.last[:term]
      ) do |res|
        reset_step_down_deadline! # New!
        ...
```

Now, we'll have a periodic task to step down leaders once their deadline is up.

```rb
    # Leader stepdown thread
    @node.every 0.1 do
      @lock.synchronize do
        if @state == :leader and @step_down_deadline < Time.now
          @node.log "Stepping down: haven't received any acks recently"
          become_follower!
        end
      end
    end
```

Now we can observe leaders politely stepping down a few seconds after their
election:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 0 --log-stderr
...
INFO [2021-02-27 01:44:29,758] n0 stderr - maelstrom.process Became leader for term 2
INFO [2021-02-27 01:44:31,758] n0 stderr - maelstrom.process Stepping down: haven't received any acks recently
INFO [2021-02-27 01:44:31,759] n0 stderr - maelstrom.process Became follower for term 2
```

There we have it! A functioning leader election system. Next, we'll push
operations into our log, and replicate logs between nodes.
