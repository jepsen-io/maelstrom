# maelstrom-rust-node

Yet another one Rust crate for implementing nodes for https://github.com/jepsen-io/maelstrom and solve
https://fly.io/dist-sys/ challenges.

# Examples

## Echo workload

```bash
$ cargo build --examples
$ maelstrom test -w echo --bin ./target/debug/examples/echo --node-count 1 --time-limit 10 --log-stderr
````

implementation:

[echo.rs](./src/bin/echo.rs)

spec:

receiving

    {
      "src": "c1",
      "dest": "n1",
      "body": {
        "type": "echo",
        "msg_id": 1,
        "echo": "Please echo 35"
      }
    }

send back the same msg with body.type == echo_ok.

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

## Broadcast workload

```bash
$ cargo build --examples
$ RUST_LOG=debug maelstrom test -w broadcast --bin ./target/debug/examples/broadcast --node-count 2 --time-limit 20 --rate 10 --log-stderr
````

implementation:

```bash
#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        let msg: Result<Request> = req.body.as_obj();
        match msg {
            Ok(Request::Read {}) => {
                let data = self.snapshot();
                let msg = Request::ReadOk { messages: data };
                return runtime.reply(req, msg).await;
            }
            Ok(Request::Broadcast { message: element }) => {
                if self.try_add(element) {
                    info!("messages now {}", element);
                    for node in runtime.neighbours() {
                        runtime.call_async(node, Request::Broadcast { message: element });
                    }
                }

                return runtime.reply_ok(req).await;
            }
            Ok(Request::Topology { topology }) => {
                let neighbours = topology.get(runtime.node_id()).unwrap();
                self.inner.lock().unwrap().t = neighbours.clone();
                info!("My neighbors are {:?}", neighbours);
                return runtime.reply_ok(req).await;
            }
            _ => done(runtime, req),
        }
    }
}
```

## lin-kv workload

```bash
$ cargo build --examples
$ RUST_LOG=debug ~/Projects/maelstrom/maelstrom test -w lin-kv --bin ./target/debug/examples/lin_kv --node-count 4 --concurrency 2n --time-limit 10 --rate 100 --log-stderr
````

implementation:

```rust
#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        let (ctx, _handler) = Context::new();
        let msg: Result<Request> = req.body.as_obj();
        match msg {
            Ok(Request::Read { key }) => {
                let value = self.s.get(ctx, key.to_string()).await?;
                return runtime.reply(req, Request::ReadOk { value }).await;
            }
            Ok(Request::Write { key, value }) => {
                self.s.put(ctx, key.to_string(), value).await?;
                return runtime.reply(req, Request::WriteOk {}).await;
            }
            Ok(Request::Cas { key, from, to, put }) => {
                self.s.cas(ctx, key.to_string(), from, to, put).await?;
                return runtime.reply(req, Request::CasOk {}).await;
            }
            _ => done(runtime, req),
        }
    }
}

fn handler(runtime: Runtime) -> Handler {
    Handler { s: lin_kv(runtime) }
}
```

## g-set workload

```bash
$ cargo build --examples
$ RUST_LOG=debug ~/Projects/maelstrom/maelstrom test -w g-set --bin ./target/debug/examples/g_set --node-count 2 --concurrency 2n --time-limit 20 --rate 10 --log-stderr
```

implementation:

```rust
#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        let msg: Result<Request> = req.body.as_obj();
        match msg {
            Ok(Request::Read {}) => {
                let data = to_seq(&self.s.lock().unwrap());
                return runtime.reply(req, Request::ReadOk { value: data }).await;
            }
            Ok(Request::Add { element }) => {
                self.s.lock().unwrap().insert(element);
                return runtime.reply(req, Request::AddOk {}).await;
            }
            Ok(Request::ReplicateOne { element }) => {
                self.s.lock().unwrap().insert(element);
                return Ok(());
            }
            Ok(Request::ReplicateFull { value }) => {
                let mut s = self.s.lock().unwrap();
                for v in value {
                    s.insert(v);
                }
                return Ok(());
            }
            Ok(Request::Init {}) => {
                // spawn into tokio (instead of runtime) to not to wait
                // until it is completed, as it will never be.
                let (r0, h0) = (runtime.clone(), self.clone());
                tokio::spawn(async move {
                    loop {
                        tokio::time::sleep(Duration::from_secs(5)).await;
                        debug!("emit replication signal");
                        let s = h0.s.lock().unwrap();
                        for n in r0.neighbours() {
                            let msg = Request::ReplicateFull { value: to_seq(&s) };
                            drop(r0.send_async(n.to_string(), msg));
                        }
                    }
                });
                return Ok(());
            }
            _ => done(runtime, req),
        }
    }
}
```

# API

## Key-Value storage

```rust
use async_trait::async_trait;
use maelstrom::kv::{lin_kv, Storage, KV};
use maelstrom::protocol::Message;
use maelstrom::{done, Node, Result, Runtime};
use tokio_context::context::Context;

#[derive(Clone)]
struct Handler {
    s: Storage,
}

#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        let (ctx, _handler) = Context::new();
        let msg: Result<Request> = req.body.as_obj();
        match msg {
            Ok(Request::Read { key }) => {
                let value = self.s.get(ctx, key.to_string()).await?;
                return runtime.reply(req, Request::ReadOk { value }).await;
            }
            Ok(Request::Write { key, value }) => {
                self.s.put(ctx, key.to_string(), value).await?;
                return runtime.reply(req, Request::WriteOk {}).await;
            }
            Ok(Request::Cas { key, from, to, put }) => {
                self.s.cas(ctx, key.to_string(), from, to, put).await?;
                return runtime.reply(req, Request::CasOk {}).await;
            }
            _ => done(runtime, req),
        }
    }
}

fn handler(runtime: Runtime) -> Handler {
    Handler { s: lin_kv(runtime) }
}
```

## RPC

```rust
use async_trait::async_trait;
use maelstrom::protocol::Message;
use maelstrom::{Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
struct Handler {}

#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        let (mut ctx, _handler) = Context::with_timeout(Duration::from_secs(1));

        // 1.
        runtime.call_async(node, msg.clone());

        // 2. put it into runtime.spawn(async move { ... }) if needed
        let res: RPCResult = runtime.rpc(node, msg.clone()).await?;
        let msg: Result<Message> = res.await;

        // 3. put it into runtime.spawn(async move { ... }) if needed
        let mut res: RPCResult = runtime.rpc(node, msg.clone()).await?;
        let msg: Message = res.done_with(ctx).await?;

        // 4. put it into runtime.spawn(async move { ... }) if needed
        let msg = runtime.call(ctx, node, msg.clone()).await?;

        // 5. async send variant
        //    spawn into tokio (instead of runtime) to not to wait
        //    until it is completed, as it will never be.
        let (r0, h0) = (runtime.clone(), self.clone());
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                debug!("emit replication signal");
                let s = h0.s.lock().unwrap();
                for n in r0.neighbours() {
                    let msg = Request::ReplicateFull { value: to_seq(&s) };
                    drop(r0.send_async(n, msg));
                }
            }
        });

        return runtime.reply_ok(req).await;
    }
}
```

## Requests

```rust
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
struct TopologyRequest {
    topology: HashMap<String, Vec<String>>,
}

// or

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "type")]
pub enum Message {
    Topology {
        topology: HashMap<String, Vec<String>>,
    },
    Broadcast {
        message: u64,
    },
    ReadOk {
        messages: Vec<u64>,
    },
}

```

## Responses

```rust
use async_trait::async_trait;
use log::info;
use maelstrom::protocol::Message;
use maelstrom::{done, Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
struct Handler { /* ... */ }

#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        if req.get_type() == "echo" {
            let echo = req.body.clone().with_type("echo_ok");
            return runtime.reply(req, echo).await;
        }

        if req.get_type() == "echo" {
            let echo = format!("Another echo {}", message.body.msg_id);
            let msg = Value::Object(Map::from_iter([("echo".to_string(), Value::String(echo))]));
            return runtime.reply(message, msg).await;
        }

        if req.get_type() == "echo" {
            let err = maelstrom::Error::TemporarilyUnavailable {};
            let body = ErrorMessageBody::from_error(err);
            return runtime.reply(message, body).await;
        }

        if req.get_type() == "echo" {
            let body = MessageBody::default().with_type("echo_ok").with_reply_to(req.body.msg_id);
            // send: no response type auto-deduction and no reply_to
            return runtime.send(message, body).await;
        }

        if req.get_type() == "echo" {
            return runtime.reply(message, EchoResponse { echo: "blah".to_string() }).await;
        }

        if req.get_type() == "read" {
            let data = self.inner.lock().unwrap().clone();
            let msg = ReadResponse { messages: data };
            return runtime.reply(req, msg).await;
        }

        if req.get_type() == "broadcast" {
            let raw = Value::Object(req.body.extra.clone());

            let mut msg = serde_json::from_value::<BroadcastRequest>(raw)?;
            msg.typ = req.body.typ.clone();

            return runtime.reply(req, msg).await;
        }

        if req.get_type() == "broadcast" {
            let mut msg = serde_json::from_value::<BroadcastRequest>(req.body.raw())?;
            msg.typ = req.body.typ.clone();

            return runtime.reply(req, msg).await;
        }

        if req.get_type() == "broadcast" {
            let mut msg = req.body.as_obj::<BroadcastRequest>()?;
            msg.typ = req.body.typ.clone();

            return runtime.reply(req, msg).await;
        }

        if req.get_type() == "topology" {
            info!("new topology {:?}", req.body.extra.get("topology").unwrap());
            return runtime.reply_ok(req).await;
        }

        done(runtime, message)
    }
}
```
