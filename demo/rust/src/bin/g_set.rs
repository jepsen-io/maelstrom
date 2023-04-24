/// ```bash
/// $ cargo build
/// $ RUST_LOG=debug ~/Projects/maelstrom/maelstrom test -w g-set --bin ./target/debug/g_set --node-count 2 --concurrency 2n --time-limit 20 --rate 10 --log-stderr
/// ```
use async_trait::async_trait;
use log::debug;
use maelstrom::protocol::Message;
use maelstrom::{done, Node, Result, Runtime};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

pub(crate) fn main() -> Result<()> {
    Runtime::init(try_main())
}

async fn try_main() -> Result<()> {
    let runtime = Runtime::new();
    let handler = Arc::new(Handler::default());
    runtime.with_handler(handler).run().await
}

#[derive(Clone, Default)]
struct Handler {
    s: Arc<Mutex<HashSet<i64>>>,
}

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
                            drop(r0.send_async(n, msg));
                        }
                    }
                });
                return Ok(());
            }
            _ => done(runtime, req),
        }
    }
}

fn to_seq(s: &MutexGuard<HashSet<i64>>) -> Vec<i64> {
    s.iter().copied().collect()
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "type")]
enum Request {
    Init {},
    Read {},
    ReadOk { value: Vec<i64> },
    Add { element: i64 },
    AddOk {},
    ReplicateOne { element: i64 },
    ReplicateFull { value: Vec<i64> },
}
