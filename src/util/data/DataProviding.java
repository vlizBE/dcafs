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

    /* Double */
    Optional<RealVal> getRealVal(String param );
    RealVal getOrAddRealVal(String id );
    boolean renameReal(String from, String to, boolean alterXml);
    boolean hasReal(String id);
    double getReal(String id, double defVal, boolean createIfNew);
    double getReal(String id, double bad);

    boolean setReal(String id, double value);
    boolean updateReal(String id, double bad);
    int updateRealGroup(String group, double value);

    /* Integer */
    Optional<IntegerVal> getIntegerVal(String param );
    IntegerVal getOrAddIntegerVal( String id );
    boolean renameInteger( String from, String to, boolean alterXml);
    boolean hasInteger( String id);
    int getInteger(String id, int defVal, boolean createIfNew);
    int getInteger(String id, int bad);

    boolean setInteger(String id, int value);
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
