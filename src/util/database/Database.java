package util.database;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

public abstract class Database {

    protected long firstSimpleStamp = 0; // The System.currentTimeInMillis of the last query
    protected long firstPrepStamp = 0; // The System.currentTimeInMillis of the last query

    protected String id = ""; // the name of the database
    protected String user = ""; // username with writing rights
    protected String pass = ""; // password for the username
    protected String irl = ""; // irl string for the connection

    protected int maxQueries = 30; // Maximum amount of queries in a buffer before a flush is triggered
    protected long maxAge = 30; // Maximum age of the oldest query in the buffer before a flush is triggered
    protected int idleCount = 0; // How many seconds this has been idle
    protected int idleTime = -1; // Amount of seconds before a database is considered idle and the disconnected
                                 // (-1 is never)
    protected STATE state = STATE.IDLE; // current state of the database

    protected enum STATE {
        IDLE, CON_BUSY, HAS_CON, NEED_CON, FLUSH_REQ, HAS_DUMP
    }

    protected boolean tablesRetrieved = false;
    protected boolean connecting = false;
    protected String lastError="";

    /* ************************************************************************************************* */

    /**
     * Set the ID  of this database
     * 
     * @param id The id given
     */
    public void setID(String id) {
        this.id = id;
    }

    /**
     * Get the database id
     * 
     * @return the id given to this database
     */
    public String getID() {
        return id;
    }
    public String getLastError(){
        String l = lastError;
        lastError="";
        return l;
    }
    protected boolean readFlushSetup(Element set){
        if( set != null ){
            String age = XMLtools.getStringAttribute( set, "age", "30s");	    // How many time before data is flushed (if not reached batch size)
            maxAge = TimeTools.parsePeriodStringToSeconds(age);
            maxQueries = XMLtools.getIntAttribute( set, "batchsize", 30);		// Minimum amount of queries before a flush unless checks till flush is reached
            Logger.debug( id+" -> Flush:"+maxAge+"s maxQ:"+maxQueries);
            return true;
        }else{
            Logger.debug( id+" -> No changes requested to default flush/idle values ");
            return false;
        }
    }

    /* Abstract Methods */
    public abstract boolean connect(boolean force);
    public abstract boolean disconnect();
    public abstract boolean isValid(int timeout);

    public abstract int getRecordsCount() ;
    public abstract boolean hasRecords();

    public abstract void writeToXml(XMLfab fab);

    public abstract boolean getCurrentTables(boolean clear);
    public abstract String createContent(boolean keepConnection);
    public abstract String getTableInfo(String eol);
    public abstract boolean buildGenericFromTable( XMLfab fab, String tableName, String genID, String delim);
    public abstract int buildGenericFromTables( XMLfab fab,boolean overwrite, String delim);

    public abstract void checkState( int secondsPassed ) throws Exception;

    public abstract int doDirectInsert(String table, Object... values);

    public abstract Optional<List<List<Object>>> doSelect(String query, boolean includeNames );
    public Optional<List<List<Object>>> doSelect(String query ){
        return doSelect(query,false);
    }
    public abstract void addQuery(String query);
    public abstract boolean buildInsert(String table, ConcurrentMap<String, Double> rtvals,
                                        ConcurrentMap<String, String> rttext, String macro);

}