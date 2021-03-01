class RPCError < StandardError
  class << self
    def timeout(msg);           new 0,  msg; end

    def not_supported(msg);             new 10, msg; end
    def temporarily_unavailable(msg);   new 11, msg; end
    def malformed_request(msg);         new 12, msg; end
    def crash(msg);                     new 13, msg; end
    def abort(msg);                     new 14, msg; end

    def key_does_not_exist(msg);  new 20, msg; end
    def key_already_exists(msg);  new 21, msg; end
    def precondition_failed(msg); new 22, msg; end

    def txn_conflict(msg);        new 30, msg; end
  end

  attr_reader :code

  def initialize(code, text)
    @code = code
    @text = text
    super(text)
  end

  # Constructs a JSON error response
  def to_json
    {type: "error",
     code: @code,
     text: @text}
  end
end
