package maelstrom;

import maelstrom.broadcast.BroadcastServer;
import maelstrom.echo.EchoServer;

public class Main {
  public static void main(String[] args) {
    new EchoServer().run();
    // new BroadcastServer().run();
  }
}