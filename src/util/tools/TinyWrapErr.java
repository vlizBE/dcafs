package util.tools;

import org.tinylog.Logger;
import java.io.PrintStream;

public class TinyWrapErr extends java.io.PrintStream {
    PrintStream ori = System.err;

    public TinyWrapErr() {
        super(System.err);
    }
    public static void install(  )
    {
        System.setErr( new TinyWrapErr() );
    }
    public void print(String x) {
        Logger.error(x);
        ori.print(x);
    }
    public void println(String x) {
        Logger.error(x);
        ori.println(x);
    }
}
