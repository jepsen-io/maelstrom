/// ```bash
/// $ cargo build
/// $ RUST_LOG=debug maelstrom test -w broadcast --bin ./target/debug/broadcast --node-count 2 --time-limit 20 --rate 10 --log-stderr
/// ````
use async_trait::async_trait;
use log::info;
use maelstrom::protocol::{Message, MessageBody};
use maelstrom::{done, rpc, Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

pub(crate) fn main() -> Result<()> {
    Runtime::init(try_main())
}

async fn try_main() -> Result<()> {
    let handler = Arc::new(Handler::default());
    Runtime::new().with_handler(handler).run().await
}

#[derive(Clone, Default)]
struct Handler {
    inner: Arc<Mutex<Vec<u64>>>,
}

#[async_trait]
impl Node for Handler {
    async fn process(&self, runtime: Runtime, req: Message) -> Result<()> {
        match req.get_type() {
            "read" => {
                let data = self.snapshot();
                let msg = ReadResponse { messages: data };
                return runtime.reply(req, msg).await;
            }
            "broadcast" => {
                let msg = BroadcastRequest::from_message(&req.body)?;

                self.add(msg.value);

                if !runtime.is_from_cluster(&req.src) {
                    for node in runtime.neighbours() {
                        runtime.spawn(rpc(runtime.clone(), node.clone(), msg.clone()));
                    }
                }

                return runtime.reply_ok(req).await;
            }
            "topology" => {
                info!("new topology {:?}", req.body.extra.get("topology").unwrap());
                return runtime.reply_ok(req).await;
            }
            _ => done(runtime, req),
        }
    }
}

impl Handler {
    fn snapshot(&self) -> Vec<u64> {
        self.inner.lock().unwrap().clone()
    }

    fn add(&self, val: u64) {
        self.inner.lock().unwrap().push(val);
    }
}

#[derive(Serialize, Deserialize, Clone)]
struct BroadcastRequest {
    #[serde(default, rename = "type")]
    typ: String,
    #[serde(default, rename = "message")]
    value: u64,
}

#[derive(Serialize)]
struct ReadResponse {
    messages: Vec<u64>,
}

impl BroadcastRequest {
    fn from_message(m: &MessageBody) -> Result<Self> {
        let mut msg = m.as_obj::<BroadcastRequest>()?;
        msg.typ = m.typ.clone();
        Ok(msg)
    }
}
