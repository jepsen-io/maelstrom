# Maelstrom Node Implementation in C++

Welcome to Maelstrom, an advanced node implementation designed to provide a simple and efficient framework for writing C++ code.

## Classes 🚀

**1. `Message`:** This encapsulates the JSON messages that are exchanged between the test binary and the Maelstrom. Serialization and deserialization are taken care of by the Boost.JSON library.

**2. `Node`:** The medium of message exchange between the test binary and the Maelstrom. It receives messages from the Maelstrom and calls the relevant message handler. Additionally, it offers a means to register a custom message handler.

**3. `MessageHandler`:** This provides an interface for users to create a custom message handler.

## Building the Project 🛠

Although tested primarily on Mac, the implementation itself is not specific to any particular operating system.

This project utilizes the [Boost C++ Libraries](https://www.boost.org/) (particularly Boost.JSON), and assumes that Boost is installed on your system.

You can build the source code using the `Makefile`. Here are a few key variables you might need to adjust:

- `CXX`: Specifies the C++ compiler. The default is `g++`.
- `CXXFLAGS`: Flags passed to the C++ compiler, including the path to the Boost headers and the `-std=c++17` flag to enable C++17 features. Please adjust the hard-coded Boost headers path (`/opt/homebrew/Cellar/boost/1.82.0_1/include`) to match your system configuration.
- `LDFLAGS`: Flags passed to the linker. The default includes the path to the Boost libraries. Update the hard-coded Boost libraries path (`/opt/homebrew/Cellar/boost/1.82.0_1/lib`) to match your system configuration.
- `DEBUG`: If set to `1`, enables debug flags.

To build the project:

```bash
make
```

To build the project with debug symbols:

```bash
make DEBUG=1
```

To clean the build:

```bash
make clean
```

## Running the Echo Server 📡

The `echo.cpp` file provides an implementation for the `echo` workload. An executable binary is generated upon successful `make` command execution.

To run this binary against the Maelstrom, issue the following command:

```bash
maelstrom test -w echo --bin ./demo/c++/echo  --node-count 3 --time-limit 60
```

If you're unfamiliar with the above command, refer to [this section](https://github.com/jepsen-io/maelstrom/blob/main/doc/01-getting-ready/index.md) for further details.
