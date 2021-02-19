#!/usr/bin/env ruby

# A strict-serializable implementation of a transactional list-append storage
# system, based on the Datomic transactor model. Uses an eventually consistent
# storage system (lww-kv) for immutable data, and stores a mutable pointer in a
# linearizable store (lin-kv).

require_relative 'node.rb'

class DatomicListAppendNode
  def initialize
    @node       = Node.new
    @ptr        = 0
    @ptr_svc    = "lin-kv"
    @value_svc  = "lww-kv"
    # A performance optimization to reduce contention on a single node.
    @txn_lock   = Mutex.new

    @node.on_init do |msg|
      # Write initial state.
      write_state! "init", {}
      @node.sync_rpc! @ptr_svc, type: "write", key: "ptr", value: "init"
    end

    @node.on "txn" do |msg|
      @txn_lock.synchronize do
        txn = msg[:body][:txn]
        @node.log "Txn is #{txn}"
        ptr1 = current_ptr
        @node.log "Current pointer is #{ptr1}"
        state = state_of(ptr1)
        @node.log "Current state is #{state}"
        state2, txn2 = apply_txn(state, txn)
        @node.log "Final state is #{state2}"
        if state != state2
          ptr2 = new_ptr
          write_state!(ptr2, state2)
          @node.log "State #{ptr2} written"
          advance_ptr!(ptr1, ptr2)
          @node.log "Pointer advanced from #{ptr1} to #{ptr2}"
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

  # Returns the current pointer.
  def current_ptr
    res = @node.sync_rpc! @ptr_svc, {type: "read", key: "ptr"}
    body = res[:body]
    if body[:type] == "error"
      if body[:code] == 20
        nil
      else
        raise RPCError.abort "Unsure how to handle #{body}"
      end
    else
      body[:value]
    end
  end

  # Bumps the pointer atomically from p1 to p2. Returns iff pointer advanced;
  # throws otherwise.
  def advance_ptr!(p1, p2)
    res = @node.sync_rpc! @ptr_svc, {type: "cas", key: "ptr", from: p1, to: p2}
    if res[:body][:type] == "cas_ok"
      true
    else
      raise RPCError.txn_conflict "pointer no longer #{p1}"
    end
  end

  # Returns the state of the given pointer.
  def state_of(ptr)
    loop do
      res = @node.sync_rpc! @value_svc, {type: "read", key: ptr}
      body = res[:body]
      if body[:type] == "read_ok"
        return body[:value]
      else
        sleep 0.1
        @node.log "Retrying read of state #{ptr}"
      end
    end
  end

  # Writes a state to the AP store under ptr, returning a promise which is true
  # iff the write completed.
  def write_state!(ptr, state)
    res = @node.sync_rpc! @value_svc, {type: "write", key: ptr, value: state}
    body = res[:body]
    if body[:type] == "write_ok"
      true
    else
      raise RPCError.abort "writing state failed"
    end
  end

  # Applies a transaction to the given state, returning [state', completed-txn]
  def apply_txn(state, txn)
    txn2 = []
    state2 = state.clone

    txn.each do |op|
      f, k, v = op
      case f
        when "r"
          txn2 << [f, k, state2[k.to_s]]
        when "append"
          txn2 << op
          list = (state2[k.to_s].clone or [])
          list << v
          state2[k.to_s] = list
        else
          raise "Unrecognized op type #{f}"
      end
    end

    [state2, txn2]
  end

  def main!
    @node.main!
  end
end

DatomicListAppendNode.new.main!
