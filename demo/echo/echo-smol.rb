#!/usr/bin/ruby

# A simple echo server, in as few lines as possible.

require 'json'

class EchoServer
  def initialize
    @node_id = nil
    @next_msg_id = 0
  end

  # Replies to a request message with the given body.
  def reply!(request, body)
    id = @next_msg_id += 1
    body[:msg_id] = id,
    body[:in_reply_to] = request[:msg_id]
    msg = {src: @node_id, dest: request[:src], body: body}
    JSON.dump msg, STDOUT
    STDOUT << "\n"
    STDOUT.flush
  end

  # Runs the main loop
  def main!
    loop do
      # Wait for a message
      req = JSON.parse gets, symbolize_names: true
      STDERR << "Received #{req.inspect}\n"

      body = req[:body]
      case body[:type]
        when "init"
          @node_id = body[:node_id]
          STDERR << "Initialized node #{@node_id}\n"
          reply! req, {type: :init_ok}

        when "echo"
          STDERR << "Echoing #{body}"
          reply! req, body
      end
    end
  end
end

EchoServer.new.main!
