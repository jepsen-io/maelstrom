# maelstrom-python

This is a Python implementation of the Maelstrom Node. This provides basic
message handling and an asyncio event loop.

## Usage

For Python, the easiest way to run a script is to make sure that it has the
executable mode set (`chmod +x`) and add a shebang line to the top of the file.

```python
#!/usr/bin/env python3
```

Examples are in `echo.py` and `broadcast.py`, which import from `maelstrom.py`.

```sh
$ maelstrom test -w echo --bin echo.py ...
$ maelstrom test -w broadcast --bin broadcast.py ...
```
