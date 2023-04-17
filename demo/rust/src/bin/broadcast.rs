/// ```bash
/// $ cargo build
/// $ RUST_LOG=debug maelstrom test -w broadcast --bin ./target/debug/broadcast --node-count 2 --time-limit 20 --rate 10 --log-stderr
/// ````
use async_trait::async_trait;
use log::info;
use maelstrom::protocol::Message;
use maelstrom::{done, Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
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
    inner: Arc<Mutex<Inner>>,
}

#[derive(Clone, Default)]
struct Inner {
    s: HashSet<u64>,
    t: Vec<String>,
}

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

impl Handler {
    fn snapshot(&self) -> Vec<u64> {
        self.inner.lock().unwrap().s.iter().copied().collect()
    }

    fn try_add(&self, val: u64) -> bool {
        let mut g = self.inner.lock().unwrap();
        if !g.s.contains(&val) {
            g.s.insert(val);
            return true;
        }
        false
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "type")]
enum Request {
    Init {},
    Read {},
    ReadOk {
        messages: Vec<u64>,
    },
    Broadcast {
        message: u64,
    },
    Topology {
        topology: HashMap<String, Vec<String>>,
    },
}
