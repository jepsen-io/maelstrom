#!/usr/bin/ruby

# A linearizable key-value store which works by proxying all requests to a
# Maelstrom-provided linearizable kv store.

require_relative 'node.rb'

class LinKVNode
  def initialize
    @node = Node.new

    @node.on "read" do |msg|
      proxy! msg
    end

    @node.on "write" do |msg|
      proxy! msg
    end

    @node.on "cas" do |msg|
      proxy! msg
    end
  end

  # Takes a client request, proxies the body to lin_kv, and sends the response
  # back to the client.
  def proxy!(req_msg)
    proxy_body = req_msg[:body].clone
    proxy_body.delete :msg_id

    # Replace with seq-kv or lww-kv to see linearization failures!
    # @node.rpc! "seq-kv", proxy_body do |res|
    @node.rpc! "lin-kv", proxy_body do |res|
      res[:body].delete :msg_id
      @node.reply! req_msg, res[:body]
    end
  end

  def main!
    @node.main!
  end
end

LinKVNode.new.main!
