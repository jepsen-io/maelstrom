const builtin = @import("builtin");
const root = @import("root");
const std = @import("std");

// pub const log = maelstrom.log.f;
// pub const log_level : std.log.Level = .debug;

pub const f = log;

pub fn log(
    comptime message_level: std.log.Level,
    comptime scope: @Type(.EnumLiteral),
    comptime format: []const u8,
    args: anytype,
) void {
    if (builtin.os.tag == .freestanding)
        @compileError(
            \\freestanding targets do not have I/O configured;
            \\please provide at least an empty `log` function declaration
        );

    const ts = std.time.nanoTimestamp();
    const ns = @intCast(u64, @mod(ts, 1000000));
    const ms = @intCast(u64, @mod(@divTrunc(ts, 1000000), 1000));
    const ss = @divTrunc(ts, 1000000000);

    const level_txt = " " ++ comptime message_level.asText();
    const prefix2 = " " ++ if (scope == .default) "default" else @tagName(scope);

    const stderr = std.io.getStdErr().writer();

    std.debug.getStderrMutex().lock();
    defer std.debug.getStderrMutex().unlock();

    nosuspend stderr.print("[{} {d:0>3}.{d:0>6}" ++ level_txt ++ prefix2 ++ "] " ++ format ++ "\n", .{ ss, ms, ns } ++ args) catch return;
}
