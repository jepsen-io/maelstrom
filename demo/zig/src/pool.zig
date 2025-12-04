const std = @import("std");
const queue = @import("wait_job_queue.zig");

// can't store anyframe cause zig does not support async without -fstage1.
pub const Job = struct {
  arena: std.heap.ArenaAllocator,
  req: []u8,
};

pub const Pool = struct {
  alloc: std.mem.Allocator,
  threads: []std.Thread,
  queue: queue.WaitJobQueue(Job),

  pub const JobNode = queue.WaitJobQueue(Job).Node;

  pub fn init(alloc: std.mem.Allocator, size: usize) !Pool {
      return Pool{
        .alloc = alloc,
        .threads = try alloc.alloc(std.Thread, size),
        .queue = queue.WaitJobQueue(Job).init(),
      };
  }

  pub fn start(self: *Pool, worker: anytype, args: anytype) !void {
      var i: usize = 0;
      while (i < self.threads.len) : (i += 1) {
        // FIXME: cleanup on errdefer
        self.threads[i] = try std.Thread.spawn(.{}, worker, args);
      }
  }

  // frame allocated on alloc that we will deinit later.
  pub fn enqueue(self: *Pool, alloc0: std.mem.Allocator, req: []const u8) !void {
    var arena = std.heap.ArenaAllocator.init(alloc0);
    var alloc = arena.allocator();

    var node = try alloc.create(JobNode);

    node.data = Job{
      .arena = arena,
      .req = try alloc.dupe(u8, req),
    };

    self.queue.put(node);
  }

  pub fn deinit(self: *Pool) void {
    // signal shutdown
    self.queue.shutdown();

    // wait threads to exit
    for (self.threads) |t| {
        t.join();
    }

    // purge queue
    while (!self.queue.isEmpty()) {
        self.queue.get().?.data.arena.deinit();
    }

    // cleanup
    self.alloc.free(self.threads);
  }
};