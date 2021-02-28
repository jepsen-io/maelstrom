#!/usr/bin/env ruby

require 'set'
require_relative 'node.rb'

class Map
  attr_reader :map
  def initialize(map = {})
    @map = map
  end

  # Applies an operation (e.g. {type: "write", key: 1, value: 2}) to this Map,
  # returning a tuple of the resulting map and completed operation.
  def apply(op)
    k = op[:key]
    case op[:type]
    when 'read'
      if @map.key? k
        [self, {type: 'read_ok', value: @map[k]}]
      else
        [self, RPCError.key_does_not_exist('not found').to_json]
      end

    when 'write'
      [Map.new(@map.merge({k => op[:value]})), {type: 'write_ok'}]

    when 'cas'
      if @map.key? k
        if @map[k] == op[:from]
          [Map.new(@map.merge({k => op[:to]})),
           {type: 'cas_ok'}]
        else
          [self,
           RPCError.precondition_failed(
             "expected #{op[:from]}, but had #{@map[k]}"
          ).to_json]
        end
      else
        [self, RPCError.key_does_not_exist('not found').to_json]
      end
    end
  end
end

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
    # @node.log "Log: #{@entries.inspect}"
  end

  # All entries from index i to the end
  def from_index(i)
    if i <= 0
      raise IndexError.new "Illegal index #{i}"
    end

    @entries.slice(i - 1 .. -1)
  end

  # The most recent entry
  def last
    @entries[-1]
  end

  # Truncate the log to this many entries
  def truncate!(len)
    @entries.slice! len...size
  end

  # How many entries in the log?
  def size
    @entries.size
  end
end

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
    @leader = nil         # Who do we think the leader is?

    # Leader state
    @commit_index = 0     # The highest committed entry in the log
    @last_applied = 1     # The last entry we applied to the state machine
    @next_index = nil     # A map of nodes to the next index to replicate
    @match_index = nil    # A map of (other) nodes to the highest log entry
                          # known to be replicated on that node.

    @node.on 'read'  do |m| client_req!(m) end
    @node.on 'write' do |m| client_req!(m) end
    @node.on 'cas'   do |m| client_req!(m) end

    @node.on 'request_vote' do |msg|
      body = msg[:body]
      @lock.synchronize do
        maybe_step_down! body[:term]
        grant = false

        @node.log "Last log: #{@log.last.inspect}"

        if body[:term] < @term
          @node.log "Candidate term #{body[:term]} lower than #{@term}, not granting vote."
        elsif @voted_for
          @node.log "Already voted for #{@voted_for}; not granting vote."
        elsif body[:last_log_term] < @log.last[:term]
          @node.log "Have log entries from term #{@log.last[:term]}, which is newer than remote term #{body[:last_log_term]}; not granting vote."
        elsif body[:last_log_term] == @log.last[:term] and
          body[:last_log_index] < @log.size
          @node.log "Our logs are both at term #{@log.last[:term]}, but our log is #{@log.size} and theirs is only #{body[:last_log_index]} long; not granting vote."
        else
          @node.log "Granting vote to #{body[:candidate_id]}"
          grant = true
          @voted_for = body[:candidate_id]
          reset_election_deadline!
        end

        @node.reply! msg, {
          type: 'request_vote_res',
          term: @term,
          vote_granted: grant
        }
      end
    end

    @node.on 'append_entries' do |msg|
      body = msg[:body]
      @lock.synchronize do
        maybe_step_down! body[:term]

        res = {type: 'append_entries_res',
               term: @term,
               success: false}

        if body[:term] < @term
          # Leader is behind us
          @node.reply! msg, res
          break
        end

        # Leader is ahead of us; remember them and don't try to run our own
        # election for a bit.
        @leader = body[:leader_id]
        reset_election_deadline!

        # Check previous entry to see if it matches
        if body[:prev_log_index] <= 0
          raise RPCError.abort "Out of bounds previous log index #{body[:prev_log_index]}"
        end
        e = @log[body[:prev_log_index]]

        if e.nil? or e[:term] != body[:prev_log_term]
          # We disagree on the previous term
          @node.reply! msg, res
          break
        end

        # We agree on the previous log term; truncate and append
        @log.truncate! body[:prev_log_index]
        @log.append! body[:entries]

        # Advance commit pointer
        if @commit_index < body[:leader_commit]
          @commit_index = [@log.size, body[:leader_commit]].min
          advance_state_machine!
        end

        # Acknowledge
        res[:success] = true
        @node.reply! msg, res
      end
    end

    # Leader election thread
    @node.every 0.1 do
      sleep(rand / 10)
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

    # Leader stepdown thread
    @node.every 0.1 do
      @lock.synchronize do
        if @state == :leader and @step_down_deadline < Time.now
          @node.log "Stepping down: haven't received any acks recently"
          become_follower!
        end
      end
    end

    # Replication thread
    @node.every @min_replication_interval do
      replicate_log! false
    end
  end

  # Returns the map of match indices, including an entry for ourselves, based
  # on our log size.
  def match_index
    @match_index.merge({@node.node_id => @log.size})
  end

  # Handles a client RPC request
  def client_req!(msg)
    @lock.synchronize do
      if @state == :leader
        @log.append! [{term: @term, op: msg}]
      elsif @leader
        # We're not the leader, but we can proxy to one.
        @node.rpc! @leader, msg[:body] do |res|
          @node.reply! msg, res[:body]
        end
      else
        raise RPCError.temporarily_unavailable "not a leader"
      end
    end
  end

  # What number would constitute a majority of n nodes?
  def majority(n)
    (n / 2.0).floor + 1
  end

  # Given a collection of elements, finds the median, biasing towards lower
  # values if there's a tie.
  def median(xs)
    xs.sort[xs.size - majority(xs.size)]
  end

  # Don't start an election for a while
  def reset_election_deadline!
    @lock.synchronize do
      @election_deadline = Time.now + (@election_timeout * (rand + 1))
    end
  end

  # We got communication; don't step down for a while
  def reset_step_down_deadline!
    @lock.synchronize do
      @step_down_deadline = Time.now + @election_timeout
    end
  end

  # If we're the leader, advance our commit index based on what other nodes
  # match us.
  def advance_commit_index!
    @lock.synchronize do
      if @state == :leader
        n = median(match_index.values)
        if @commit_index < n and @log[n][:term] == @term
          @node.log "Commit index now #{n}"
          @commit_index = n
        end
      end
      advance_state_machine!
    end
  end

  # If we have unapplied committed entries in the log, apply them to the state
  # machine.
  def advance_state_machine!
    @lock.synchronize do
      while @last_applied < @commit_index
        # Advance the applied index and apply that op.
        @last_applied += 1
        req = @log[@last_applied][:op]
        @node.log "Applying req #{req}"
        @state_machine, res = @state_machine.apply req[:body]
        @node.log "State machine res is #{res}"

        if @state == :leader
          # We're currently the leader: let's respond to the client.
          @node.reply! req, res
        end
      end
    end
  end

  def become_follower!
    @lock.synchronize do
      @state = :follower
      @match_index = nil
      @next_index = nil
      @leader = nil
      reset_election_deadline!
      @node.log "Became follower for term #{@term}"
    end
  end

  # Become a candidate, advance our term, and request votes.
  def become_candidate!
    @lock.synchronize do
      @state = :candidate
      advance_term!(@term + 1)
      @voted_for = @node.node_id
      @leader = nil
      reset_election_deadline!
      reset_step_down_deadline!
      @node.log "Became candidate for term #{@term}"
      request_votes!
    end
  end

  # Become a leader
  def become_leader!
    @lock.synchronize do
      unless @state == :candidate
        raise "Should be a candidate!"
      end

      @state = :leader
      @leader = nil
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

  # Advance our term to `term`, resetting who we voted for.
  def advance_term!(term)
    @lock.synchronize do
      unless @term < term
        raise "Term can't go backwards!"
      end
    end
    @term = term
    @voted_for = nil
  end

  # If remote_term is bigger than ours, advance our term and become a follower
  def maybe_step_down!(remote_term)
    @lock.synchronize do
      if @term < remote_term
        @node.log "Stepping down: remote term #{remote_term} higher than our term #{@term}"
        advance_term! remote_term
        become_follower!
      end
    end
  end

  # Request that other nodes vote for us as a leader.
  def request_votes!
    @lock.synchronize do
      # We vote for ourselves
      votes = Set.new [@node.node_id]
      term = @term

      @node.log "Last log #{@log.last}"

      @node.brpc!(
        type: 'request_vote',
        term: term,
        candidate_id: @node.node_id,
        last_log_index: @log.size,
        last_log_term: @log.last[:term]
      ) do |res|
        reset_step_down_deadline!

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

            if majority(@node.node_ids.size) <= votes.size
              # We have a majority of votes for this term!
              become_leader!
            end
          end
        end
      end
    end
  end

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

      if @state == :leader and @min_replication_interval < elapsed_time
        # We're a leader, and enough time elapsed
        @node.other_node_ids.each do |node|
          # What entries should we send this node?
          ni = @next_index[node]
          entries = @log.from_index ni
          if 0 < entries.size or @heartbeat_interval < elapsed_time
            @node.log "Replicating #{ni}+ to #{node}"
            replicated = true
            @node.rpc!(node, {
              type:           'append_entries',
              term:           @term,
              leader_id:      @node.node_id,
              prev_log_index: ni - 1,
              prev_log_term:  @log[ni - 1][:term],
              entries:        entries,
              leader_commit:  @commit_index
            }) do |res|
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
                    advance_commit_index!
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

      if replicated
        # We did something!
        @last_replication = Time.now
      end
    end
  end
end

Raft.new.node.main!
