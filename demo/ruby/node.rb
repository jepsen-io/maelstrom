require 'json'
require_relative 'errors.rb'
require_relative 'promise.rb'

class Node
  attr_reader :node_id, :node_ids

  def initialize
    @node_id = nil
    @node_ids = nil
    @next_msg_id = 0

    @init_handlers = []
    @handlers = {}
    @callbacks = {}
    @periodic_tasks = []

    @lock = Monitor.new
    @log_lock = Mutex.new

    # Register an initial handler for the init message
    on "init" do |msg|
      # Set our node ID and IDs
      @node_id = msg[:body][:node_id]
      @node_ids = msg[:body][:node_ids]

      @init_handlers.each do |h|
        h.call msg
      end

      reply! msg, type: "init_ok"
      log "Node #{@node_id} initialized"

      # Launch threads for periodic tasks
      start_periodic_tasks!
    end
  end

  # Writes a message to stderr
  def log(message)
    @log_lock.synchronize do
      STDERR.puts message
      STDERR.flush
    end
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

  # Periodically evaluates block every dt seconds with the node lock
  # held--helpful for building periodic replication tasks, timeouts, etc.
  def every(dt, &block)
    @periodic_tasks << {dt: dt, f: block}
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
      body = body.merge({msg_id: msg_id})
      send! dest, body
    end
  end

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

  # Sends a synchronous RPC request, blocking this thread and returning the
  # response message.
  def sync_rpc!(dest, body)
    p = Promise.new
    rpc! dest, body do |response|
      p.deliver! response
    end
    p.await
  end

  # Launches threads to process periodic handlers
  def start_periodic_tasks!
    @periodic_tasks.each do |task|
      Thread.new do
        loop do
          task[:f].call
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

  # Loops, processing messages from STDIN
  def main!
    Thread.abort_on_exception = true

    while line = STDIN.gets
      msg = parse_msg line
      log "Received #{msg.inspect}"

      # What handler should we use for this message?
      handler = nil
      @lock.synchronize do
        if in_reply_to = msg[:body][:in_reply_to]
          if handler = @callbacks[msg[:body][:in_reply_to]]
            @callbacks.delete msg[:body][:in_reply_to]
          else
            log "Ignoring reply to #{in_reply_to} with no callback"
          end
        elsif handler = @handlers[msg[:body][:type]]
        else
          raise "No handler for #{msg.inspect}"
        end
      end

      if handler
        # Actually handle message
        Thread.new(handler, msg) do |handler, msg|
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
end
