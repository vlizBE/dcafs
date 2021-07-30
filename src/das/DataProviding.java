package das;

import java.util.ArrayList;

public interface DataProviding {

    /* Parsing */
    String simpleParseRT( String line );
    String parseRTline( String line, String error );

    /* Double */
    DoubleVal getDoubleVal( String param );
    double getRealtimeValue(String parameter, double defVal, boolean createIfNew);
    double getRealtimeValue(String parameter, double bad);
    boolean setRealtimeValue(String param, double value, boolean createIfNew);

    /* Text */
    String getRealtimeText(String parameter, String bad);
    boolean setRealtimeText(String param, String value );

    /* Flags */
    boolean hasFlag( String flag);
    boolean isFlagUp( String flag );
    boolean isFlagDown( String flag );
    boolean raiseFlag( String... flag );
    boolean lowerFlag( String... flag );
    ArrayList<String> listFlags();
}
