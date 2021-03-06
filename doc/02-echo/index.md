# A Simple Echo Server

In this chapter, we'll write a basic echo server in Ruby, using Maelstrom as a
test harness. An echo server accepts messages from clients with some payload,
and replies to that client with the same payload.

This chapter uses the Ruby programming language. Your package manager probably
has Ruby packages, but if not, see [the Ruby language
website](https://www.ruby-lang.org/en/documentation/installation/). You should
be able to run

```
ruby -v
ruby 2.7.2p137 (2020-10-01 revision 5445e04352) [x86_64-linux-gnu]
```

You can also follow along in the language of your choice--any language that has
a JSON parser should work. :-)

## Starting Up

Maelstrom works with any kind of binary, feeding it network messages on
stdin, receiving network messages from stdout, and logging information on
stderr. The binary we're going to write is a Ruby script, so open up a new
file in your favorite editor--let's call it `echo.rb`:

```rb
#!/usr/bin/env ruby

class EchoServer
  def main!
    while line = STDIN.gets
      STDERR.puts "Received #{line.inspect}"
    end
  end
end

EchoServer.new.main!
```

This is a small program which simply loops over lines from stdin, printing out
each one to stderr as it's received. We print to stderr because that's where Maelstrom debugging information goes--stdout is reserved for network messages.

We'll have to make that script executable, so we can run it:

```
$ chmod +x echo.rb
$ ./echo.rb
```

Now we can type lines of text into the server, and confirm that they're being
received correctly.

```
Hello, server!
Received "Hello, server!\n"
How are you?
Received "How are you?\n"
```

Let's try this server out with Maelstrom. We'll tell Maelstrom we'd like to
test an `echo` server, that our binary is called `echo.rb`, that it should run
one instance of that server on a node called `n1`, that we'd like to run the
test for ten seconds, and that we'd like to see stderr log messages in the Maelstrom logs.

```
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10 --log-stderr
...
INFO [2021-02-07 16:33:15,155] jepsen node n1 - maelstrom.db Setting up n1
INFO [2021-02-07 16:33:15,156] jepsen node n1 - maelstrom.process launching demo/echo/echo_demo.rb nil
INFO [2021-02-07 16:33:15,244] node n1 - maelstrom.process Received "{\"dest\":\"n1\",\"body\":{\"type\":\"init\",\"node_id\":\"n1\",\"node_ids\":[\"n1\"],\"msg_id\":1},\"src\":\"c1\"}\n"
INFO [2021-02-07 16:33:25,169] jepsen node n1 - maelstrom.db Tearing down n1
WARN [2021-02-07 16:33:25,180] main - jepsen.core Test crashed!
java.lang.RuntimeException: timed out
```

Maelstrom started our node `n1`, calling `echo.rb` with no arguments (`nil`).
Our server got an `init` message from Maelstrom: `"{\"dest\":\"n1\", ...}"`.
Then the test harness timed out. That's fine! Maelstrom expected our server to
do more, but this is a good first start!

## A Tiny Network Server

Maelstrom sent our server an initialization network message, telling the server
what its ID was and who else was in the cluster. We need to parse this message,
and remember our node ID. Let's require a JSON parser, and add a variable for
node identifiers:

```rb
require 'json'

class EchoServer
  def initialize
    @node_id = nil
  end
  ...
```

In the main loop, we'll parse each line we receive as JSON.

```rb
  def main!
    while line = STDIN.gets
      req = JSON.parse line, symbolize_names: true
      STDERR.puts "Received #{req.inspect}"
    end
  end
```

Let's give that a shot:

```
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10 --log-stderr
...
INFO [2021-02-08 10:34:21,262] node n1 - maelstrom.process Received {:dest=>"n1", :body=>{:type=>"init", :node_id=>"n1", :node_ids=>["n1"], :msg_id=>1}, :src=>"c1"}
...
```

The test also crashes with `clojure.lang.ExceptionInfo: Expected node n1 to
respond to an init message, but node did not respond.`, but that's all right
for now: we'll send a response later.

We've parsed the initialization message into a Ruby data structure. Now we can
extract the node ID, and use it to initialize our own state.

```rb
  def main!
    while line = STDIN.gets
      req = JSON.parse line, symbolize_names: true
      STDERR.puts "Received #{req.inspect}"

      body = req[:body]
      case body[:type]
        # Initialize this node
        when "init"
          @node_id = body[:node_id]
          STDERR.puts "Initialized node #{@node_id}"
      end
    end
  end
```

```
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10 --log-stderr
...
INFO [2021-02-08 10:36:24,323] node n1 - maelstrom.process Received {:dest=>"n1", :body=>{:type=>"init", :node_id=>"n1", :node_ids=>["n1"], :msg_id=>1}, :src=>"c1"}
INFO [2021-02-08 10:36:24,325] node n1 - maelstrom.process Initialized node n1
...
```

Excellent. Now we need to reply to Maelstrom, confirming our initialization.

## Sending a Reply

We receive an initialization message of the form:

```js
{src: "c1",
 dest: "n1",
 body: {msg_id: 1,
        type: "init",
        node_id: "n1",
        node_ids: ["n1"]}}
```

We [need to produce a reply](/doc/protocol.md) to this message with something
like:

```js
{src: "n1",
 dest: "c1",
 body: {msg_id: 123
        in_reply_to: 1
        type: "init_ok"}}
```

Our reply will come from our node ID `n1`, and be sent to the client which
originated this request: `c1`. Our message's body will have type `init_ok`,
acknowledging the response. We also need our own locally unique `msg_id`. In
order for the client to figure out that we're replying to this particular
message, we'll need an `in_reply_to` field in our body, whose value is the
`msg_id` of the init request.

First things first: to generate unique `msg_id`s, we'll need an instance
variable:

```rb
  def initialize
    @node_id = nil
    @next_msg_id = 0
  end
```

Next, we need a way to send a reply. Let's define a `reply!` method, which
takes a request to reply to, and a body to send back. We'll start by
incrementing `@next_msg_id`, so that we have a unique message identifier for
our reply, and setting the body's message identifier to that new id.

```rb
  def reply!(request, body)
    id = @next_msg_id += 1
```

Next, we'll create a copy of the body we're asked to send, and fill in its `msg_id` and `in_reply_to` fields:

```rb
    body = body.merge msg_id: id, in_reply_to: request[:body][:msg_id]
```

Now, we can construct the message envelope, with `src` and `dest` taken from
the request, but reversed:

```rb
    msg = {src: @node_id, dest: request[:src], body: body}
```

Finally, we'll write that as a JSON string to stdout, followed by a newline
separator. We'll make sure to flush stdout: otherwise our message could sit in
the stdout buffer indefinitely, and Maelstrom wouldn't receive it.

```rb
    JSON.dump msg, STDOUT
    STDOUT << "\n"
    STDOUT.flush
  end
```

OK! Now we can reply to the initialization message! All we need to say is that
we initialized OK.

```rb
  def main!
    while line = STDIN.gets
      req = JSON.parse line, symbolize_names: true
      STDERR.puts "Received #{req.inspect}"

      body = req[:body]
      case body[:type]
        # Initialize this node
        when "init"
          @node_id = body[:node_id]
          STDERR.puts "Initialized node #{@node_id}"
          reply! req, {type: "init_ok"}
      end
    end
  end
```

Let's give that a shot!

```
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10 --log-stderr
...
INFO [2021-02-08 11:17:57,613] jepsen node n1 - maelstrom.db Setting up n1
INFO [2021-02-08 11:17:57,615] jepsen node n1 - maelstrom.process launching echo.rb nil
INFO [2021-02-08 11:17:57,703] node n1 - maelstrom.process Received {:dest=>"n1", :body=>{:type=>"init", :node_id=>"n1", :node_ids=>["n1"], :msg_id=>1}, :src=>"c1"}
INFO [2021-02-08 11:17:57,705] node n1 - maelstrom.process Initialized node n1
INFO [2021-02-08 11:17:57,728] jepsen test runner - jepsen.core Relative time begins now
INFO [2021-02-08 11:17:57,842] jepsen worker 0 - jepsen.util 0	:invoke	:echo	"Please echo 29"
INFO [2021-02-08 11:17:57,850] node n1 - maelstrom.process Received {:dest=>"n1", :body=>{:type=>"echo", :echo=>"Please echo 29", :msg_id=>1}, :src=>"c3"}
WARN [2021-02-08 11:18:02,855] jepsen worker 0 - jepsen.generator.interpreter Process 0 crashed
java.lang.RuntimeException: timed out
...

Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

We successfully initialized node n1! After initializing, Maelstrom went on to
send us a new kind of message: `{type: "echo", echo: "Please echo 29"}`. Those
requests all timed out, because we didn't send back any responses. That caused
Maelstrom to print `Analysis invalid!`: it's letting us know that something in
our system looks broken. To fix that, we need to respond to those `echo`
messages.

## Echo? Echo!

The [Echo Workload](../workloads.md#workload-echo) defines a single kind of
RPC request: clients send `type: echo` messages with an `echo: <some-string>`
field, and expect `type: echo_ok` responses with that same `echo:
<some-string>` back.

We'll add a new case to our `main!` method, for responding to echo messages.
Let's try replying with the same body we were given:

```rb
  def main!
    while line = STDIN.gets
      req = JSON.parse line, symbolize_names: true
      STDERR.puts "Received #{req.inspect}"

      body = req[:body]
      case body[:type]
        # Initialize this node
        when "init"
          @node_id = body[:node_id]
          STDERR.puts "Initialized node #{@node_id}"
          reply! req, {type: :init_ok}

        # Send echoes back
        when "echo"
          STDERR.puts "Echoing #{body}"
          reply! req, body
      end
    end
  end
```

Let's try that out:

```clj
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10 --log-stderr
...
clojure.lang.ExceptionInfo: Malformed RPC response. Maelstrom sent node n1 the following request:

{:echo "Please echo 45", :type "echo", :msg_id 1}

And expected a response of the form:

{:type (eq "echo_ok"),
 :echo Any,
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}

... but instead received

{:echo "Please echo 45", :type "echo", :msg_id 10, :in_reply_to 1}

This is malformed because:

{:type (not (= "echo_ok" "echo"))}

See doc/protocol.md for more guidance.
```

Maelstrom checks the messages we send to make sure they match up with the
expected format. We were *supposed* to respond with a body that had `type:
"echo_ok"`, but instead we sent back `type: "echo"`. Why? Because we responded
with the same body we took in! Let's fix that:

```rb
        when "echo"
          STDERR.puts "Echoing #{body}"
          reply! req, body.merge({type: "echo_ok"})
```

```clj
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10
...
INFO [2021-02-22 13:54:50,633] jepsen test runner - jepsen.core Relative time begins now
INFO [2021-02-22 13:54:50,647] jepsen worker 0 - jepsen.util 0	:invoke	:echo	"Please echo 58"
INFO [2021-02-22 13:54:50,668] jepsen worker 0 - jepsen.util 0	:ok	:echo	{:echo "Please echo 58", :type "echo_ok", :msg_id 2, :in_reply_to 1}
INFO [2021-02-22 13:54:50,700] jepsen worker 0 - jepsen.util 0	:invoke	:echo	"Please echo 124"
INFO [2021-02-22 13:54:50,702] jepsen worker 0 - jepsen.util 0	:ok	:echo	{:echo "Please echo 124", :type "echo_ok", :msg_id 3, :in_reply_to 2}
INFO [2021-02-22 13:54:51,602] jepsen worker 0 - jepsen.util 0	:invoke	:echo	"Please echo 12"
INFO [2021-02-22 13:54:51,605] jepsen worker 0 - jepsen.util 0	:ok	:echo	{:echo "Please echo 12", :type "echo_ok", :msg_id 4, :in_reply_to 3}
...
```

Each `:invoke` line means Maelstrom is about to send a request to our echo
server. Each `:ok` line shows the body of the response that our echo server
sent back. Our responses match the requested values, so Maelstrom logs:

```clj
INFO [2021-02-22 13:55:01,554] jepsen test runner - jepsen.core {:perf {:latency-graph {:valid? true},
        :rate-graph {:valid? true},
        :valid? true},
 :timeline {:valid? true},
 :exceptions {:valid? true},
 :stats {:valid? true,
         :count 12,
         :ok-count 12,
         :fail-count 0,
         :info-count 0,
         :by-f {:echo {:valid? true,
                       :count 12,
                       :ok-count 12,
                       :fail-count 0,
                       :info-count 0}}},
 :net {:stats {:all {:send-count 26, :recv-count 26, :msg-count 26},
               :clients {:send-count 26,
                         :recv-count 26,
                         :msg-count 26},
               :servers {:send-count 0, :recv-count 0, :msg-count 0}},
       :valid? true},
 :workload {:valid? true, :errors ()},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Hurrah! We have an echo server! It successfully performed 12 echo operations,
and used 26 messages in the process (12 echo requests, 12 responses, plus one
request and response for initialization). Let's try *changing* the response we
send to see if Maelstrom notices.

```rb
        when "echo"
          STDERR.puts "Echoing #{body}"
          reply! req, body.merge({type: "echo_ok", echo: "not-right"})
```

```
$ ./maelstrom test -w echo --bin echo.rb --nodes n1 --time-limit 10
...
 :workload {:valid? false,
            :errors (["Expected a message with :echo"
                      "Please echo 15"
                      "But received"
                      {:echo "not-right",
                       :type "echo_ok",
                       :msg_id 9,
                       :in_reply_to 8}]
                     ["Expected a message with :echo"
                      "Please echo 20"
                      "But received"
                      {:echo "not-right",
                       :type "echo_ok",
                       :msg_id 5,
                       :in_reply_to 4}]
                     ...

Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

Aha! So if we respond with the wrong value, Maelstrom detects the inconsistency
and informs us at the end of the test. Each of Maelstrom's
[workloads](/doc/workloads.md) uses different kinds of operations, and checks
different kinds of properties on them. We'll see additional workloads in later
chapters.

### Clojure implementations

If you want to run the `clojure` implementation of the echo server, first install [babashka](https://github.com/babashka/babashka) and then run

```
$ ./maelstrom test -w echo --bin demo/clojure/echo.clj --time-limit 10
```
