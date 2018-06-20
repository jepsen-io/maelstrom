#!/usr/bin/ruby

require 'json'
require 'set'

class Logger
  def <<(*args)
    STDERR.puts *args
  end
end

class Log
  def initialize(logger)
    @logger = logger
    @entries = [{term: 0, op: nil}]
  end

  # Raft's log is 1-indexed
  def [](i)
    @entries[i-1]
  end

  def append(entries)
    @entries += entries
    @logger << "Log: #{@entries.inspect}"
  end

  def last
    @entries[-1]
  end

  def last_term
    if l = last
      l[:term]
    else
      0
    end
  end

  def size
    @entries.size
  end

  # Truncate log to length len
  def truncate(len)
    @entries.slice! len...size
  end

  # Entries from index i onwards
  def from(i)
    raise "illegal index #{i}" unless 0 < i
    @entries.slice(i - 1 .. -1)
  end
end

class Client
  attr_accessor :node_id

  def initialize(logger)
    @node_id = nil
    @logger = logger
    @next_msg_id = 0
    @handlers = {}
    @callbacks = {}

    @in_buffer = ""
  end

  # Generate a fresh message id
  def new_msg_id
    @next_msg_id += 1
  end

  # Register a new message type handler
  def on(type, &handler)
    if @handlers[type]
      raise "Already have a handler for #{type}!"
    end

    @handlers[type] = handler
  end

  # Sends a raw message
  def send_msg!(msg)
    @logger << "Sent #{msg.inspect}"
    JSON.dump msg, STDOUT
    STDOUT << "\n"
    STDOUT.flush
  end

  # Send a body to the given node id. Fills in src with our own node_id.
  def send!(dest, body)
    send_msg!({dest: dest, src: @node_id, body: body})
  end

  # Reply to a request with a response body
  def reply!(req, body)
    body[:in_reply_to] = req[:body][:msg_id]
    send! req[:src], body
  end

  # Send an RPC request
  def rpc!(dest, body, &handler)
    msg_id = new_msg_id
    @callbacks[msg_id] = handler
    body[:msg_id] = msg_id
    send! dest, body
  end

  # Consumes available input from stdin, filling @in_buffer. Returns a line
  # if ready, or nil if no line ready yet.
  def readline_nonblock
    begin
      loop do
        x = STDIN.read_nonblock 1
        break if x == "\n"
        @in_buffer << x
      end

      res = @in_buffer
      @in_buffer = ""
      res
    rescue IO::WaitReadable
    end
  end

  # Processes a single message from stdin, if one is available.
  def process_msg!
    if line = readline_nonblock
      msg = JSON.parse line, symbolize_names: true
      @logger << "Received #{msg.inspect}"

      handler = nil
      if handler = @callbacks[msg[:body][:in_reply_to]]
        @callbacks.delete msg[:body][:in_reply_to]
      elsif handler = @handlers[msg[:body][:type]]
      else
        raise "No callback or handler for #{msg.inspect}"
      end
      handler.call msg
      true
    end
  end
end

class KVStore
  def initialize(logger)
    @logger = logger
    @state = {}
  end

  # Apply op to state machine and generate a response message
  def apply!(op)
    res = nil
    k = op[:key]
    case op[:type]
    when "read"
      if @state.include? k
        res = {type: "read_ok", value: @state[op[:key]]}
      else
        res = {type: "error", code: 20, text: "not found"}
      end
    when "write"
      @state[k] = op[:value]
      res = {type: "write_ok"}
    when "cas"
      if not @state.include? k
        res = {type: "error", code: 20, text: "not found"}
      elsif @state[k] != op[:from]
        res = {type: "error",
               code: 22,
               text: "expected #{op[:from]}, had #{@state[k]}"}
      else
        @state[k] = op[:to]
        res = {type: "cas_ok"}
      end
    end
    @logger << "KV: #{@state.inspect}"

    res[:in_reply_to] = op[:msg_id]
    {dest: op[:client], body: res}
  end
end

class RaftNode
  def initialize
    @election_timeout = 2.0
    @heartbeat_interval = 1.0
    @min_replication_interval = 0.05
    @election_deadline = Time.at 0
    @last_replication = Time.at 0

    @logger = Logger.new

    @node_id     = nil
    @node_ids    = nil

    # Raft state
    @current_term = 0
    @voted_for    = nil
    @log          = Log.new @logger

    @commit_index = 0
    @last_applied = 1

    @leader = nil

    @client = Client.new @logger
    @state_machine = KVStore.new @logger
    setup_handlers!

    @state = :nascent
  end

  # What number would constitute a majority of n nodes?
  def majority(n)
    (n / 2.0 + 1).floor
  end

  # Given a collection of elements, finds the median, biasing towards lower
  # values if there's a tie.
  def median(xs)
    xs.sort[xs.size - majority(xs.size)]
  end

  def other_nodes
    @node_ids - [@node_id]
  end

  def next_index
    m = @next_index.dup
    m[@node_id] = @log.size + 1
    m
  end

  def match_index
    m = @match_index.dup
    m[@node_id] = @log.size
    m
  end

  def node_id=(id)
    @node_id = id
    @client.node_id = id
  end

  # Broadcast RPC
  def brpc!(body, &handler)
    other_nodes.each do |node_id|
      @client.rpc! node_id, body, &handler
    end
  end

  def reset_election_deadline!
    @election_deadline = Time.now + (@election_timeout * (rand + 1))
  end

  def advance_term!(term)
    raise "Can't go backwards" unless @current_term < term
    @current_term = term
    @voted_for = nil
  end

  def maybe_step_down!(remote_term)
    if @current_term < remote_term
      advance_term! remote_term
      become_follower!
    end
  end

  def request_votes!
    votes = Set.new([@node_id])

    brpc!(
      type:            "request_vote",
      term:            @current_term,
      candidate_id:    @node_id,
      last_log_index:  @log.size,
      last_log_term:   @log.last_term
    ) do |response|
      body = response[:body]
      case body[:type]
      when "request_vote_res"
        maybe_step_down! body[:term]
        if @state == :candidate and body[:vote_granted] and body[:term] == @current_term
          # Got a vote for our candidacy
          votes << response[:src]
          if majority(@node_ids.size) <= votes.size
            # We have a majority of votes for this term
            become_leader!
          end
        end
      else
        raise "Unknown response message: #{response.inspect}"
      end
    end
  end

  ## Transitions between roles ###############################################

  def become_follower!
    @logger << "Became follower for term #{@current_term}"
    @state = :follower
    @leader = nil
    @next_index = nil
    @match_index = nil
  end

  def become_candidate!
    @state = :candidate
    advance_term! @current_term + 1
    @voted_for = @node_id
    @leader = nil
    @logger << "Became candidate for term #{@current_term}"
    reset_election_deadline!
    request_votes!
  end

  def become_leader!
    raise "Should be a candidate" unless @state == :candidate
    @logger << "Became leader for term #{@current_term}"
    @state = :leader
    @leader = nil
    @next_index = Hash[other_nodes.zip([@log.size + 1] * other_nodes.size)]
    @match_index = Hash[other_nodes.zip([0] * other_nodes.size)]
  end


  ## Rules for all servers ##################################################

  def advance_state_machine!
    if @last_applied < @commit_index
      @last_applied += 1
      res = @state_machine.apply! @log[@last_applied][:op]
      if @state == :leader
        @client.send! res[:dest], res[:body]
      end
      true
    end
  end


  ## Rules for leaders ######################################################

  def replicate_log!(force)
    if @state == :leader and @min_replication_interval < (Time.now - @last_replication)
      other_nodes.each do |node|
        ni = @next_index[node]
        entries = @log.from ni
        if 0 < entries.size or @heartbeat_interval < (Time.now - @last_replication)
          @last_replication = Time.now

          @logger << "replicating #{ni}+: #{entries.inspect}"
          @client.rpc!(
            node,
            type:            "append_entries",
            term:            @current_term,
            leader_id:       @node_id,
            prev_log_index:  ni - 1,
            prev_log_term:   @log[ni - 1][:term],
            entries:         entries,
            leader_commit:   @commit_index
          ) do |res|
            body = res[:body]
            maybe_step_down! body[:term]

            if @state == :leader
              if body[:success]
                @next_index[node] =
                  [@next_index[node], ni + entries.size].max
                @match_index[node] =
                  [@match_index[node], ni - 1 + entries.size].max
              else
                @next_index[node] -= 1
              end
            end
          end
          true
        end
      end
    end
  end

  def leader_advance_commit_index!
    if @state == :leader
      n = median match_index.values
      if @commit_index < n and @log[n][:term] == @current_term
        @commit_index = n
        true
      end
    end
  end


  ## Leader election ########################################################


  def election!
    if @election_deadline < Time.now
      if (@state == :follower or @state == :candidate)
        # Time for an election!
        become_candidate!
      else
        # We're a leader or initializing, sleep again
        reset_election_deadline!
      end
      true
    end
  end

  ## Top-level message handlers #############################################

  def add_log_entry!(msg)
    body = msg[:body]
    if @state == :leader
      body[:client] = msg[:src]
      @log.append [{term: @current_term, op: body}]
    elsif @leader
      # Proxy to current leader
      @logger << "Proxying to #{@leader}"
      msg[:dest] = @leader
      @client.send_msg! msg
    else
      @client.reply! msg, {type: "error", code: 11, text: "not a leader"}
    end
  end

  def setup_handlers!
    @client.on "raft_init" do |msg|
      raise "Can't init twice!" unless @state == :nascent

      body = msg[:body]
      self.node_id = body[:node_id]
      @node_ids = body[:node_ids]
      @logger << "Raft init!"
      @client.reply! msg, {type: "raft_init_ok"}

      reset_election_deadline!
      become_follower!
    end

    @client.on "request_vote" do |msg|
      body = msg[:body]
      maybe_step_down! body[:term]
      grant = false
      if (body[:term] < @current_term)
      elsif (@voted_for == nil or @voted_for == body[:candidate_id]) and
        @log.last_term <= body[:last_log_term] and
        @log.size <= body[:last_log_index]

        grant = true
        @voted_for = body[:candidate_id]
        reset_election_deadline!
      end

      @client.reply! msg, {type: "request_vote_res",
                           term: @current_term,
                           vote_granted: grant}
    end

    @client.on "append_entries" do |msg|
      body = msg[:body]
      maybe_step_down! body[:term]
      reset_election_deadline!

      ok  = {type: "append_entries_res", term: @current_term, success: true}
      err = {type: "append_entries_res", term: @current_term, success: false}

      if body[:term] < @current_term
        # Leader is behind us
        @client.reply! msg, err
        break
      end

      @leader = body[:leader_id]

      # TODO: I think there's a bug here; we should assign e before *instead*
      # of having it in the if.
      if 0 < body[:prev_log_index] and e = @log[body[:prev_log_index]] and (e.nil? or e[:term] != body[:prev_log_term])
        # We disagree on the previous log term
        @client.reply! msg, err
      else
        # OK, we agree on the previous log term now. Truncate and append
        # entries.
        @log.truncate body[:prev_log_index]
        @log.append body[:entries]

        # Advance commit pointer
        if @commit_index < body[:leader_commit]
          @commit_index = [body[:leader_commit], @log.size].min
        end

        @client.reply! msg, ok
      end
    end

    @client.on "read"  do |msg| add_log_entry! msg end
    @client.on "write" do |msg| add_log_entry! msg end
    @client.on "cas"   do |msg| add_log_entry! msg end
  end

  def main!
    @logger << "Online"
    while true
      begin
        @client.process_msg! or
          replicate_log! false or
          election! or
          leader_advance_commit_index! or
          advance_state_machine! or
          sleep 0.001
      rescue StandardError => e
        @logger << "Caught #{e}:\n#{e.backtrace.join "\n"}"
      end
    end
  end
end

RaftNode.new.main!
