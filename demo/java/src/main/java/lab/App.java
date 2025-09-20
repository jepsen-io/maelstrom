package lab;

import lab.txnListAppend.TxnListAppendServer;

public class App {
    public static void main( String[] args ) {
        final String server = args[0];
        if (server.equals("echo")) {
            new EchoServer().run();
        } else if (server.equals("broadcast")) {
            new BroadcastServer().run();
        } else if (server.equals("g-set")) {
            new GSetServer().run();
        } else if (server.equals("g-counter")) {
            new GCounterServer().run();
        } else if (server.equals("txn-list-append")) {
            new TxnListAppendServer().run();
        }
    }
}
