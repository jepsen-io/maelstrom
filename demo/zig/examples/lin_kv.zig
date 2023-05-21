// zig build && ~/maelstrom/maelstrom test -w lin-kv --bin ./zig-out/bin/lin_kv --node-count 4 --concurrency 2n --time-limit 20 --rate 100 --log-stderr

const m = @import("maelstrom");
const std = @import("std");

pub const log = m.log.f;
pub const log_level = .debug;

var kv: m.kv.Storage = m.kv.Storage.init_lin_kv();

pub fn main() !void {
    var runtime = try m.init();
    try runtime.handle("read", read);
    try runtime.handle("write", write);
    try runtime.handle("cas", cas);
    try runtime.run();
}

fn read(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    const in = m.proto.json_map_obj(Read, self.alloc, req.body) catch return m.Error.MalformedRequest;
    const key = std.fmt.allocPrint(self.alloc, "{}", .{in.key}) catch return m.Error.Crash;
    const val = try kv.get(self, key, 0);

    self.reply(req, ReadOk{
        .value = val.Integer,
    });
}

fn write(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    const in = m.proto.json_map_obj(Write, self.alloc, req.body) catch return m.Error.MalformedRequest;
    const key = std.fmt.allocPrint(self.alloc, "{}", .{in.key}) catch return m.Error.Crash;

    try kv.put(self, key, in.value, 0);

    self.reply_ok(req);
}

fn cas(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    const in = m.proto.json_map_obj(Cas, self.alloc, req.body) catch return m.Error.MalformedRequest;
    const key = std.fmt.allocPrint(self.alloc, "{}", .{in.key}) catch return m.Error.Crash;
    const put_var = req.body.raw.Object.get("put");
    const put = if (put_var) |v| v.Bool else false;

    try kv.cas(self, key, in.from, in.to, put, 0);

    self.reply_ok(req);
}

const Read = struct {
    key: u64,
};

const ReadOk = struct {
    value: i64,
};

const Write = struct {
    key: u64,
    value: i64,
};

const Cas = struct {
    key: u64,
    from: i64,
    to: i64,
    // optional: put: bool,
};
