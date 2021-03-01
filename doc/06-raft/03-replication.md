# Replicating Logs

First, we'll need nontrivial logs to replicate! Right now, all our logs have a single, hardcoded entry. Let's start adding state transitions to the log!

For the time being, we'll keep processing all key-value operations locally, but
as a side effect, we'll also append those operations to the leader's log.
Later, when we've got a working commit index, we'll advance the state machine
based on log entries. In our `client_req!` method, we'll have leaders journal
the entire request message to the log. Non-leaders will throw an error.

```rb
  def client_req!(msg)
    @lock.synchronize do
      if @state != :leader
        raise RPCError.temporarily_unavailable "not a leader"
      end

      @log.append! [{term: @term, op: msg}]
      @state_machine, res = @state_machine.apply(msg[:body])
      @node.reply! msg, res
    end
  end
```

If we run some requests through this, we can observe non-leaders rejecting requests, and logs on leaders filling up.

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 5
...
$ cat store/latest/node-logs/n0.log
...
Log:
[{:term=>0, :op=>nil}, {:term=>2, :op=>{:key=>0, :type=>"read", :msg_id=>5, :client=>"c8"}}, {:term=>2, :op=>{:key=>0, :from=>3, :to=>3, :type=>"cas", :msg_id=>4, :client=>"c7"}}, {:term=>2, :op=>{:key=>0, :from=>0, :to=>1, :type=>"cas", :msg_id=>5, :client=>"c7"}}, {:term=>2, :op=>{:key=>0, :type=>"read", :msg_id=>6, :client=>"c8"}}, {:term=>4, :op=>{:key=>0, :type=>"read", :msg_id=>11, :client=>"c8"}}]
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

We'll also add the `commit_index`, because we want to inform other nodes how far
we've committed, and advance ours to match the leader.

```rb
class Raft
  attr_reader :node
  def initialize
    # Heartbeats and timeouts
    @election_timeout = 2            # Time before next election, in seconds
    @heartbeat_interval = 1          # Time between heartbeats, in seconds
    @min_replication_interval = 0.05 # Don't replicate TOO frequently

    @election_deadline  = Time.now   # Next election, in epoch seconds
    @step_down_deadline = Time.now   # When to step down automatically.
    @last_replication   = Time.now   # When did we last replicate?

    # Components
    @node = Node.new
    @lock = Monitor.new
    @log = Log.new @node
    @state_machine = Map.new

    # Raft state
    @state = 'follower'   # Either follower, candidate, or leader
    @term = 0             # What's our current term?
    @voted_for = nil      # Which node did we vote for in this term?

    # Leader state
    @commit_index = 0     # The highest committed entry in the log
    @next_index = nil     # A map of nodes to the next index to replicate
    @match_index = nil    # A map of (other) nodes to the highest log entry
                          # known to be replicated on that node.
```

We'll define a method, `match_index`, to fill in our own value.

```rb
  # Returns the map of match indices, including an entry for ourselves, based
  # on our log size.
  def match_index
    @match_index.merge({@node.node_id => @log.size})
  end
```

When we become a follower, we'll clear our match_index and next_index:

```rb
  def become_follower!
    @lock.synchronize do
      @state = :follower
      @match_index = nil
      @next_index = nil
      reset_election_deadline!
      @node.log "Became follower for term #{@term}"
    end
  end
```

And when we become a leader, we'll initialize match_index and next_index, as
per the paper. We'll also set last_replication to 0, so that we'll
*immediately* broadcast heartbeat to other nodes.

```rb
  # Become a leader
  def become_leader!
    @lock.synchronize do
      unless @state == :candidate
        raise "Should be a candidate!"
      end

      @state = :leader
      @last_replication = Time.at 0

      # We'll start by trying to replicate our most recent entry
      @next_index = {}
      @match_index = {}
      @node.other_node_ids.each do |node|
        @next_index[node] = @log.size + 1
        @match_index[node] = 0
      end

      reset_step_down_deadline!
      @node.log "Became leader for term #{@term}"
    end
  end
```

Now, we need to periodically replicate leader log entries to followers. To do
that, we'll need to obtain slices of the log from one index to the end, so
let's add a small helper function to Log:

```rb
class Log
  ...
  # All entries from index i to the end
  def from_index(i)
    if i <= 0
      raise IndexError.new "Illegal index #{i}"
    end

    @entries.slice(i - 1 .. -1)
  end
```

With that ready, we can create a method for leaders to replicate. Hang
on; this one's a doozy.

```rb
class Raft
  ...

  # If we're the leader, replicate unacknowledged log entries to followers.
  # Also serves as a heartbeat.
  def replicate_log!(force)
    @lock.synchronize do
      # How long has it been since we last replicated?
      elapsed_time = Time.now - @last_replication
      # We'll set this to true if we replicated to anyone
      replicated = false
      # We need the current term to ensure we don't process responses in later
      # terms.
      term = @term
```

We only want to replicate if we're a leader, and avoid replicating too frequently:

```rb
      if @state == :leader and @min_replication_interval < elapsed_time
        # We're a leader, and enough time elapsed
```

Then, for every other node, we'll look at the next index we want to send that
node, and take all higher entries from our log.

```rb
        @node.other_node_ids.each do |node|
          # What entries should we send this node?
          ni = @next_index[node]
          entries = @log.from_index ni
```

Whenever there are new entries to replicate, or, for heartbeats, if we haven't
replicated in the heartbeat interval, we'll send this node an `appendEntries`
message.

```rb
          if 0 < entries.size or @heartbeat_interval < elapsed_time
            @node.log "Replicating #{ni}+ to #{node}"
            replicated = true
```

Now, we'll issue an RPC request to append these entries.

```rb
            @node.rpc!(node, {
              type:           'append_entries',
              term:           @term,
              leader_id:      @node.node_id,
              prev_log_index: ni - 1,
              prev_log_term:  @log[ni - 1][:term],
              entries:        entries,
              leader_commit:  @commit_index
            }) do |res|
```

On receiving a response, we may need to step down--but if we're still a leader
for the same term, we'll reset our step-down deadline, since we're still in
touch with some followers. If the node acknowledges our request, we'll advance
their next and matching index; otherwise, we'll back up and try again.

```rb
              body = res[:body]
              @lock.synchronize do
                maybe_step_down! body[:term]
                if @state == :leader and body[:term] == @term
                  reset_step_down_deadline!
                  if body[:success]
                    # Excellent, these entries are now replicated!
                    @next_index[node] = [@next_index[node],
                                         (ni + entries.size)].max
                    @match_index[node] = [@match_index[node],
                                          (ni + entries.size - 1)].max
                    @node.log "Next index: #{@next_index}"
                  else
                    # We didn't match; back up our next index for this node
                    @next_index[node] -= 1
                  end
                end
              end
            end
          end
        end
      end
```

To finish up, if we replicated anything, we'll record this replication time.

```rb
      if replicated
        # We did something!
        @last_replication = Time.now
      end
    end
  end
```

Finally, we'll add a periodic task for replication.

```rb
  def initialize
    ...

    # Replication thread
    @node.every @min_replication_interval do
      replicate_log! false
    end
```

Let's give that a shot! If we look in the logs, we should be able to see nodes
issuing `append_entries` messages with entries from their local log. They're
going to crash, because we haven't told nodes how to handle these messages yet,
but that's OK.

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 5
...
Received {:dest=>"n2", :src=>"n1", :body=>{:type=>"append_entries", :term=>1, :leader_id=>"n1", :prev_log_index=>1, :prev_log_term=>0, :entries=>[{"term"=>1, "op"=>{"key"=>0, "value"=>4, "type"=>"write", "msg_id"=>1, "client"=>"c6"}}], :leader_commit=>0, :msg_id=>4}, :id=>13}
/home/aphyr/maelstrom/node.rb:174:in `block in main!': No handler for {:dest=>"n2", :src=>"n1", :body=>{:type=>"append_entries", :term=>1, :leader_id=>"n1", :prev_log_index=>1, :prev_log_term=>0, :entries=>[{"term"=>1, "op"=>{"key"=>0, "value"=>4, "type"=>"write", "msg_id"=>1, "client"=>"c6"}}], :leader_commit=>0, :msg_id=>4}, :id=>13} (RuntimeError)
```

Next, we'll interpret those appendEntries messages, and figure out how to
respond.

## Receiving Logs

When we're given entries by a leader, we need to possibly step down, check to
make sure the leader is higher than us, then check to see if the leader's
previous log index and term match our log, before truncating and appending
entries to our local log.

Log truncation is important--we need to delete all mismatching log entries
after the given index. We'll add that functionality to our Log:

```rb
class Log
  ...
  # Truncate the log to this many entries
  def truncate!(len)
    @entries.slice! len...size
  end
end
```

With that, we can add a message handler to `Raft` which responds to incoming
`append_entries` requests. We begin by pulling out the message body, and
stepping down & adopting the new term if the leader that sent this request had
a higher term than us.

```rb
class Node
  def initialize
    ...
    @node.on 'append_entries' do |msg|
      body = msg[:body]
      @lock.synchronize do
        maybe_step_down! body[:term]

        res = {type: 'append_entries_res',
               term: @term,
               success: false}
```

This default response is unsuccessful; we'll fill in `success = True` if all
the preconditions are satisfied. First, we reject messages from older leaders:

```rb
        if body[:term] < @term
          # Leader is behind us
          @node.reply! msg, res
          break
        end
```

If we get a message from a valid leader, we'll defer our election deadline--we
don't want to compete with that leader. Then we'll check the previous log
entry (provided one exists):

```rb
        reset_election_deadline!

        # Check previous entry to see if it matches
        if body[:prev_log_index] <= 0
          raise RPCError.abort "Out of bounds previous log index #{body[:prev_log_index]}"
        end
        e = @log[body[:prev_log_index]]
```

If the previous entry doesn't exist, or if we disagree on its term, we'll
reject this request.

```rb
        if e.nil? or e[:term] != body[:prev_log_term]
          # We disagree on the previous term
          @node.reply! msg, res
          break
        end
```

If we made it this far, then we do agree on the previous log term! We'll
truncate our log to that point, and append the given entries. We'll also
advance our commit pointer to the leader's (or however high we can go). Note
that we're rewriting strings to keywords here: the `Node` deserializer only
keywordizes the message and body, not inner structures.

```rb
        # We agree on the previous log term; truncate and append
        @log.truncate! body[:prev_log_index]
        @log.append! body[:entries]

        # Advance commit pointer
        if @commit_index < body[:leader_commit]
          @commit_index = [@log.size, body[:leader_commit]].min
        end
```

Finally, we'll acknowledge the append to the leader!

```rb
        # Acknowledge
        res[:success] = true
        @node.reply! msg, res
      end
    end
```

If we try running this, we should be able to see successful `append_entries`
results in the log. Logs should gradually grow over time, and look ~mostly~
like one another.

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 3 --concurrency 2n --rate 5
...
$ grep 'append_entries_res' store/latest/node-logs/*
...
store/latest/node-logs/n2.log:Sent {:dest=>"n1", :src=>"n2", :body=>{:type=>"append_entries_res", :term=>3, :success=>true, :in_reply_to=>116}}
```

Moreover, with append_entries messages resetting the election deadline,
leadership is now *stable*: after the first election, the leader
stays online indefinitely.

```sh
$ grep 'Became leader' store/latest/node-logs/*
store/latest/node-logs/n0.log:Became leader for term 1
```

Node `n0` was the leader for this entire test. We can test timeouts and
elections in the face of network partitions by running the test for
longer, and adding `--nemesis partition`.

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 60 --node-count 3 --concurrency 2n --rate 1 --nemesis partition
...
$ grep -i 'became leader' store/latest/node-logs/n*.log
store/latest/node-logs/n1.log:Became leader for term 8
store/latest/node-logs/n2.log:Became leader for term 9
$ grep -i 'acks' store/latest/node-logs/n*.log
store/latest/node-logs/n1.log:Stepping down: haven't received any acks recently
```

We observe leaders changing at approximately the same frequency as network
partitions change.

Next up, we'll have leaders advance their commit index, and update the state
machine with committed log entries.
