const std = @import("std");
const proto = @import("protocol.zig");

/// [source](https://github.com/jepsen-io/maelstrom/blob/main/doc/protocol.md#errors).
pub const HandlerError = error{
    /// Indicates that the requested operation could not be completed within a timeout.
    Timeout,
    /// Use this error to indicate that a requested operation is not supported by
    /// the current implementation. Helpful for stubbing out APIs during development.
    NotSupported,
    /// Indicates that the operation definitely cannot be performed at this time--perhaps
    /// because the server is in a read-only state, has not yet been initialized,
    /// believes its peers to be down, and so on. Do not use this error for indeterminate
    /// cases, when the operation may actually have taken place.
    TemporarilyUnavailable,
    /// The client's request did not conform to the server's expectations,
    /// and could not possibly have been processed.
    MalformedRequest,
    /// Indicates that some kind of general, indefinite error occurred.
    /// Use this as a catch-all for errors you can't otherwise categorize,
    /// or as a starting point for your error handler: it's safe to return
    /// internal-error for every problem by default, then add special cases
    /// for more specific errors later.
    Crash,
    /// Indicates that some kind of general, definite error occurred.
    /// Use this as a catch-all for errors you can't otherwise categorize,
    /// when you specifically know that the requested operation has not taken place.
    /// For instance, you might encounter an indefinite failure during
    /// the prepare phase of a transaction: since you haven't started the commit process yet,
    /// the transaction can't have taken place. It's therefore safe to return
    /// a definite abort to the client.
    Abort,
    /// The client requested an operation on a key which does not exist
    /// (assuming the operation should not automatically create missing keys).
    KeyDoesNotExist,
    /// The client requested the creation of a key which already exists,
    /// and the server will not overwrite it.
    KeyAlreadyExists,
    /// The requested operation expected some conditions to hold,
    /// and those conditions were not met. For instance, a compare-and-set operation
    /// might assert that the value of a key is currently 5; if the value is 3,
    /// the server would return precondition-failed.
    PreconditionFailed,
    /// The requested transaction has been aborted because of a conflict with
    /// another transaction. Servers need not return this error on every conflict:
    /// they may choose to retry automatically instead.
    TxnConflict,
    /// General error for anything you would like to add.
    Other,
};

pub fn to_code(err: HandlerError) i64 {
    return switch (err) {
        error.Timeout => 0,
        error.NotSupported => 10,
        error.TemporarilyUnavailable => 11,
        error.MalformedRequest => 12,
        error.Crash => 13,
        error.Abort => 14,
        error.KeyDoesNotExist => 20,
        error.KeyAlreadyExists => 21,
        error.PreconditionFailed => 22,
        error.TxnConflict => 30,
        error.Other => 1000,
    };
}

pub fn to_err(code: i64) HandlerError {
    return switch (code) {
        0 => error.Timeout,
        10 => error.NotSupported,
        11 => error.TemporarilyUnavailable,
        12 => error.MalformedRequest,
        13 => error.Crash,
        14 => error.Abort,
        20 => error.KeyDoesNotExist,
        21 => error.KeyAlreadyExists,
        22 => error.PreconditionFailed,
        30 => error.TxnConflict,
        else => error.Other,
    };
}

pub fn to_text(err: HandlerError) []const u8 {
    return switch (err) {
        error.Timeout => "timeout",
        error.NotSupported => "not supported",
        error.TemporarilyUnavailable => "temporarily unavailable",
        error.MalformedRequest => "malformed request",
        error.Crash => "crash",
        error.Abort => "abort",
        error.KeyDoesNotExist => "key does not exist",
        error.KeyAlreadyExists => "key already exists",
        error.PreconditionFailed => "precondition failed",
        error.TxnConflict => "txn conflict",
        error.Other => "user-level error kind",
    };
}

pub fn to_message(err: HandlerError) proto.ErrorMessageBody {
    return proto.ErrorMessageBody{
        .typ = "error",
        .code = to_code(err),
        .text = to_text(err),
    };
}

test "error mapping works" {
    try std.testing.expect(to_code(HandlerError.NotSupported) == 10);
    try std.testing.expect(std.mem.eql(u8, to_text(HandlerError.NotSupported), "not supported"));
    try std.testing.expect(to_message(HandlerError.NotSupported).code == 10);
}
