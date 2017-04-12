#!/usr/bin/ruby

require 'json'

class RaftNode
  @node_id     = nil
  @node_ids    = nil
  @next_msg_id = 0

  # Generate a fresh message id
  def new_msg_id
    @next_msg_id += 1
  end

  # Send a body to the given node id
  def send!(dest, body)
    JSON.dump({dest: dest,
               src:  @node_id,
               body: body},
              STDOUT)
    STDOUT << "\n"
    STDOUT.flush
  end

  # Reply to a request with a response body
  def reply!(req, body)
    body[:in_reply_to] = req[:body][:msg_id]
    send! req[:src], body
  end

  def main
    STDERR.puts "Online"
    while true
      msg = JSON.parse(STDIN.gets, symbolize_names: true)
      STDERR.puts "Received #{msg.inspect}"
      body = msg[:body]

      case body[:type]
      when "raft_init"
        @node_id = body[:node_id]
        @node_ids = body[:node_ids]
        STDERR.puts "Raft init!"
        reply! msg, {type: "raft_init_ok"}
      end
    end
  end
end

RaftNode.new.main
