# Maelstrom Java demos

This Maven project includes a basic echo and broadcast system, as well a
general-purpose node (split into three tiers--Node1, Node2, and Node3, which
provide progressively more sophisticated functionality). The entry point is `maelstrom.Main`.

Compile this project with `./build`, then in the main `maelstrom` directory, run

```
lein run test -w echo --bin demo/java/server
```

Right now this is hardcoded to be an echo server, and changing that requires
editing the code. Later someone (me?) should go make this a CLI argument, and
maybe add some wrapper scripts. :-)

There are also Visual Studio Code tasks set up for running Maelstrom tests--see .vscode/tasks.json.
