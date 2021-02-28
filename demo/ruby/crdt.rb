#!/usr/bin/ruby

# Sets up a Node to act as a generic CRDT server. Takes a CRDT value with four
# methods:
#
# .from_json(json) Inflates a value (ignoring dt) from a JSON structure
# .to_json         Returns a JSON structure representation for serialization
# .merge(other)    Returns a copy of value merged with other.
# .read            Returns the effective state of this CRDT value.
#
# As a node, supports the following messages:
#
# {type: "read"}                returns the current value of the valuetype
# {type: "merge", :value value}  merges a value into our own
#
# The current value of the CRDT is available in crdt.value.

class CRDT
  attr_accessor :node, :value

  def initialize(node, value)
    @node  = node
    @value = value

    @node.on "read" do |msg|
      @node.reply! msg, type: "read_ok", value: @value.read
    end

    # Merge another node's value into our own
    @node.on "merge" do |msg|
      @value = @value.merge @value.from_json(msg[:body][:value])
      STDERR.puts "Value now #{@value.to_json}"
      @node.reply! msg, type: "merge_ok"
    end

    # Silently ignore merge_ok messages
    @node.on "merge_ok" do |msg|
    end

    # Periodically replicate entire state
    @node.every 5 do
      @node.other_node_ids.each do |node|
        @node.send! node, {type: "merge", value: @value.to_json}
      end
    end
  end
end
