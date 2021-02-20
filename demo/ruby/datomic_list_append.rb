#!/usr/bin/env ruby

# A strict-serializable implementation of a transactional list-append storage
# system, based on the Datomic transactor model. Uses an eventually consistent
# storage system (lww-kv) for immutable data, and stores a mutable pointer in a
# linearizable store (lin-kv).

require_relative 'node.rb'
require 'zlib'

# We store a single pointer (a string, uniquely generated) in a well-known key
# in lin-kv:
#
# root -> branch0
#
# The state, in turn, is a persistent tree of immutable nodes, each stored in
# lww-kv:
#
#       branch0
#        /   \
#       b1   b2
#      / \
#     /   \
#    /     \
#  leaf3  leaf4
#
# Each node in this tree is stored as a JSON object like so:
#
# {:type    "leaf"
#  :range   [5 10]
#  :pairs   [[k1 v1] [k2 v2] ...]}
#
# {:type     "branch"
#  :range    [5 10]
#  :branches [[upper-bound branch-ptr] ...]}
#
# All nodes have a range, which defines the [lower, upper) interval of hash
# values that node covers. Leaf nodes encode actual data: a map of actual keys
# to values, stored as kv pairs in JSON. Branch nodes encode pointers to other
# tree nodes: the values which hash within [lower, upper) are stored in `ptr`.
#
# We want to take advantage of aggressive caching, and also *not* materialize
# the entire world unless necessary, so we create immutable Leaf and Branch
# classes which lazily unpack their values as needed.


class Tree
  # Where do we store our immutable values?
  VALUE_SVC = "lww-kv"

  # Maximum size of the hash ring
  RING_SIZE = 128

  # Maximum number of elements per node
  BRANCH_FACTOR = 8

  # A map of pointers to locally cached Tree nodes.
  @@cache = {}

  # Computes the hashring index of a key. Note that Ruby's .hash is wildly
  # unstable from interpreter run to run.
  def self.hash(k)
    Zlib.crc32(k.to_s) % RING_SIZE
  end

  # Constructs a new empty leaf node that covers the whole hash space
  def self.empty(node)
    Leaf.new(node, "empty", [0, RING_SIZE], false, {})
  end

  # Inflate a tree node from a JSON value
  def self.from_json(node, ptr, json)
    range = json["range"]
    case json["type"]
    when "branch"
      Branch.new(node, ptr, range, true, json["branches"])
    when "leaf"
      Leaf.new(node, ptr, range, true, Hash[json["pairs"]])
    end
  end

  # Fetches a pointer to a tree node, either cached or from storage.
  def self.load(node, ptr)
    loop do
      node.node.log "Trying to load #{ptr}"
      if @@cache.has_key? ptr
        node.node.log "Cache has #{ptr}!"
        return @@cache[ptr]
      else
        res = node.node.sync_rpc! VALUE_SVC, {type: "read", key: ptr}
        body = res[:body]
        if body[:type] == "read_ok"
          tree = from_json node, ptr, body[:value]
          @@cache[ptr] = tree
          return tree
        else
          node.node.log "Retrying read of tree node #{ptr}"
        end
      end
    end
  end

  attr_reader :node, :ptr, :range, :saved

  def initialize(node, ptr, range, saved)
    raise unless node.is_a? DatomicListAppendNode
    raise unless ptr.is_a? String
    raise unless range.is_a? Array
    raise unless range.length == 2
    raise unless range[0].is_a? Integer
    raise unless range[1].is_a? Integer
    @node   = node
    @ptr    = ptr
    @range  = range
    @saved  = saved
  end

  # Ensures a key falls within this node's hash range
  def bounds_check(k)
    h = Tree.hash k
    lower, upper = @range
    unless lower <= h and h < upper
      raise "Key #{k} with hash #{h} does not belong in\n#{inspect}"
    end
  end

  # Writes this node to storage. Returns a promise which is delivered once
  # saved.
  def save_this!
    p = Promise.new
    @node.node.rpc!(VALUE_SVC, {
      type: "write",
      key: @ptr,
      value: self.to_json
    }) do |res|
      body = res[:body]
      if body[:type] == "write_ok"
        p.deliver! true
      else
        p.deliver! false
      end
    end

    p
  end

  def to_s
    inspect
  end
end


class Leaf < Tree
  def initialize(node, ptr, range, saved, map)
    super node, ptr, range, saved
    @map = map
  end

  def [](k)
    bounds_check k
    @map[k]
  end

  def assoc(k, v)
    bounds_check k

    if @map.has_key?(k) or @map.size < BRANCH_FACTOR
      # We've got space to accomodate this update
      Leaf.new @node, @node.new_ptr, @range, false, @map.merge({k => v})
    else
      # Replace ourselves with a new branch node. First, split up our map into
      # several new leaf nodes, one per branch...
      lower, upper = range
      branch_size = ((upper - lower) / BRANCH_FACTOR).floor
      branches = (0...BRANCH_FACTOR).map do |i|
        # Compute upper and lower bounds for this particular child branch
        b_lower = lower + (i * branch_size)
        if i == (BRANCH_FACTOR - 1)
          # Final branch stretches to cover the upper bounds
          b_upper = upper
        else
          b_upper = b_lower + branch_size
        end
        # Which of our keys go in this branch? We might actually mess up if
        # everything hashed to one child and push *everything* down into this
        # node, but that's not the end of the world--the next assoc will split
        # it again.
        b_map = @map.merge({k => v}).filter do |k, v|
          hash = Tree.hash k
          b_lower <= hash and hash < b_upper
        end
        # Construct new [upper, leaf] pair
        [b_upper,
         Leaf.new(@node, @node.new_ptr, [b_lower, b_upper], false, b_map)]
      end
      # Then construct a new Branch node for ourselves.
      Branch.new @node, @node.new_ptr, [lower, upper], false, branches
    end
  end

  def inspect
    "(leaf #{@ptr} [#{@range[0]} #{@range[1]}] #{@map})"
  end

  def to_json
    {type: "leaf",
     range: range,
     pairs: @map.entries}
  end

  # Saving a leaf node is simply saving the node itself.
  def save!
    return Promise.new.deliver!(true) if saved

    p = save_this!
    # Mark ourselves as saved once the write completes.
    Thread.new do
      if p.await
        @saved = true
      end
    end
    p
  end
end

class Branch < Tree
  attr_reader :branches

  def initialize(node, ptr, range, saved, branches)
    super node, ptr, range, saved
    @branches = branches
  end

  # Returns the index of the branch for a given key. Ensures the branch is
  # loaded from storage, as a side-effect.
  def branch_index(k)
    bounds_check k
    # What branch does this key belong to?
    hash = Tree.hash k
    # Search through branches to find the one this key belongs to.
    @branches.each_with_index do |pair, i|
      upper, branch = pair
      if hash < upper
        # This is our spot!
        unless branch.is_a? Tree
          # We haven't loaded this part of the tree yet; grab it.
          @branches[i][1] = Tree.load @node, branch
        end
        return i
      end
    end
  end

  def [](k)
    i = branch_index k
    @branches[i][1][k]
  end

  def assoc(k, v)
    i = branch_index k

    # Create a copy of that branch with this k/v pair assoc'ed on
    branches = @branches.clone
    upper, branch = branches[i]
    branches[i] = [upper, branch.assoc(k, v)]

    # And create a new, unsaved copy of this branch, referring to it.
    Branch.new(@node, @node.new_ptr, @range, false, branches)
  end

  def inspect
    branches = @branches.map { |upper, branch|
      "#{upper}\t#{branch.inspect}"
    }.join("\n").gsub!(/^/, "  ")
    "(branch #{@ptr} [#{@range[0]} #{@range[1]}]\n#{branches}\n)"
  end

  def to_json
    {type:    "branch",
     range:    range,
     branches: @branches.map { |upper, branch|
       if branch.is_a? Tree
         [upper, branch.ptr]
       else
         [upper, branch]
       end
     }}
  end

  # To save a branch, we need to save it *and* all its unsaved children.
  def save!
    return Promise.new.deliver!(true) if saved

    # Fire off all of our saving tasks
    tasks = []
    @branches.each do |upper, branch|
      if branch.is_a? Tree
        tasks << branch.save!
      end
    end
    tasks << save_this!

    # Once saved, we can set our own status to saved.
    p = Promise.new
    Thread.new do
      begin
        if tasks.map(&:await).all?
          # We succeeded
          @saved = true
          p.deliver! true
        else
          p.deliver! false
        end
      rescue RPCError => e
        p.deliver! false
      end
    end
    p
  end
end

class DatomicListAppendNode
  # Where do we store the root pointer to the entire DB tree?
  ROOT = "root"
  ROOT_SVC = "lin-kv"

  attr_reader :node

  def initialize
    @node       = Node.new
    @ptr        = 0
    # A performance optimization to reduce contention on a single node.
    @txn_lock   = Mutex.new

    @node.on_init do |msg|
      if @node.node_ids.first == @node.node_id
        # Write initial state.
        t = Tree.empty(self)
        t.save!.await or raise RPCError.abort "Couldn't write initial state"
        @node.sync_rpc! ROOT_SVC, type: "write", key: ROOT, value: t.ptr
      end
    end

    @node.on "txn" do |msg|
      @txn_lock.synchronize do
        txn = msg[:body][:txn]
        @node.log "\nTxn is #{txn}"

        tree1 = current_tree
        @node.log "Current state is #{tree1}"

        tree2, txn2 = apply_txn(tree1, txn)
        @node.log "Final state is #{tree2}"

        if tree1.ptr != tree2.ptr
          tree2.save!.await or raise RPCError.abort "Couldn't save new tree"
          @node.log "State #{tree2.ptr} written"

          advance_root! tree1.ptr, tree2.ptr
          @node.log "Pointer advanced from #{tree1.ptr} to #{tree2.ptr}"
        end

        @node.reply! msg, {type: "txn_ok", txn: txn2}
      end
    end
  end

  # Gets a new, unique pointer.
  def new_ptr
    p = @ptr += 1
    "#{@node.node_id}-#{p}"
  end

  # Returns the current tree.
  def current_tree
    res = @node.sync_rpc! ROOT_SVC, {type: "read", key: ROOT}
    body = res[:body]
    if body[:type] == "read_ok"
      return Tree.load self, body[:value]
    end
    raise RPCError.abort "Unsure how to handle #{body}"
  end

  # Bumps the pointer atomically from p1 to p2. Returns iff pointer advanced;
  # throws otherwise.
  def advance_root!(p1, p2)
    res = @node.sync_rpc! ROOT_SVC, {type: "cas", key: ROOT, from: p1, to: p2}
    if res[:body][:type] == "cas_ok"
      true
    else
      raise RPCError.txn_conflict "pointer no longer #{p1}"
    end
  end

  # Applies a transaction to the given Tree, returning [tree', completed-txn]
  def apply_txn(tree, txn)
    txn2 = []

    tree2 = txn.reduce(tree) do |t, op|
      @node.log "Op: #{op}\nTree: #{t.inspect}"
      f, k, v = op
      case f
        when "r"
          txn2 << [f, k, t[k]]
          t
        when "append"
          txn2 << op
          list = (t[k].clone or [])
          list << v
          t.assoc k, list
        else
          raise "Unrecognized op type #{f}"
      end
    end

    [tree2, txn2]
  end

  def main!
    @node.main!
  end
end

DatomicListAppendNode.new.main!
