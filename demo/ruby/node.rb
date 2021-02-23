# Shared functions for all nodes.

require 'json'
require_relative 'promise.rb'
require_relative 'errors.rb'

class Node
  attr_reader :node_id, :node_ids

  def initialize
    @node_id = nil
    @node_ids = nil
    @next_msg_id = 0
    @init_handlers = []
    @handlers = {}
    @callbacks = {}
    @every_tasks = []
    @lock = Monitor.new
    @log_lock = Mutex.new

    # Register an initial handler for the init message
    on "init" do |msg|
      # Set our node ID and IDs
      body = msg[:body]
      @node_id = body[:node_id]
      @node_ids = body[:node_ids]

      # Call init handlers
      @init_handlers.each do |h|
        h.call msg
      end

      # Let Maelstrom know we initialized
      reply! msg, {type: "init_ok"}
      log "Node initialized"

      # Spawn periodic task handlers
      start_every_tasks!
    end
  end

  # Writes a message to stderr
  def log(message)
    @log_lock.synchronize do
      STDERR.puts message
      STDERR.flush
    end
  end

  # Returns an array of nodes other than ourselves.
  def other_node_ids
    ids = @node_ids.clone
    ids.delete @node_id
    ids
  end

  # Register a new message type handler
  def on(type, &handler)
    if @handlers[type]
      raise "Already have a handler for #{type}!"
    end

    @handlers[type] = handler
  end

  # A special handler for initialization
  def on_init(&handler)
    @init_handlers << handler
  end

  # Send a body to the given node id. Fills in src with our own node_id.
  def send!(dest, body)
    msg = {dest: dest,
           src: @node_id,
           body: body}
    @lock.synchronize do
      log "Sent #{msg.inspect}"
      JSON.dump msg, STDOUT
      STDOUT << "\n"
      STDOUT.flush
    end
  end

  # Broadcasts a message to all other nodes.
  def broadcast!(body)
    other_node_ids.each do |n|
      send! n, body
    end
  end

  # Reply to a request with a response body
  def reply!(req, body)
    body = body.merge({in_reply_to: req[:body][:msg_id]})
    send! req[:src], body
  end

  # Send an async RPC request. Invokes block with response message once one
  # arrives.
  def rpc!(dest, body, &handler)
    @lock.synchronize do
      msg_id = @next_msg_id += 1
      @callbacks[msg_id] = handler
      body[:msg_id] = msg_id
      send! dest, body
    end
  end

  # Sends a synchronous RPC request, blocking this thread and returning the
  # response message.
  def sync_rpc!(dest, body)
    p = Promise.new
    rpc! dest, body do |response|
      p.deliver! response
    end
    p.await
  end

  # Periodically evaluates block every dt seconds with the node lock
  # held--helpful for building periodic replication tasks, timeouts, etc.
  def every(dt, &block)
    @every_tasks << {dt: dt, f: block}
  end

  # Launches threads to process periodic handlers
  def start_every_tasks!
    @every_tasks.each do |task|
      Thread.new do
        loop do
          @lock.synchronize do
            task[:f].call
          end
          sleep task[:dt]
        end
      end
    end
  end

  # Turns a line of STDIN into a message hash
  def parse_msg(line)
    msg = JSON.parse line
    msg.transform_keys!(&:to_sym)
    msg[:body].transform_keys!(&:to_sym)
    msg
  end

  # Loops, processing messages.
  def main!
    Thread.abort_on_exception = true

    while line = STDIN.gets
      msg = parse_msg line
      log "Received #{msg.inspect}"

      handler = nil
      @lock.synchronize do
        if handler = @callbacks[msg[:body][:in_reply_to]]
          @callbacks.delete msg[:body][:in_reply_to]
        elsif handler = @handlers[msg[:body][:type]]
        else
          raise "No callback or handler for #{msg.inspect}"
        end
      end

      Thread.new(msg) do |msg|
        begin
          handler.call msg
        rescue RPCError => e
          reply! msg, e.to_json
        rescue => e
          log "Exception handling #{msg}:\n#{e.full_message}"
          reply! msg, RPCError.crash(e.full_message).to_json
        end
      end
    end
  end
end
