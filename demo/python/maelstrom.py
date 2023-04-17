import asyncio
import copy
import json
import sys
from dataclasses import dataclass
from enum import IntEnum
from typing import Any, Awaitable, Callable, Optional, TypeAlias


Body: TypeAlias = dict[str, Any]


@dataclass
class Request:
    src: str  # A string identifying the node this message came from
    dest: str  # A string identifying the node this message is to
    body: Body  # An object: the payload of the message


class Error(IntEnum):
    """Numeric error codes returned by Maelstrom in messages of type "error".
    See <https://github.com/jepsen-io/maelstrom/blob/main/doc/protocol.md> for
    documentation on the protocol and a table of error codes.
    """

    TIMEOUT = 0
    NODE_NOT_FOUND = 1
    NOT_SUPPORTED = 10
    TEMPORARILY_UNAVAILABLE = 11
    MALFORMED_REQUEST = 12
    CRASH = 13
    ABORT = 14
    KEY_DOES_NOT_EXIST = 20
    KEY_ALREADY_EXISTS = 21
    PRECONDITION_FAILED = 22
    TXN_CONFLICT = 30

    def is_definite(self) -> bool:
        """Returns whether an error code is definite.
        Errors are either definite or indefinite. A definite error means that
        the requested operation definitely did not (and never will) happen. An
        indefinite error means that the operation might have happened, or might
        never happen, or might happen at some later time. Maelstrom uses this
        information to interpret histories correctly, so it's important that
        you never return a definite error under indefinite conditions.
        When in doubt, indefinite is always safe. Custom error codes are always
        indefinite.
        """
        return self not in [Error.TIMEOUT, Error.CRASH]


class Node:
    """An application node within the distributed system."""

    _handlers: dict[str, Callable[[Request], Awaitable[Body]]]  # RPC handlers
    _next_id: int  # Monotonically increasing identifier
    _stdout_lock: asyncio.Lock  # Lock on stdout
    _stderr_lock: asyncio.Lock  # Lock on stderr
    _tasks: set  # Background tasks
    _reply_handlers: dict[int, asyncio.Future]

    node_id: str  # Read-only, received from the init message.
    node_ids: list[str]  # Read-only, received from the init message.

    def __init__(self) -> None:
        self._handlers = {}
        self._next_id = 1
        self._stdout_lock = asyncio.Lock()
        self._stderr_lock = asyncio.Lock()
        self._tasks = set()
        self._reply_handlers = {}

    def handler(self, f: Callable[[Request], Awaitable[Body]]):
        """Decorator used to wrap a function that handles requests."""
        self._handlers[f.__name__] = f
        return f

    async def rpc(self, dest: str, body: Body) -> Body:
        """Make an RPC call, blocking until a response is received."""
        msg_id = body["msg_id"] = self._next_id
        self._next_id += 1
        fut = asyncio.get_running_loop().create_future()
        self._reply_handlers[msg_id] = fut

        req = Request(self.node_id, dest, body)
        await self._send(req)

        try:
            async with asyncio.timeout(1.0):  # Timeout RPCs after 1 second.
                return await fut
        except TimeoutError:
            await self.log(f"[node] request timed out: {json.dumps(body)}")
            return {
                "type": "error",
                "in_reply_to": msg_id,
                "code": Error.TIMEOUT,
                "text": "RPC request timed out",
            }
        finally:
            self._reply_handlers.pop(msg_id, None)

    async def log(self, *values: Any) -> None:
        """Print a message to the standard error log."""
        async with self._stderr_lock:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(
                None, lambda: print(*values, flush=True, file=sys.stderr)
            )

    def spawn(self, coro: Awaitable[None]) -> asyncio.Task:
        """Spawn a background task on the node's executor."""
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _recv(self) -> Request:
        loop = asyncio.get_running_loop()
        line = (await loop.run_in_executor(None, sys.stdin.readline)).strip()
        if not line:
            raise EOFError()
        data = json.loads(line)
        return Request(data["src"], data["dest"], data["body"])

    async def _send(self, req: Request) -> None:
        serialized = json.dumps({"src": req.src, "dest": req.dest, "body": req.body})
        async with self._stdout_lock:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, lambda: print(serialized, flush=True))

    async def _run(self, init: Optional[Callable[[], None]]) -> None:
        req = await self._recv()
        assert req.body["type"] == "init"  # first message should be init
        self.node_id = req.body["node_id"]
        self.node_ids = req.body["node_ids"]
        resp_body = {"type": "init_ok", "in_reply_to": req.body["msg_id"]}
        await self._send(Request(self.node_id, req.src, resp_body))

        if init is not None:
            init()

        while True:
            try:
                req = await self._recv()
            except EOFError:
                await self.log("[node] finishing execution")
                for t in self._tasks:
                    t.cancel()
                return  # exiting node

            if req.body.get("in_reply_to"):
                reply_id = req.body["in_reply_to"]
                fut = self._reply_handlers.pop(reply_id, None)
                # If there's no handler, this might be a duplicate message--we'll quietly
                # ignore it.
                if fut is not None:
                    try:
                        fut.set_result(req.body)
                    except asyncio.InvalidStateError:
                        pass
                continue

            async def thunk(req: Request):
                message_type = req.body["type"]
                if message_type in self._handlers:
                    callback = self._handlers[message_type]
                    resp_body = await callback(copy.deepcopy(req))
                else:
                    resp_body = {
                        "type": "error",
                        "code": Error.NOT_SUPPORTED,
                        "text": "RPC type is not supported",
                    }
                resp_body["in_reply_to"] = req.body["msg_id"]
                await self._send(Request(self.node_id, req.src, resp_body))

            self.spawn(thunk(req))

    def run(self, init: Optional[Callable[[], None]] = None) -> None:
        """Run the node, optionally with a function on startup."""
        asyncio.run(self._run(init))
