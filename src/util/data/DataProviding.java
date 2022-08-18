package util.data;

import das.IssuePool;
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

    /* Real */
    Optional<RealVal> getRealVal(String id );
    boolean addRealVal( RealVal rv, boolean storeInXML );
    boolean renameReal(String from, String to, boolean alterXml);
    boolean hasReal(String id);
    double getReal(String id, double bad);
    boolean updateReal(String id, double value);
    int updateRealGroup(String group, double value);

    /* Integer */
    Optional<IntegerVal> getIntegerVal(String id );
    IntegerVal addIntegerVal( String group, String name );
    IntegerVal addIntegerVal(IntegerVal iv, boolean storeInXML);
    boolean renameInteger( String from, String to, boolean alterXml);
    boolean hasInteger( String id);
    int getInteger(String id, int bad);

    boolean updateInteger(String id, int bad);
    int updateIntegerGroup(String group, int value);

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
