require_relative "errors.rb"

class Promise
  WAITING = Object.new
  TIMEOUT = 5

  def initialize
    @lock = Mutex.new
    @cvar = ConditionVariable.new
    @value = WAITING
  end

  # Blocks this thread until a value is delivered, then returns it.
  def await
    if @value != WAITING
      return @value
    end

    # Not ready yet; block
    @lock.synchronize do
      @cvar.wait @lock, TIMEOUT
    end

    if @value != WAITING
      return @value
    else
      raise RPCError.timeout "promise timed out"
    end
  end

  # Delivers value. We don't check for double-delivery--this is a super
  # bare-bones implementation and we're trying to minimize code!
  def deliver!(value)
    @value = value
    # Not sure if we actually have to hold the mutex here. The cvar docs are...
    # vague.
    @lock.synchronize do
      @cvar.broadcast
    end
    self
  end
end
