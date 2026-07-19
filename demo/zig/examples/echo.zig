// zig build && ~/maelstrom/maelstrom test -w echo --bin ./zig-out/bin/echo --node-count 1 --time-limit 10 --log-stderr

const m = @import("maelstrom");
const std = @import("std");

pub const log = m.log.f;
pub const log_level = .debug;

pub fn main() !void {
    var runtime = try m.Runtime.init();
    try runtime.handle("echo", echo);
    try runtime.run();
}

fn echo(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    self.send_back_ok(req);
}
