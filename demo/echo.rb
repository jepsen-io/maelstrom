#!/usr/bin/ruby

# A simple echo server

require 'json'
require 'set'

class Logger
  def <<(*args)
    STDERR.puts *args
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

class EchoNode
  def initialize
    @logger = Logger.new
    @client = Client.new @logger
    @state = :init

    @node_id     = nil
    @node_ids    = nil

    setup_handlers!
  end

  ## Top-level message handlers #############################################

  def setup_handlers!
    @client.on "init" do |msg|
      raise "Can't init twice!" unless @state == :init

      body = msg[:body]
      @node_id = body[:node_id]
      @client.node_id = @node_id
      @node_ids = body[:node_ids]
      @logger << "Maelstrom init!"
      @client.reply! msg, {type: "init_ok"}
    end

    @client.on "echo" do |msg|
      @client.reply! msg, msg[:body]
    end
  end

  def main!
    @logger << "Online"
    while true
      begin
        @client.process_msg! or
          sleep 0.001
      rescue StandardError => e
        @logger << "Caught #{e}:\n#{e.backtrace.join "\n"}"
      end
    end
  end
end

EchoNode.new.main!
