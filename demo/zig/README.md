# maelstrom-zig-node

Zig node framework for building distributed systems for learning for
https://github.com/jepsen-io/maelstrom and solving https://fly.io/dist-sys/
challenges.

## What is Maelstrom?

Maelstrom is a platform for learning distributed systems. It is build around Jepsen and Elle to ensure no properties are
violated. With maelstrom you build nodes that form distributed system that can process different workloads.

## Features

- zig 0.10.1 + mt
- Runtime API
- response types auto-deduction, extra data available via Value()
- unknown message types handling
- a/sync RPC() support + timeout / context
- lin/seq/lww kv storage

## Examples

### Echo workload

```bash
zig build && ~/maelstrom/maelstrom test -w echo --bin ./zig-out/bin/echo --node-count 1 --time-limit 10 --log-stderr
````

implementation:

```zig
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
```

spec:

receiving

```json
{
  "src": "c1",
  "dest": "n1",
  "body": {
    "type": "echo",
    "msg_id": 1,
    "echo": "Please echo 35"
  }
}
```

send back the same msg with body.type == echo_ok.

```json
{
  "src": "n1",
  "dest": "c1",
  "body": {
    "type": "echo_ok",
    "msg_id": 1,
    "in_reply_to": 1,
    "echo": "Please echo 35"
  }
}
```

### Broadcast workload

```sh
zig build && ~/maelstrom/maelstrom test -w broadcast --bin ./zig-out/bin/broadcast --node-count 2 --time-limit 20 --rate 10 --log-stderr
```

implementation:

```zig
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
```

### lin-kv workload

```sh
zig build && ~/maelstrom/maelstrom test -w lin-kv --bin ./zig-out/bin/lin_kv --node-count 4 --concurrency 2n --time-limit 20 --rate 100 --log-stderr
```

implementation:

```zig
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
```

### g-set workload

```sh
zig build && ~/maelstrom/maelstrom test -w g-set --bin ./zig-out/bin/g_set --node-count 2 --concurrency 2n --time-limit 20 --rate 10 --log-stderr
```

implementation:

```zig
...
```

## API

see examples.

## Why

Now its a good time to learn Zig. Zig is beautiful C-like language.
That Will be not perfect but ok. Thanks TigerBeetle for the inspiration.

Thanks Aphyr and guys a lot.

## Where

[GitHub](https://github.com/sitano/maelstrom-zig-node) / Ivan Prisyazhnyi / @JohnKoepi

## Links

- <https://zig.news/xq/zig-build-explained-part-3-1ima>
- <https://zig.news/mattnite/import-and-packages-23mb>
- <https://ziglearn.org/chapter-1/>
- <https://ziglang.org/learn/>
- <https://ziglang.org/learn/samples/>
- <https://ziglang.org/documentation/master/>
