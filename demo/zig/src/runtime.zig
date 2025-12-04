const builtin = @import("builtin");
const errors = @import("error.zig");
const pool = @import("pool.zig");
const proto = @import("protocol.zig");
const root = @import("root");
const rpcpkg = @import("rpc.zig");
const std = @import("std");

const Message = proto.Message;
const ErrorMessageBody = proto.ErrorMessageBody;
const HandlerError = errors.HandlerError;
const EmptyStringArray = [0][]const u8{};

pub const thread_safe: bool = !builtin.single_threaded;
pub const MutexType: type = @TypeOf(if (thread_safe) std.Thread.Mutex{} else DummyMutex{});

// FIXME: what we can do about those? - move to the heap with BufReader.
pub const read_buf_size = if (@hasDecl(root, "read_buf_size")) root.read_buf_size else 4096;

pub const Handler = fn (ScopedRuntime, *Message) HandlerError!void;
pub const HandlerPtr = *const Handler;
pub const HandlerMap = std.StringHashMap(HandlerPtr);

pub const RPCRequest = rpcpkg.Request;
const RPCRuntime = rpcpkg.Runtime;

pub const Runtime = struct {
    gpa: ?std.heap.GeneralPurposeAllocator(.{}),
    // thread-safe by itself
    alloc: std.mem.Allocator,

    out: std.fs.File,

    pool: pool.Pool,
    // log: TODO: @TypeOf(Scoped)

    handlers: HandlerMap,
    rpc_runtime: RPCRuntime,

    // init state
    m: MutexType,
    node_id: []const u8,
    nodes: [][]const u8,

    pub fn init() !*Runtime {
        return initWithAllocator(null);
    }

    // alloc is expected to be thread-safe by itself.
    pub fn initWithAllocator(alloc: ?std.mem.Allocator) !*Runtime {
        var runtime: *Runtime = undefined;

        if (alloc) |a| {
            runtime = try a.create(Runtime);
            runtime.gpa = null;
            runtime.alloc = a;
        } else {
            var gpa = std.heap.GeneralPurposeAllocator(.{}){};

            runtime = try gpa.allocator().create(Runtime);
            runtime.gpa = gpa;
            runtime.alloc = runtime.gpa.?.allocator();
        }

        runtime.out = std.io.getStdOut();
        runtime.pool = try pool.Pool.init(runtime.alloc, @max(2, @min(4, try std.Thread.getCpuCount())));
        runtime.handlers = HandlerMap.init(runtime.alloc);
        runtime.rpc_runtime = try RPCRuntime.init(runtime.alloc);
        runtime.m = MutexType{};
        runtime.node_id = "";
        runtime.nodes = &EmptyStringArray;

        return runtime;
    }

    pub fn deinit(self: *Runtime) void {
        self.pool.deinit();
        self.handlers.deinit();
        self.rpc_runtime.deinit();
    }

    /// handle(type, f) registers a handler for specific message type.
    pub fn handle(self: *Runtime, msg_type: []const u8, f: HandlerPtr) !void {
        if (self.handlers.contains(msg_type)) {
            std.debug.panic("this message type is already registered: {s}", .{msg_type});
        }

        try self.handlers.put(msg_type, f);
    }

    pub fn send_raw_f(self: *Runtime, comptime fmt: []const u8, args: anytype) void {
        if (comptime std.io.is_async) {
            @panic("async IO in unsupported at least until 0.12.0. we need sync stdout. see the comment below.");
        }

        const out = self.out.writer();

        defer std.log.debug("Sent " ++ fmt, args);

        const m = std.debug.getStderrMutex();
        m.lock();
        defer m.unlock();
        // stdout.writer().print suspends in async io mode.
        // on Darwin a suspend point in the middle of mutex causes for 0.10.1:
        //     Illegal instruction at address 0x7ff80f6c1efc
        //     ???:?:?: 0x7ff80f6c1efc in ??? (???)
        //     zig/0.10.1/lib/zig/std/Thread/Mutex.zig:115:40: 0x10f60dd84 in std.Thread.Mutex.DarwinImpl.unlock (echo)
        //     os.darwin.os_unfair_lock_unlock(&self.oul);
        //
        // FIXME: check if it works with 0.12.0 + darwin when its ready.
        nosuspend out.print(fmt ++ "\n", args) catch return;
    }

    pub fn send_raw(self: *Runtime, msg: []const u8) void {
        self.send_raw_f("{s}", .{msg});
    }

    // msg must support special treatment for arrays and messagebody flattening.
    // does not support non-struct and non array kinds.
    //
    //    runtime.send("n1", msg);
    //    runtime.send("n1", .{req.body, msg}) - merges objects.
    pub fn send(self: *Runtime, alloc: std.mem.Allocator, to: []const u8, msg: anytype) !void {
        const body = try proto.merge_to_json(alloc, msg);

        var packet = proto.Message{
            .src = self.node_id,
            .dest = to,
            .body = proto.MessageBody.init(),
        };

        packet.body.raw = body;

        var obj = try proto.to_json_value(alloc, packet);
        const str = try std.json.stringifyAlloc(alloc, obj, .{});

        self.send_raw(str);

        if (self.node_id.len == 0) {
            std.log.warn("Responding to {s} with {s} without having src address. Missed <init> message?", .{ to, str });
        }
    }

    pub fn send_back(self: *Runtime, alloc0: std.mem.Allocator, req: *Message, msg: anytype) !void {
        try self.send(alloc0, req.src, msg);
    }

    pub fn reply(self: *Runtime, alloc: std.mem.Allocator, req: *Message, msg: anytype) !void {
        var obj = try proto.merge_to_json(alloc, msg);

        try obj.Object.put("in_reply_to", std.json.Value{
            .Integer = @intCast(i64, req.body.msg_id),
        });

        if (!obj.Object.contains("type")) {
            try obj.Object.put("type", std.json.Value{
                .String = try std.fmt.allocPrint(alloc, "{s}_ok", .{req.body.typ}),
            });
        }

        try self.send(alloc, req.src, obj);
    }

    pub fn reply_err(self: *Runtime, alloc: std.mem.Allocator, req: *Message, resp: HandlerError) !void {
        var obj = errors.to_message(resp);
        if (resp == HandlerError.NotSupported) {
            obj.text = try std.fmt.allocPrint(alloc, "not supported: {s}", .{req.body.typ});
        }
        try self.reply(alloc, req, obj);
    }

    pub fn reply_custom_err(self: *Runtime, alloc: std.mem.Allocator, req: *Message, code: i64, text: []const u8) !void {
        var obj = errors.to_message(HandlerError.Other);
        obj.code = code;
        obj.text = text;
        try self.reply(alloc, req, obj);
    }

    pub fn reply_ok(self: *Runtime, alloc: std.mem.Allocator, req: *Message) !void {
        var resp = std.json.Value{
            .Object = std.json.ObjectMap.init(alloc),
        };
        var typ = req.body.typ;

        if (!std.mem.endsWith(u8, typ, "_ok")) {
            typ = try std.fmt.allocPrint(alloc, "{s}_ok", .{typ});
        }

        try self.reply(alloc, req, resp);
    }

    pub fn send_back_ok(self: *Runtime, alloc: std.mem.Allocator, req: *Message) !void {
        var resp = std.json.Value{
            .Object = std.json.ObjectMap.init(alloc),
        };
        var typ = req.body.typ;

        if (!std.mem.endsWith(u8, typ, "_ok")) {
            typ = try std.fmt.allocPrint(alloc, "{s}_ok", .{typ});
        }

        try resp.Object.put("type", std.json.Value{
            .String = typ,
        });

        try self.reply(alloc, req, .{ req.body, resp });
    }

    /// rpc() makes an RPC call to another node.
    /// if Request is async, the Runtime shall execute a message handler,
    /// to process the request. Otherwise, it expects the waiters to be notified.
    pub fn rpc(self: *Runtime, is_async: bool, to: []const u8, msg: anytype) !*RPCRequest {
        var req = try self.rpc_runtime.new_req(is_async);
        var alloc = req.arena.allocator();
        var obj = try proto.merge_to_json(alloc, msg);

        if (!is_async) {
            req.add_ref();
        }
        errdefer {
            if (!is_async) {
                req.deinit();
            }
        }

        if (!try self.rpc_runtime.add(req)) {
            // should not ever happen
            return error.TryAgain;
        }

        errdefer {
            self.rpc_runtime.remove(req.msg_id);
            req.deinit();
        }

        try self.send(alloc, to, .{
            proto.MessageBody{ .typ = "", .msg_id = req.msg_id, .in_reply_to = 0, .raw = obj },
        });

        return req;
    }

    /// call() makes a sync RPC call to another node. use .wait() or .timed_wait().
    pub fn call(self: *Runtime, to: []const u8, msg: anytype) !*RPCRequest {
        return try self.rpc(false, to, msg);
    }

    /// call_async() makes an async RPC call to another node.
    pub fn call_async(self: *Runtime, to: []const u8, msg: anytype) !u64 {
        return try self.rpc(true, to, msg).msg_id;
    }

    // in: std.io.Reader.{}
    // read buffer is 4kB.
    pub fn listen(self: *Runtime, in: anytype) !void {
        var buffer: [read_buf_size]u8 = undefined;

        while (nextLine(in, &buffer)) |try_line| {
            if (try_line == null) return;
            const line = try_line.?;

            if (line.len == 0) continue;
            std.log.debug("Received {s}", .{line});

            try self.pool.enqueue(self.alloc, line);
        } else |err| {
            return err;
        }
    }

    pub fn run(self: *Runtime) !void {
        try self.pool.start(worker, .{self});

        std.log.info("node started.", .{});

        self.listen(std.io.getStdIn().reader()) catch |e| {
            std.log.err("listen loop error: {}", .{e});
        };

        // finish workers before printing finish.
        self.deinit();

        std.log.info("node finished.", .{});
    }

    pub fn worker(self: *Runtime) void {
        const id = std.Thread.getCurrentId();

        std.log.debug("[{d}] worker started.", .{id});

        while (self.pool.queue.get()) |node| {
            self.process_request_node(node);
        }

        std.log.debug("[{d}] worker finished.", .{id});
    }

    fn process_request_node(self: *Runtime, node: *pool.Pool.JobNode) void {
        defer node.data.arena.deinit();

        const id = std.Thread.getCurrentId();

        // std.log.debug("[{d}] worker: got an item: {s}", .{ id, node.data.req });

        if (proto.parse_message(node.data.arena.allocator(), node.data.req)) |req| {
            var scoped = ScopedRuntime.init(self, node, id);

            const rpc_request = self.rpc_runtime.poll_request(req.body.in_reply_to);
            // defer is scoped, so deinit defer here.
            defer if (rpc_request) |item| {
                item.deinit();
            };
            if (rpc_request) |item| {
                // FIXME: any ideas how to move the whole tree to another arena?
                //        we can't just pass the req with local lifetime (node.data.arena a') to the external lifetime b'.
                if (proto.parse_message(item.arena.allocator(), node.data.req)) |resp| {
                    item.set_completed(resp);
                } else |err| {
                    // we can't continue from here safely. probably we could, but this is experimental project.
                    std.debug.panic("[{d}] rpc response parsing error {s}: {}", .{ id, node.data.req, err });
                }

                if (!item.is_async) {
                    return;
                }
            }

            const is_err = std.mem.eql(u8, req.body.typ, "error");
            if (is_err) {
                // if it is an error response to rpc, we can't do anything about it.
                std.log.err("[{d}] got error response {s}", .{ id, node.data.req });
                return;
            }

            const is_init = std.mem.eql(u8, req.body.typ, "init");
            if (is_init) {
                process_init_message(&scoped, req) catch |err| {
                    std.log.err("[{d}] processing init message error {s}: {}", .{ id, node.data.req, err });
                    scoped.reply_err(req, HandlerError.MalformedRequest);
                    return;
                };
            }

            if (self.handlers.get(req.body.typ)) |f| {
                f(scoped, req) catch |err| scoped.reply_err(req, err);
            } else if (!is_init) {
                const is_ok = std.mem.endsWith(u8, req.body.typ, "_ok");
                // if the msg is not an init and is not an ok response from rpc than yeah.
                if (!is_ok) {
                    scoped.reply_err(req, HandlerError.NotSupported);
                }
                return;
            }

            if (is_init) {
                scoped.reply_ok(req);
            }
        } else |err| {
            std.log.err("[{d}] incoming message parsing error {s}: {}", .{ id, node.data.req, err });
        }
    }

    fn process_init_message(self: *ScopedRuntime, req: *Message) !void {
        const in = try proto.json_map_obj(proto.InitMessageBody, self.alloc, req.body);

        const node_id = try self.runtime.alloc.dupe(u8, in.node_id);
        var node_ids = try self.runtime.alloc.alloc([]const u8, in.node_ids.len);
        var i: usize = 0;
        while (i < node_ids.len) {
            node_ids[i] = try self.runtime.alloc.dupe(u8, in.node_ids[i]);
            i += 1;
        }

        self.runtime.m.lock();
        defer self.runtime.m.unlock();

        self.runtime.node_id = node_id;
        self.runtime.nodes = node_ids;

        self.node_id = node_id;
        self.nodes = node_ids;

        std.log.info("new cluster state: node_id = {s}, nodes = {s}", .{ node_id, node_ids });
    }

    pub fn neighbours(self: *Runtime) NeighbourIterator {
        return NeighbourIterator{
            .node_id = self.node_id,
            .nodes = self.nodes,
            .len = self.nodes.len,
        };
    }
};

/// Proxy class for the context of (runtime, allocator scoped to the current request).
pub const ScopedRuntime = struct {
    runtime: *Runtime,
    alloc: std.mem.Allocator,
    worker_id: usize,

    node_id: []const u8,
    nodes: [][]const u8,

    pub fn init(runtime: *Runtime, node: *pool.Pool.JobNode, worker_id: usize) ScopedRuntime {
        return ScopedRuntime{
            .runtime = runtime,
            .worker_id = worker_id,
            .alloc = node.data.arena.allocator(),
            .node_id = runtime.node_id,
            .nodes = runtime.nodes,
        };
    }

    pub inline fn send_raw_f(self: ScopedRuntime, comptime fmt: []const u8, args: anytype) void {
        self.runtime.send_raw_f(fmt, args);
    }

    pub inline fn send_raw(self: ScopedRuntime, msg: []const u8) void {
        self.runtime.send_raw(msg);
    }

    // msg must support special treatment for arrays and messagebody flattening.
    // does not support non-struct and non array kinds.
    //
    //    runtime.send("n1", msg);
    //    runtime.send("n1", .{req.body, msg}) - merges objects.
    pub inline fn send(self: ScopedRuntime, to: []const u8, msg: anytype) void {
        self.runtime.send(self.alloc, to, msg) catch |err| {
            std.log.err("[{d}] sending {} to {s} error: {}", .{ self.worker_id, msg, to, err });
            self.runtime.send(self.alloc, to, errors.to_message(HandlerError.Crash)) catch |err2| {
                std.debug.panic("[{d}] sending error Crash error: {}", .{ self.worker_id, err2 });
            };
        };
    }

    pub inline fn send_back(self: ScopedRuntime, req: *Message, msg: anytype) void {
        self.runtime.send_back(self.alloc, req, msg) catch |err| {
            std.log.err("[{d}] sending back {} on {s} error: {}", .{ self.worker_id, msg, req, err });
            self.runtime.reply_err(self.alloc, req, HandlerError.Crash);
        };
    }

    pub inline fn reply(self: ScopedRuntime, req: *Message, msg: anytype) void {
        self.runtime.reply(self.alloc, req, msg) catch |err| {
            std.log.err("[{d}] responding with {} to {s} error: {}", .{ self.worker_id, msg, req, err });
            self.reply_err(req, HandlerError.Crash);
        };
    }

    pub inline fn reply_err(self: ScopedRuntime, req: *Message, resp: HandlerError) void {
        self.runtime.reply_err(self.alloc, req, resp) catch |err| {
            std.debug.panic("[{d}] responding with error {} error {s}: {}", .{ self.worker_id, resp, req, err });
        };
    }

    pub fn reply_custom_err(self: *Runtime, req: *Message, code: i64, text: []const u8) void {
        self.runtime.reply_custom_err(req, code, text) catch |err| {
            std.debug.panic("[{d}] responding with custom error {d}:{s} error {s}: {}", .{ self.worker_id, code, text, req, err });
        };
    }

    pub inline fn reply_ok(self: ScopedRuntime, req: *Message) void {
        self.runtime.reply_ok(self.alloc, req) catch |err| {
            std.debug.panic("[{d}] responding with ok error {s}: {}", .{ self.worker_id, req, err });
        };
    }

    pub inline fn send_back_ok(self: ScopedRuntime, req: *Message) void {
        self.runtime.send_back_ok(self.alloc, req) catch |err| {
            std.debug.panic("[{d}] responding with ok error {s}: {}", .{ self.worker_id, req, err });
        };
    }

    /// rpc() makes an RPC call to another node.
    /// if Request is async, the Runtime shall execute a message handler,
    /// to process the request. Otherwise, it expects the waiters to be notified.
    pub fn rpc(self: ScopedRuntime, is_async: bool, to: []const u8, msg: anytype) *RPCRequest {
        var r = self.runtime.rpc(is_async, to, msg) catch |err| {
            std.debug.panic("[{d}] emitting an rpc call error {}: {}", .{ self.worker_id, msg, err });
        };
        return r;
    }

    /// call() makes a sync RPC call to another node. use .wait() or .timed_wait().
    pub fn call(self: ScopedRuntime, to: []const u8, msg: anytype) *RPCRequest {
        return self.rpc(false, to, msg);
    }

    /// call_async() makes an async RPC call to another node.
    pub fn call_async(self: ScopedRuntime, to: []const u8, msg: anytype) u64 {
        return self.rpc(true, to, msg).msg_id;
    }

    pub fn neighbours(self: ScopedRuntime) NeighbourIterator {
        return NeighbourIterator{
            .node_id = self.node_id,
            .nodes = self.nodes,
            .len = self.nodes.len,
        };
    }

    pub fn is_cluster_node(_: ScopedRuntime, src: []const u8) bool {
        return src.len > 0 and src[0] == 'n';
    }
};

pub const NeighbourIterator = struct {
    node_id: []const u8,
    nodes: [][]const u8,
    len: usize,
    index: usize = 0,

    pub fn next(it: *NeighbourIterator) ?[]const u8 {
        if (it.index >= it.len) return null;
        if (std.mem.eql(u8, it.nodes[it.index], it.node_id)) {
            it.index += 1;
            if (it.index >= it.len) return null;
        }
        it.index += 1;
        return it.nodes[it.index - 1];
    }

    /// Reset the iterator to the initial index
    pub fn reset(it: *NeighbourIterator) void {
        it.index = 0;
    }
};

const DummyMutex = struct {
    fn lock(_: *DummyMutex) void {}
    fn unlock(_: *DummyMutex) void {}
};

// reader: std.io.Reader.{}
fn nextLine(reader: anytype, buffer: []u8) !?[]const u8 {
    var line = (try reader.readUntilDelimiterOrEof(buffer, '\n')) orelse return null;
    // trim annoying windows-only carriage return character
    if (@import("builtin").os.tag == .windows) {
        return std.mem.trimRight(u8, line, "\r");
    } else {
        return line;
    }
}
