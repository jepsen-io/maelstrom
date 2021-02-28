#!/usr/bin/ruby

# A CRDT-based grow-only set.

require_relative 'node.rb'
require 'set'

class GSetNode
  def initialize
    @node = Node.new
    @set = Set.new

    @node.on "read" do |msg|
      @node.reply! msg, {type: "read_ok", value: @set.to_a}
    end

    @node.on "add" do |msg|
      element = msg[:body][:element]
      @set.add element
      @node.reply! msg, {type: "add_ok"}
    end

    # Accept a single element from another node
    @node.on "replicate_one" do |msg|
      @set.add msg[:body][:element]
    end

    # Accept an entire value from another node
    @node.on "replicate_full" do |msg|
      @set |= msg[:body][:value]
    end

    # Periodically replicate entire state
    @node.every 5 do
      STDERR.puts "Replicating!"
      @node.other_node_ids.each do |node|
        @node.send! node, ({type: "replicate_full", value: @set.to_a})
      end
    end
  end

  def main!
    @node.main!
  end
end

GSetNode.new.main!
