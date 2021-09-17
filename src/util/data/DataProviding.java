package util.data;

import das.IssuePool;
import util.gis.Waypoint;
import util.gis.Waypoints;
import util.xml.XMLfab;

import java.util.ArrayList;
import java.util.Optional;

public interface DataProviding {
    /* Global */
    boolean storeValsInXml( boolean clearFirst );
    void readFromXML(XMLfab fab);
    /* Parsing */
    String simpleParseRT( String line, String error );
    String parseRTline( String line, String error );
    Optional<NumericVal> getNumericVal( String id);
    String buildNumericalMem( String exp, ArrayList<NumericVal> nums, int offset);

    /* Double */
    Optional<DoubleVal> getDoubleVal(String param );
    DoubleVal getOrAddDoubleVal( String id );
    boolean renameDouble( String from, String to, boolean alterXml);
    boolean hasDouble( String id);
    double getDouble(String id, double defVal, boolean createIfNew);
    double getDouble(String id, double bad);

    boolean setDouble(String id, double value);
    boolean updateDouble(String id, double bad);
    int updateDoubleGroup(String group, double value);

    /* Text */
    String getText(String parameter, String bad);
    boolean setText(String param, String value );

    /* Flags */
    Optional<FlagVal> getFlagVal( String flag);
    FlagVal getOrAddFlagVal( String id );
    boolean hasFlag( String flag);
    boolean isFlagUp( String flag );
    boolean isFlagDown( String flag );
    boolean raiseFlag( String... flag );
    boolean lowerFlag( String... flag );
    boolean setFlagState( String flag, boolean state);
    ArrayList<String> listFlags();

    /* Issues */
    ArrayList<String> getActiveIssues();
    IssuePool getIssuePool();
    Optional<Waypoints> getWaypoints();
}
