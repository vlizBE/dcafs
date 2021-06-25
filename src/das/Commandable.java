package das;

import com.Writable;

public interface Commandable {
    String replyToCommand(String[] request, Writable wr, boolean html);
    boolean removeWritable( Writable wr);
}
