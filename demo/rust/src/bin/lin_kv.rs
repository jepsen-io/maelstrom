/// ```bash
/// $ cargo build
/// $ RUST_LOG=debug ~/Projects/maelstrom/maelstrom test -w lin-kv --bin ./target/debug/lin_kv --node-count 4 --concurrency 2n --time-limit 20 --rate 100 --log-stderr
/// ````
use async_trait::async_trait;
use maelstrom::kv::{lin_kv, Storage, KV};
use maelstrom::protocol::Message;
use maelstrom::{done, Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio_context::context::Context;

pub(crate) fn main() -> Result<()> {
    Runtime::init(try_main())
}

async fn try_main() -> Result<()> {
    let runtime = Runtime::new();
    let handler = Arc::new(handler(runtime.clone()));
    runtime.with_handler(handler).run().await
}

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

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "type")]
enum Request {
    Read {
        key: u64,
    },
    ReadOk {
        value: i64,
    },
    Write {
        key: u64,
        value: i64,
    },
    WriteOk {},
    Cas {
        key: u64,
        from: i64,
        to: i64,
        #[serde(default, rename = "create_if_not_exists")]
        put: bool,
    },
    CasOk {},
}
