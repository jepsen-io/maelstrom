#!/usr/bin/ruby

# A basic broadcast system. Keeps track of a set of messages, inserted via
# `broadcast` requests from clients.

require_relative 'node.rb'
require 'set'

class BroadcastNode
  def initialize
    @node = Node.new
    @neighbors = []
    @lock = Mutex.new
    @messages = Set.new

    @node.on "topology" do |msg|
      @neighbors = msg[:body][:topology][@node.node_id.to_sym]
      @node.log "My neighbors are #{@neighbors.inspect}"
      @node.reply! msg, {type: "topology_ok"}
    end

    @node.on "read" do |msg|
      @lock.synchronize do
        @node.reply! msg, {type: "read_ok",
                           messages: @messages.to_a}
      end
    end

    @node.on "broadcast" do |msg|
      m = msg[:body][:message]
      @lock.synchronize do
        unless @messages.include? m
          @messages.add m
          @node.log "messages now #{@messages}"

          # Broadcast this message to neighbors (except whoever sent it to us)
          @node.other_node_ids.each do |neighbor|
            unless neighbor == msg[:src]
              @node.rpc! neighbor, {type: "broadcast", message: m} do |res|
                # Eh, whatever
              end
            end
          end
        end
      end
      @node.reply! msg, {type: "broadcast_ok"}
    end
  end

  def main!
    @node.main!
  end
end

BroadcastNode.new.main!
