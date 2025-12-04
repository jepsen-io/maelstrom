// zig build && ~/maelstrom/maelstrom test -w broadcast --bin ./zig-out/bin/broadcast --node-count 2 --time-limit 20 --rate 10 --log-stderr

const m = @import("maelstrom");
const std = @import("std");

pub const log = m.log.f;
pub const log_level = .debug;

var storage: *Storage = undefined;

pub fn main() !void {
    var runtime = try m.init();
    storage = try Storage.init(runtime.alloc);
    try runtime.handle("read", read);
    try runtime.handle("broadcast", broadcast);
    try runtime.handle("topology", topology);
    try runtime.run();
}

fn read(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    self.reply(req, ReadOk{
        .messages = storage.snapshot(self.alloc) catch return m.Error.Abort,
    });
}

fn broadcast(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    const in = m.proto.json_map_obj(Broadcast, self.alloc, req.body) catch return m.Error.MalformedRequest;

    if (storage.add(in.message) catch return m.Error.Abort) {
        var ns = self.neighbours();
        while (ns.next()) |node| {
            self.send(node, .{
                .typ = "broadcast",
                .message = in.message,
            });
        }
    }

    if (!self.is_cluster_node(req.src)) {
        self.reply_ok(req);
    }
}

fn topology(self: m.ScopedRuntime, req: *m.Message) m.Error!void {
    // FIXME: oops sorry, compiler bug:
    //     panic: Zig compiler bug: attempted to destroy declaration with an attached error
    // const in = try m.proto.json_map_obj(Topology, self.alloc, req.body);
    const data = req.body.raw.Object.get("topology");
    if (data == null) return m.Error.MalformedRequest;
    std.log.info("got new topology: {s}", .{std.json.stringifyAlloc(self.alloc, data, .{}) catch return m.Error.Abort});
    self.reply_ok(req);
}

const ReadOk = struct {
    messages: []u64,
};

const Broadcast = struct {
    message: u64,
};

const Topology = struct {
    topology: std.StringHashMap(std.ArrayList([]const u8)),
};

const Storage = struct {
    a: std.mem.Allocator,
    m: std.Thread.Mutex,
    s: std.AutoArrayHashMap(u64, bool),

    pub fn init(alloc: std.mem.Allocator) !*Storage {
        var res = try alloc.create(Storage);
        res.* = Storage{
            .a = alloc,
            .m = std.Thread.Mutex{},
            .s = std.AutoArrayHashMap(u64, bool).init(alloc),
        };
        return res;
    }

    pub fn add(self: *Storage, val: u64) !bool {
        self.m.lock();
        defer self.m.unlock();
        var res = try self.s.getOrPut(val);
        res.value_ptr.* = true;
        return !res.found_existing;
    }

    pub fn snapshot(self: *Storage, alloc: std.mem.Allocator) ![]u64 {
        self.m.lock();
        defer self.m.unlock();
        var res = try alloc.alloc(u64, self.s.count());
        var i: usize = 0;
        var it = self.s.iterator();
        while (it.next()) |item| {
            res[i] = item.key_ptr.*;
            i += 1;
        }
        return res;
    }
};
