#!/usr/bin/ruby

# A single PN counter.

require_relative 'node.rb'
require_relative 'crdt.rb'

class GCounter
  attr_reader :counters

  # We represent a G-counter as a map of node_id => value_at_node
  def initialize(counters = {})
    @counters = counters
  end

  def from_json(json)
    GCounter.new json
  end

  def to_json
    @counters
  end

  # The value of a G-counter is the sum of its values.
  def read
    @counters.values.reduce(0) do |sum, x|
      sum + x
    end
  end

  # Adding a value to a counter means incrementing the value for this
  # node_id.
  def add(node_id, delta)
    counters = @counters.dup
    counters[node_id] = (counters[node_id] || 0) + delta
    GCounter.new counters
  end

  # Merging two G-counters means taking the maxima of corresponding hash
  # elements.
  def merge(other)
    GCounter.new(@counters.merge(other.counters) { |k, v1, v2|
      [v1, v2].max
    })
  end
end

class PNCounter
  # A PN-Counter combines a G-counter for increments and another for
  # decrements
  attr_reader :inc, :dec

  def initialize(inc = GCounter.new, dec = GCounter.new)
    @inc = inc
    @dec = dec
  end

  def from_json(json)
    PNCounter.new(@inc.from_json(json["inc"]),
                  @dec.from_json(json["dec"]))
  end

  def to_json
    {inc: @inc.to_json,
     dec: @dec.to_json}
  end

  # The value of the G-counter is the increments minus the decrements
  def read
    @inc.read - @dec.read
  end

  # Adding a delta to the counter means modifying the value in one of the
  # g-counters.
  def add(node_id, delta)
    if 0 <= delta
      PNCounter.new @inc.add(node_id, delta), @dec
    else
      PNCounter.new @inc, @dec.add(node_id, -delta)
    end
  end

  # Merging PN-Counters just means merging the incs and decs
  def merge(other)
    PNCounter.new @inc.merge(other.inc), @dec.merge(other.dec)
  end
end

class PNCounterNode
  def initialize
    @node = Node.new
    @counter = CRDT.new(@node, PNCounter.new)

    # We can take an add message with a `delta` integer, and
    # increment/decrement our entry in the counter.
    @node.on "add" do |msg|
      @counter.value = @counter.value.add @node.node_id, msg[:body][:delta]
      STDERR.puts "Value now #{@counter.value.to_json}"
      @node.reply! msg, {type: "add_ok"}
    end
  end

  def main!
    @node.main!
  end
end

PNCounterNode.new.main!
