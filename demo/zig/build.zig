const std = @import("std");

const pkgs = struct {
    const maelstrom = std.build.Pkg{
        .name = "maelstrom",
        .source = .{ .path = "src/main.zig" },
        .dependencies = &[_]std.build.Pkg{},
    };
};

pub fn build_lib(b: *std.build.Builder) void {
    // Standard release options allow the person running `zig build` to select
    // between Debug, ReleaseSafe, ReleaseFast, and ReleaseSmall.
    const mode = b.standardReleaseOptions();

    const lib = b.addStaticLibrary("maelstrom-zig-node", "src/main.zig");
    lib.setBuildMode(mode);
    lib.install();

    const main_tests = b.addTest("src/main.zig");
    main_tests.setBuildMode(mode);

    const test_step = b.step("test", "Run library tests");
    test_step.dependOn(&main_tests.step);
}

pub fn build_example(b: *std.build.Builder, target: anytype, name: []const u8) void {
    // Standard release options allow the person running `zig build` to select
    // between Debug, ReleaseSafe, ReleaseFast, and ReleaseSmall.
    const mode = b.standardReleaseOptions();

    const path = std.fmt.allocPrint(b.allocator, "examples/{s}.zig", .{name}) catch {
        @panic("out of memory");
    };
    defer b.allocator.free(path);

    const exe = b.addExecutable(name, path);
    exe.addPackage(pkgs.maelstrom);
    exe.setTarget(target);
    exe.setBuildMode(mode);
    exe.install();

    const run_cmd = exe.run();
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| {
        run_cmd.addArgs(args);
    }

    const run_step = b.step("run", "Run the app");
    run_step.dependOn(&run_cmd.step);

    const exe_tests = b.addTest(path);
    exe_tests.setTarget(target);
    exe_tests.setBuildMode(mode);

    const test_step = b.step("test", "Run unit tests");
    test_step.dependOn(&exe_tests.step);
}

pub fn build(b: *std.build.Builder) void {
    // usr/lib/zig/std/event/loop.zig:16:39: error: async has not been
    // implemented in the self-hosted compiler yet
    //
    // /usr/lib/zig/std/event/loop.zig:16:39: note: to use async enable the
    // stage1 compiler with either '-fstage1' or by setting '.use_stage1 = true`
    // in your 'build.zig' script
    //
    // no needed any more: b.use_stage1 = true;

    build_lib(b);

    // Standard target options allows the person running `zig build` to choose
    // what target to build for. Here we do not override the defaults, which
    // means any target is allowed, and the default is native. Other options
    // for restricting supported target set are available.
    const target = b.standardTargetOptions(.{});

    build_example(b, target, "echo");
    build_example(b, target, "broadcast");
    build_example(b, target, "broadcast_rpc_sync");
    build_example(b, target, "broadcast_rpc_async");
    build_example(b, target, "lin_kv");
}
