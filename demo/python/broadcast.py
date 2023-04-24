#!/usr/bin/env python3

import asyncio
from maelstrom import Node, Body, Request

node = Node()

messages: set[int] = set()
cond = asyncio.Condition()


async def add_messages(values: set[int]) -> None:
    if values <= messages:
        return
    messages.update(values)
    async with cond:
        cond.notify_all()


@node.handler
async def broadcast(req: Request) -> Body:
    msg = req.body["message"]
    await add_messages({msg})
    return {"type": "broadcast_ok"}


@node.handler
async def broadcast_many(req: Request) -> Body:
    msgs = req.body["messages"]
    await add_messages(set(msgs))
    return {"type": "broadcast_many_ok"}


@node.handler
async def read(req: Request) -> Body:
    return {"type": "read_ok", "messages": list(messages)}


@node.handler
async def topology(req: Request) -> Body:
    neighbors = req.body["topology"][node.node_id]
    for n in neighbors:
        node.spawn(gossip_task(n))
    return {"type": "topology_ok"}


async def gossip_task(neighbor: str) -> None:
    sent = set()
    while True:
        # assert sent <= messages
        if len(sent) == len(messages):
            async with cond:
                # Wait for the next update to our message set.
                await cond.wait_for(lambda: len(sent) != len(messages))

        to_send = messages - sent
        body = {"type": "broadcast_many", "messages": list(to_send)}
        resp = await node.rpc(neighbor, body)
        if resp["type"] == "broadcast_many_ok":
            sent.update(to_send)


node.run()
