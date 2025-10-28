const std = @import("std");
const m = @import("runtime.zig");
const errors = @import("error.zig");
const proto = @import("protocol.zig");

const Error = errors.HandlerError;

pub const LinKind = "lin-kv";
pub const SeqKind = "seq-kv";
pub const LWWKind = "lww-kv";
pub const TSOKind = "lin-tso";

pub const Storage = struct {
    kind: []const u8,

    pub fn init(kind: []const u8) Storage {
        return Storage{
            .kind = kind,
        };
    }

    pub fn init_lin_kv() Storage {
        return Storage.init(LinKind);
    }

    pub fn init_seq_kv() Storage {
        return Storage.init(SeqKind);
    }

    pub fn init_lww_kv() Storage {
        return Storage.init(LWWKind);
    }

    pub fn init_tso_kv() Storage {
        return Storage.init(TSOKind);
    }

    pub fn get(self: *Storage, runtime: m.ScopedRuntime, key: []const u8, wait_ns: u64) Error!std.json.Value {
        // FIXME: everything is allocated on top of runtime.alloc, so we just skip the defer rpc.deinit();
        //        to assign it to the runtime a' lifetime.
        var rpc = runtime.call(self.kind, .{ .key = key, .typ = "read" });
        var resp = if (wait_ns == 0) rpc.wait() else try rpc.timed_wait(wait_ns);
        const is_err = std.mem.eql(u8, resp.body.typ, "error");
        if (is_err) {
            var err = proto.ErrorMessageBody.init();
            _ = err.from_json(resp.body.raw) catch |err2| {
                std.log.err("[{d}] kv storage/{s} get({s}) from_json error: {}", .{ runtime.worker_id, self.kind, key, err2 });
                return Error.Crash;
            };
            std.log.debug("[{d}] kv storage/{s} get({s}) error: {d}/{s}", .{ runtime.worker_id, self.kind, key, err.code, err.text });
            return errors.to_err(err.code);
        }

        // FIXME: check it is _ok status
        // FIXME: const obj = proto.json_map_obj(struct { value: value_type }, runtime.alloc, resp.body.raw) catch |err2| {
        // FIXME:     std.log.err("[{d}] kv storage/{s} get({s}) json_map_obj error: {} / {}", .{ runtime.worker_id, self.kind, key, err2, resp.body.raw });
        // FIXME:     return Error.Crash;
        // FIXME: };
        // FIXME: return obj.value;

        // if protocol/parse_into_arena/parser/copy_strigns == false, it will assert sometimes with garbage ;D.
        const val = resp.body.raw.Object.get("value");
        if (val == null) {
            std.log.err("[{d}] kv storage/{s} get({s}) protocol error: {}", .{ runtime.worker_id, self.kind, key, resp });
            return Error.Crash;
        }

        return val.?;
    }

    pub fn put(self: *Storage, runtime: m.ScopedRuntime, key: []const u8, value: anytype, wait_ns: u64) Error!void {
        // FIXME: everything is allocated on top of runtime.alloc, so we just skip the defer rpc.deinit();
        //        to assign it to the runtime a' lifetime.
        var rpc = runtime.call(self.kind, .{ .key = key, .value = value, .typ = "write" });
        var resp = if (wait_ns == 0) rpc.wait() else try rpc.timed_wait(wait_ns);
        const is_err = std.mem.eql(u8, resp.body.typ, "error");
        if (is_err) {
            var err = proto.ErrorMessageBody.init();
            _ = err.from_json(resp.body.raw) catch |err2| {
                std.log.err("[{d}] kv storage/{s} put({s}) from_json error: {}", .{ runtime.worker_id, self.kind, key, err2 });
                return Error.Crash;
            };
            std.log.debug("[{d}] kv storage/{s} put({s}) error: {d}/{s}", .{ runtime.worker_id, self.kind, key, err.code, err.text });
            return errors.to_err(err.code);
        }
        // FIXME: check it is _ok status
    }

    pub fn cas(self: *Storage, runtime: m.ScopedRuntime, key: []const u8, from: anytype, to: anytype, putIfAbsent: bool, wait_ns: u64) Error!void {
        // FIXME: everything is allocated on top of runtime.alloc, so we just skip the defer rpc.deinit();
        //        to assign it to the runtime a' lifetime.
        var rpc = runtime.call(self.kind, .{ .key = key, .from = from, .to = to, .put = putIfAbsent, .typ = "cas" });
        var resp = if (wait_ns == 0) rpc.wait() else try rpc.timed_wait(wait_ns);
        const is_err = std.mem.eql(u8, resp.body.typ, "error");
        if (is_err) {
            var err = proto.ErrorMessageBody.init();
            _ = err.from_json(resp.body.raw) catch |err2| {
                std.log.err("[{d}] kv storage/{s} put({s}) from_json error: {}", .{ runtime.worker_id, self.kind, key, err2 });
                return Error.Crash;
            };
            std.log.debug("[{d}] kv storage/{s} put({s}) error: {d}/{s}", .{ runtime.worker_id, self.kind, key, err.code, err.text });
            return errors.to_err(err.code);
        }
        // FIXME: check it is _ok status
    }
};
