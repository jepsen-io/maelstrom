const std = @import("std");
const root = @import("root");

pub const Message = struct {
    src: []const u8,
    dest: []const u8,

    body: MessageBody,

    pub usingnamespace MessageMethods(@This());
};

pub const MessageBody = struct {
    typ: []const u8,
    msg_id: u64,
    in_reply_to: u64,

    raw: std.json.Value,

    pub usingnamespace FormatAsJson(@This());
    pub usingnamespace MessageBodyMethods(@This());
};

pub const InitMessageBody = struct {
    node_id: []const u8,
    node_ids: [][]const u8,

    pub usingnamespace FormatAsJson(@This());
};

pub const ErrorMessageBody = struct {
    // .typ fields are handled by to_json_value.
    typ: []const u8,
    code: i64,
    text: []const u8,

    pub usingnamespace FormatAsJson(@This());
    pub usingnamespace ErrorMessageMethods(@This());
};

// buf must be allocated on arena. we would not clean or copy it.
pub fn parse_message(alloc: std.mem.Allocator, buf: []u8) !*Message {
    return Message.parse_into_arena(alloc, buf);
}

fn MessageMethods(comptime Self: type) type {
    return struct {
        // buf must be allocated on arena. we would not clean or copy it.
        pub fn parse_into_arena(alloc: std.mem.Allocator, buf: []u8) !*Message {
            // FIXME: with copy_strings == false, some rpc resp reads fail after
            //        being passed over the threads boundary. wut???.
            //
            //        runtime.rpc:
            //
            //        if (proto.parse_message(item.arena.allocator(), node.data.req)) |resp|
            //           item.set_completed(resp);
            //
            //        test: zig build && ~/maelstrom/maelstrom test -w lin-kv --bin ./zig-out/bin/lin_kv --node-count 4 --concurrency 2n --time-limit 20 --rate 100 --log-stderr
            var parser = std.json.Parser.init(alloc, true);
            defer parser.deinit();

            var tree = try parser.parse(buf);
            // we hope for arena allocator instead of defer tree.deinit();

            var m = try alloc.create(Message);
            m.* = Message.init();

            return try m.from_json(tree.root);
        }

        pub fn from_json(self: *Self, src0: ?std.json.Value) !*Message {
            if (src0 == null) return self;
            const src = src0.?;

            self.src = try_json_string(src.Object.get("src"));
            self.dest = try_json_string(src.Object.get("dest"));
            _ = try self.body.from_json(src.Object.get("body"));

            return self;
        }

        pub fn init() Message {
            return Message{
                .src = "",
                .dest = "",
                .body = MessageBody.init(),
            };
        }

        pub fn format(value: Self, comptime fmt: []const u8, options: std.fmt.FormatOptions, writer: anytype) !void {
            try writer.writeAll("{");

            var i: u32 = 0;
            if (value.src.len > 0) {
                try writer.writeAll(" \"src\": \"");
                try writer.writeAll(value.src);
                try writer.writeAll("\"");
                i += 1;
            }

            if (value.dest.len > 0) {
                if (i > 0) try writer.writeAll(",");
                try writer.writeAll(" \"dest\": \"");
                try writer.writeAll(value.dest);
                try writer.writeAll("\"");
            }

            if (i > 0) try writer.writeAll(",");
            try writer.writeAll(" \"body\": ");
            try value.body.format(fmt, options, writer);

            try writer.writeAll(" }");
        }

        pub fn to_json_value(self: Self, alloc: std.mem.Allocator) !std.json.Value {
            var v = std.json.Value{ .Object = std.json.ObjectMap.init(alloc) };
            try v.Object.put("src", std.json.Value{ .String = self.src });
            try v.Object.put("dest", std.json.Value{ .String = self.dest });
            try v.Object.put("body", try self.body.to_json_value(alloc));
            return v;
        }
    };
}

fn MessageBodyMethods(comptime Self: type) type {
    return struct {
        pub fn init() MessageBody {
            return MessageBody{
                .typ = "",
                .msg_id = 0,
                .in_reply_to = 0,
                .raw = .Null,
            };
        }

        pub fn from_json(self: *Self, src0: ?std.json.Value) !*MessageBody {
            if (src0 == null) return self;
            const src = src0.?;

            self.typ = try_json_string(src.Object.get("type"));
            self.msg_id = try_json_u64(src.Object.get("msg_id"));
            self.in_reply_to = try_json_u64(src.Object.get("in_reply_to"));
            self.raw = src;

            _ = self.raw.Object.swapRemove("type");
            _ = self.raw.Object.swapRemove("msg_id");
            _ = self.raw.Object.swapRemove("in_reply_to");

            return self;
        }

        pub fn to_json_value(self: Self, alloc: std.mem.Allocator) !std.json.Value {
            var v = std.json.Value{ .Object = std.json.ObjectMap.init(alloc) };

            if (self.typ.len > 0) try v.Object.put("type", std.json.Value{ .String = self.typ });
            if (self.msg_id > 0) try v.Object.put("msg_id", std.json.Value{ .Integer = @intCast(i64, self.msg_id) });
            if (self.in_reply_to > 0) try v.Object.put("in_reply_to", std.json.Value{ .Integer = @intCast(i64, self.in_reply_to) });

            try merge_json(&v, self.raw);

            return v;
        }
    };
}

fn ErrorMessageMethods(comptime Self: type) type {
    return struct {
        pub fn init() ErrorMessageBody {
            return ErrorMessageBody{
                .typ = "error",
                .code = 0,
                .text = "",
            };
        }

        pub fn from_json(self: *Self, src0: ?std.json.Value) !*ErrorMessageBody {
            if (src0 == null) return self;
            const src = src0.?;

            self.typ = try_json_string(src.Object.get("type"));
            self.code = try_json_i64(src.Object.get("code"));
            self.text = try_json_string(src.Object.get("text"));

            return self;
        }
    };
}

fn FormatAsJson(comptime Self: type) type {
    return struct {
        pub fn format(value: Self, comptime fmt: []const u8, options: std.fmt.FormatOptions, writer: anytype) !void {
            _ = fmt;
            _ = options;
            // FIXME: this does not work in async IO until this fixed: https://github.com/ziglang/zig/issues/4060.
            try nosuspend std.json.stringify(value, .{}, writer);
        }
    };
}

fn try_json_string(val: ?std.json.Value) []const u8 {
    if (val == null) return "";
    switch (val.?) {
        .String => |s| return s,
        else => return "",
    }
}

fn try_json_u64(val: ?std.json.Value) u64 {
    if (val == null) return 0;
    switch (val.?) {
        .Integer => |s| return @intCast(u64, s),
        else => return 0,
    }
}

fn try_json_i64(val: ?std.json.Value) i64 {
    if (val == null) return 0;
    switch (val.?) {
        .Integer => |s| return @intCast(i64, s),
        else => return 0,
    }
}

const MergeJsonError = error{InvalidType};

/// merges src object into dst object.
pub fn merge_json(dst: *std.json.Value, src: std.json.Value) !void {
    switch (dst.*) {
        .Object => {},
        else => {
            return MergeJsonError.InvalidType;
        },
    }

    switch (src) {
        .Object => |inner| {
            var it = inner.iterator();
            while (it.next()) |entry| {
                try dst.Object.put(entry.key_ptr.*, entry.value_ptr.*);
            }
        },
        else => {},
    }
}

/// all allocated memory belongs to the caller.
/// FIXME: double serialization is not efficient, but we want to be simple right now.
/// until the std lib api will be able to handle it.
/// support override with to_json_value(Allocator).
pub fn to_json_value(alloc: std.mem.Allocator, value: anytype) !std.json.Value {
    const T = @TypeOf(value);
    const args_type_info = @typeInfo(T);

    if (args_type_info == .Pointer) {
        return to_json_value(alloc, value.*);
    }

    if (T == std.json.Value) {
        return value;
    }

    if (args_type_info != .Struct) {
        @compileError("expected tuple or struct argument, found " ++ @typeName(T));
    }

    if (comptime std.meta.trait.hasFn("to_json_value")(T)) {
        return try value.to_json_value(alloc);
    }

    const str = try std.json.stringifyAlloc(alloc, value, .{});

    var parser = std.json.Parser.init(alloc, false);
    defer parser.deinit();

    var obj = try parser.parse(str);

    // FIXME: I am sorry for this hack. But std.json.Parser creates temporary arena allocator
    //        that becomes dead after it is coming out of scope of parser.Parse() scope. So any other
    //        calls to the objects that require allocation in tree.alocc are causing segfaults.
    //        Also, thanks std.json.Parser.transition is private and we can't workaround that.
    //
    //        Without that we are having:
    //
    //            /usr/lib/zig/std/heap/arena_allocator.zig:67:42: 0x2a0c5f in allocImpl (echo)
    //                    const cur_buf = cur_node.data[@sizeOf(BufNode)..];
    //                                                 ^
    //            /usr/lib/zig/std/mem/Allocator.zig:154:34: 0x291014 in allocAdvancedWithRetAddr__anon_8869 (echo)
    //                return self.vtable.alloc(self.ptr, len, ptr_align, len_align, ret_addr);
    //
    //        At the moment, this is very dirty hack, but works while alloc is an whole scope
    //        arena.
    //
    //        This is fixed in main, see
    //        https://github.com/ziglang/zig/commit/b42caff2a20eb34073f6a766f55d27288028165a.
    //
    //        But we want to support at least 0.10.0.
    var t = obj.root;
    t.Object.allocator = alloc;

    // Now we must transform all .typ fields into .type fields, as Zig don't have tags.
    try rename_typ_field(&t);

    return t;
}

/// renames "typ" fields into "type" fields.
/// FIXME: does storage modification invalidates running iterator? probably yes.
fn rename_typ_field(o: *std.json.Value) !void {
    switch (o.*) {
        .Object => |inner| {
            var it = inner.iterator();
            while (it.next()) |entry| {
                if (std.mem.eql(u8, entry.key_ptr.*, "typ")) {
                    try o.Object.put("type", entry.value_ptr.*);
                    _ = o.Object.swapRemove("typ");
                    continue;
                }

                try rename_typ_field(entry.value_ptr);
            }
        },
        else => {},
    }
}

/// merges a set of objects into a json Value.
/// all allocated memory belongs to the caller.
///
///     merge_to_json(arena, .{body, .{ .code = 10, .text = "not supported" }});
pub fn merge_to_json(alloc: std.mem.Allocator, args: anytype) !std.json.Value {
    const ArgsType = @TypeOf(args);
    const args_type_info = @typeInfo(ArgsType);

    if (ArgsType == std.json.Value) {
        return args;
    }

    if (args_type_info != .Struct) {
        @compileError("expected tuple or struct argument, found " ++ @typeName(ArgsType));
    }

    if (!args_type_info.Struct.is_tuple) {
        return to_json_value(alloc, args);
    }

    const fields_info = args_type_info.Struct.fields;
    const len = comptime fields_info.len;

    if (len == 0) {
        return std.json.Value{ .Object = std.json.ObjectMap.init(alloc) };
    }

    comptime var i: usize = 0;
    var obj = try to_json_value(alloc, @field(args, fields_info[i].name));

    i += 1;
    inline while (i < len) {
        var src = try to_json_value(alloc, @field(args, fields_info[i].name));
        try merge_json(&obj, src);
        i += 1;
    }

    return obj;
}

/// maps object src json representation into the target Object.
/// FIXME: non efficient, but works.
pub fn json_map_obj(comptime T: type, alloc: std.mem.Allocator, src: anytype) !T {
    const srcObj = try to_json_value(alloc, src);
    const srcStr = try std.json.stringifyAlloc(alloc, srcObj, .{});
    var stream = std.json.TokenStream.init(srcStr);
    return try std.json.parse(T, &stream, .{
        .allocator = alloc,
        .ignore_unknown_fields = true,
    });
}

test "to_json(.{})" {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();

    {
        var obj = try to_json_value(arena.allocator(), .{});
        switch (obj) {
            .Object => {},
            else => try std.testing.expect(false),
        }

        var str = try std.json.stringifyAlloc(arena.allocator(), obj, .{});
        try std.testing.expectEqualSlices(u8, "{}", str);
    }

    {
        const obj = std.json.Value{
            .Object = std.json.ObjectMap.init(arena.allocator()),
        };
        var res = try to_json_value(arena.allocator(), obj);
        try std.testing.expectEqual(obj, res);
    }
}

test "merge_to_json works" {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();

    var alloc = arena.allocator();

    var body = MessageBody.init();

    {
        var dst = try to_json_value(alloc, &body);
        const str = try std.json.stringifyAlloc(alloc, dst, .{});
        try std.testing.expectEqualStrings("{}", str);
    }

    {
        // TODO: merge_json must ignore merging Nulls
        // var dst = try to_json_value(alloc, body);
        // const obj = try merge_json(&dst, std.json.Value{ .Null = void{} });
        // const str = try std.json.stringifyAlloc(alloc, obj, .{});
        // try std.testing.expectEqualStrings("{}", str);
    }

    body.msg_id = 1;
    body.in_reply_to = 2;
    body.typ = "init";
    body.raw = std.json.Value{ .Object = std.json.ObjectMap.init(alloc) };
    try body.raw.Object.put("raw_key", std.json.Value{ .Integer = 1 });

    {
        const obj = try to_json_value(alloc, body);
        const str = try std.json.stringifyAlloc(alloc, obj, .{});
        try std.testing.expectEqualStrings("{\"type\":\"init\",\"msg_id\":1,\"in_reply_to\":2,\"raw_key\":1}", str);
    }

    {
        const obj = try merge_to_json(alloc, .{ body, .{ .msg_id = 2, .text = "bbb" } });
        const str = try std.json.stringifyAlloc(alloc, obj, .{});
        try std.testing.expectEqualStrings("{\"type\":\"init\",\"msg_id\":2,\"in_reply_to\":2,\"raw_key\":1,\"text\":\"bbb\"}", str);
    }
}
