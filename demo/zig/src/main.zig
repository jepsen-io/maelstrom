// implementation is based on zig 0.10.1.

const builtin = @import("builtin");
const std = @import("std");

pub const log = @import("log.zig");

const runtime = @import("runtime.zig");
pub const Runtime = runtime.Runtime;
pub const ScopedRuntime = runtime.ScopedRuntime;
pub const RPCRequest = runtime.Request;

pub const proto = @import("protocol.zig");
pub const Message = proto.Message;
pub const MessageBody = proto.MessageBody;

pub const errors = @import("error.zig");
pub const Error = errors.HandlerError;

pub const kv = @import("kv.zig");

// we did like to use async io but it does not work at least until 0.12.0.
// see protocol format() issues and mutex+async_print+darwin.
//
// to tell runtime we want async io define the following in root ns:
//     pub const io_mode = .evented; // auto deducted, or
//     var global_instance_state: std.event.Loop = undefined;
//     pub const event_loop: *std.event.Loop = &global_instance_state;
//
// async io also requires enabling stage1 compiler (-fstage1).
const _ = if (std.io.is_async) @panic("io is async, but is broken, thus unsupported at least until 0.12.0. sorry.") else void;

pub fn init() !*Runtime {
    return Runtime.init();
}
