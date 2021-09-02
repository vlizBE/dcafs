package util.database;

import util.data.DataProviding;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class SQLDB extends Database{

    private final String tableRequest;   // The query to request table information
    private final String columnRequest;  // The query to request column information
    private final String dbName;         // The name of the database
    protected HashMap<String, SqlTable> tables = new HashMap<>(); // Map of the tables
    protected ArrayList<String> views = new ArrayList<>();        // List if views
    protected ArrayList<String> simpleQueries = new ArrayList<>();// Simple query buffer

    protected Connection con = null; // Database connection

    DBTYPE type;                                  // The type of database this object connects to
    enum DBTYPE {MSSQL,MYSQL,MARIADB, POSTGRESQL} // Supported types

    boolean busySimple =false;   // Busy with the executing the simple queries
    boolean busyPrepared =false; // Busy with executing the prepared statement queries

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1); // Scheduler for queries

    /**
     * Prepare connection to one of the supported databases
     * @param type The type of database
     * @param address The address for this connection (port is optional)
     * @param user A user with sufficient rights
     * @param pass The pass for the user
     */
    public SQLDB( DBTYPE type, String address, String dbName, String user, String pass ) {
        this.type=type;
        this.user=user;
        this.pass=pass;
        this.dbName=dbName;

        switch( type ){
            case MYSQL:
                irl="jdbc:mysql://" + address + (address.contains(":")?"/":":3306/") + dbName;
                tableRequest="SHOW FULL TABLES;";
                columnRequest="SHOW COLUMNS FROM ";
                break;
            case MSSQL:
                irl = "jdbc:sqlserver://" + address + (dbName.isBlank() ? "" : ";database=" + dbName);
                tableRequest="SELECT * FROM information_schema.tables";
                columnRequest="";
                break;
            case MARIADB:
                irl="jdbc:mariadb://" + address + (address.contains(":")?"/":":3306/") + dbName;
                tableRequest="SHOW FULL TABLES;";
                columnRequest="SHOW COLUMNS FROM ";
                break;
            case POSTGRESQL:
                irl="jdbc:postgresql://"+ address + (address.contains(":")?"/":":5432/")+dbName;
                tableRequest="SELECT table_name FROM information_schema.tables WHERE NOT table_schema='pg_catalog'AND NOT table_schema='information_schema';";
                columnRequest="SELECT column_name,udt_name,is_nullable,is_identity FROM information_schema.columns WHERE table_name=";
                break;
            default:
                tableRequest="";
                columnRequest="";
                Logger.error(id+" (db) -> Unknown database type: "+type);
        }
    }
    protected SQLDB(){
        tableRequest="";
        columnRequest="";
        dbName="";
    }
    /* ************************************************************************************************* */
    public static SQLDB asMSSQL( String address,String dbName, String user, String pass ){
        return new SQLDB( DBTYPE.MSSQL,address,dbName,user,pass );
    }
    public static SQLDB asMARIADB( String address,String dbName, String user, String pass ){
    	return new SQLDB( DBTYPE.MARIADB,address,dbName,user,pass );
    }
    public static SQLDB asMYSQL( String address, String dbName, String user, String pass ){
        return new SQLDB( DBTYPE.MYSQL,address,dbName,user,pass);
    }
    public static SQLDB asPOSTGRESQL(String address, String dbName, String user, String pass ){
        return new SQLDB( DBTYPE.POSTGRESQL,address,dbName,user,pass);
    }
    /* ************************************************************************************************* */
    public String toString(){
        return type.toString().toLowerCase()+"@"+getTitle() +" -> " +getRecordsCount()+"/"+maxQueries;
    }

    /**
     * Get the title of the database
     * @return The title
     */
    public String getTitle(){
        if( isMySQL() || type==DBTYPE.POSTGRESQL)
            return irl.substring(irl.lastIndexOf("/")+1);
        return irl.substring(irl.lastIndexOf("=")+1);
    }

    /**
     * Check if the database is a MySQL variant (mysql,mariadb)
     * @return True if so
     */
    public boolean isMySQL(){
        return type==DBTYPE.MARIADB || type==DBTYPE.MYSQL;
    }
    /* **************************************************************************************************/
    /**
     * Open the connection to the database
     * @param force If true the current connection will be closed (if any)
     * @return True if successful
     */
    @Override
    public boolean connect(boolean force){        
        try {
            if( con != null ){
                if( !con.isValid(2) || force){
                    con.close();
                }else{   
                    return true;                    
                }
            }
        } catch (SQLException e) {
            Logger.error(id+" (db) -> "+e.getMessage());
        }        
       
        try {
            switch( type ){ // Set the class according to the database, might not be needed anymore
                case MSSQL:	  
                    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");  
                    break;
                case MYSQL:   
                    Class.forName("com.mysql.cj.jdbc.Driver"); 					  
                    break;
                case MARIADB: 
                    Class.forName("org.mariadb.jdbc.Driver");                       
                    break;
                case POSTGRESQL:
                    Class.forName("org.postgresql.Driver");
                    break;
            }
		} catch (ClassNotFoundException ex) {
            Logger.error( id+"(db) -> Driver issue with SQLite!" );
        	return false;
        }                

        try{
            con = DriverManager.getConnection(irl, user, pass);           
            Logger.info( id+"(db) -> Connection: " + irl +con);
            state = STATE.HAS_CON; // Connection established, change state
    	} catch ( SQLException ex) {              
            String message = ex.getMessage();
            int eol = message.indexOf("\n");
            if( eol != -1 )
                message = message.substring(0,eol);          
            Logger.error( id+"(db) -> Failed to make connection to database! "+message );
            state = STATE.NEED_CON; // Failed to connect, set state to try again
            return false;
        }       
    	return true;
    }
    @Override
    public boolean disconnect() {
        if (con != null) { // No use trying to disconnect a connection that doesn't exist
            try {
                if (con.isClosed()) // if no active connection, just return
                    return false;
                con.close(); // try closing it
                Logger.info(id+" -> Closed connection");
                return true;
            } catch (SQLException e) { // Failed to close it somehow
                Logger.error(id+" (db)-> "+e);
                return false;
            }
        }
        return false;
    }
    /**
     * Check whether the database is valid with a given timeout in seconds
     *
     * @param timeout The timeout in seconds
     * @return True if the connection is still valid
     */
    public boolean isValid(int timeout) {
        try {
            if (con == null) { // no con = not valid
                return false;
            } else {
                return con.isValid(timeout);
            }
        } catch (SQLException e) {
            Logger.error(id+"(db) -> SQLException when checking if valid: "+e.getMessage());
            return false;
        }
    }

    /**
     * Get the amount of records currently buffered
     * @return Get the amount of bufferd records
     */
    public int getRecordsCount() {
        return tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() + simpleQueries.size();
    }
    /**
     * Check if there are buffered records
     * @return True if there's at least one buffered record
     */
    public boolean hasRecords() {
        return !simpleQueries.isEmpty() || tables.values().stream().anyMatch(t -> t.getRecordCount() != 0);
    }
    /**
     * Get the SQLiteTable associated with the given id
     *
     * @param id The id of the table
     * @return The table if found or an empty optional if not
     */
    public Optional<SqlTable> getTable(String id) {
        if (tables.get(id) == null && !tablesRetrieved) { // No table info
            tablesRetrieved = true;
            getCurrentTables(false);
        }
        return Optional.ofNullable(tables.get(id));
    }

    /**
     * Get a stream of all the table object linked to this database
     * @return The stream of tables
     */
    public Stream<SqlTable> getTables() {
        return tables.values().stream();
    }
    /**
     * Get all the information on the local tables
     * @param eol The eol sequence to use
     * @return The gathered information
     */
    public String getTableInfo(String eol){

        StringJoiner j = new StringJoiner(eol,"Info about "+id+eol,"");
        j.setEmptyValue("No tables stored.");
        tables.values().forEach( table -> j.add(table.getInfo()));
        return j.toString();
    }
    /**
     * Build a generic based on the information of the chosen table
     * @param fab The xmlfab used
     * @param tableName The name of the table
     * @param genID The id for the new generic
     * @param delimiter The delimiter to use in the generic
     * @return True if this worked
     */
    public boolean buildGenericFromTable(XMLfab fab, String tableName, String genID, String delimiter){
        return getTable(tableName).map(sqlTable -> sqlTable.buildGeneric(fab, id, genID, delimiter)).orElse(false);
    }
    /**
     * Build a generic for each local table of this database
     * @param fab The xmlfab to use
     * @param overwrite If true will overwrite existing generics
     * @param delimiter The delimiter for the generics
     * @return The amount of generics build
     */
    @Override
    public int buildGenericsFromTables(XMLfab fab, boolean overwrite, String delimiter) {
        var ele = fab.getChildren("generic");
        int cnt=0; // keep track of the amount of generics build
        for( var table : tables.values()){ // Go through the tables
            boolean found = fab.getChildren("generic","dbid",id).stream() // get child nodes with the same dbid
                                    .anyMatch(e->e.getAttribute("table").equalsIgnoreCase(table.name)); // and the same table
            if( !found ){ // If no such node exists yet
                table.buildGeneric(fab,id,table.name,delimiter); // build it
                fab.up(); // return the fab to higher level
                cnt++; // increment the counter
            }
        }
        return cnt;
    }
    /**
     * Check which tables are currently in the database and add them to this object
     * @param clear Clear the stored databases first
     * @return True if successful
     */
    @Override
    public boolean getCurrentTables(boolean clear){
        
        if( !connect(false) ) // if no connection available try to reconnect
            return false; // Not connected and reconnect failed, can't do anything

        try( Statement stmt = con.createStatement() ){ // Prepare a statement
            ResultSet rs = stmt.executeQuery(tableRequest); // Send the tablerequest query
            if (rs != null) { // If there's a valid resultset
                try {
                    if( clear ) // If clear is set, first clear existing tables
                        tables.clear();
                    while (rs.next()) { // Go through the resultset
                        ResultSetMetaData rsmd = rs.getMetaData(); // get the metadata
                        String tableName = rs.getString(1); // the tablename is in the first column

                        String tableType="base table"; // only interested in the base tables, not system ones
                        if( rsmd.getColumnCount()==2)
                            tableType=rs.getString(2);

                        if( tableType.equalsIgnoreCase("base table") && !tableName.startsWith("sym_") ){ // ignore symmetricsDS tables and don't overwrite
                            SqlTable table = tables.get(tableName); // look for it in the stored tables
                            if( table == null ){ // if not found, it's new
                                table = new SqlTable(tableName); // so create it
                                tables.put(tableName, table); //and add it to the hashmap
                            }
                            table.toggleServer();
                            table.toggleReadFromDB(); // either way, it's already present in the database
                            Logger.debug(id+" (db) -> Found: "+tableName+" -> "+tableType);
                        }                        
                    }
                } catch (SQLException e) {
                    Logger.error(id+"(db) -> Error during table read: "+e.getErrorCode());
                    Logger.error(e.getMessage());
                    return false;
                }  
            }
        }catch( SQLException e ){
            Logger.error(e);
            return false;
        }   
        // Get the column information....
        for( SqlTable table :tables.values() ){
            if( table.hasColumns() ){// Don't overwrite existing info
                Logger.debug(id+"(db) -> The table "+table.getName()+" has already been setup, not adding the columns");
                continue;
            }
            try( Statement stmt = con.createStatement() ){
                String tblName = table.getName();
                if( type == DBTYPE.POSTGRESQL )
                    tblName = "'"+tblName+"'";
                ResultSet rs = stmt.executeQuery(columnRequest+tblName+";");
                if (rs != null) {
                    try {
                        boolean first = true;
                        while (rs.next()) {               
                            String name = rs.getString(1);
                            String colType=rs.getString(2).toLowerCase();
                            if( first && (colType.equals("timestamp") || colType.equals("datetime"))){
                                table.addUTCDateTime(name,"",true);
                                first=false;                                
                            }else if( name.equalsIgnoreCase("timestamp") && colType.equalsIgnoreCase("long") ){
                                table.addEpochMillis(name);
                            }else if( colType.contains("text") || colType.contains("char") ){
                                table.addText(name);
                            }else if( colType.equalsIgnoreCase("double") || colType.equalsIgnoreCase("decimal") || colType.startsWith("float")|| colType.equals("real")){
                                table.addReal(name);
                            }else if( colType.contains("int") || colType.contains("bit") || colType.contains("boolean")) {
                                table.addInteger(name);
                            }else if(colType.equalsIgnoreCase("timestamp") ){
                                table.addLocalDateTime(name,"",false);
                            }else if(colType.equalsIgnoreCase("timestamptz") || colType.equalsIgnoreCase("datetime") ){
                                table.addUTCDateTime(name,"",false);
                            }else{
                                Logger.info(id+" -> Found unknown column type in "+table.getName()+": "+name+" -> "+colType);
                            }                            
                        }
                        Logger.info(table.getInfo());
                    } catch (SQLException e) {
                        Logger.error(id+"(db) -> Error during table read: "+e.getErrorCode());
                        return false;
                    }  
                }
            }catch( SQLException e ){
                Logger.error(e);
                return false;
            } 
        }     
        return true;
    }
    /**
     * Actually create all the tables
     * @param keepConnection True if the connection should be kept open afterwards
     * @return Empty string if all ok, otherwise error message
     */
    public String createContent(boolean keepConnection){

        boolean connected=false;
        if( con==null || !isValid(2) ){
            connected=true;
            if (!connect(false) )
                return "No connection to "+id;
        }
        if( isValid(5) ){
                // Create the tables
                tables.values().forEach(tbl -> {
                    Logger.debug(id+"(db) -> Checking to create "+tbl.getName()+" read from?"+tbl.isReadFromDB());
                    if( !tbl.isReadFromDB() ){
                        try( Statement stmt = con.createStatement() ){
                            stmt.execute( tbl.create() );
                            if( tables.get(tbl.getName())!=null && tbl.hasIfNotExists() ){
                                Logger.warn(id+"(db) -> Already a table with the name "+tbl.getName()+" nothing done because 'IF NOT EXISTS'.");
                            }
                        } catch (SQLException e) {
                            Logger.error(id+"(db) -> Failed to create table with: "+tbl.create() );
                            Logger.error(e.getMessage());
                            tbl.setLastError(e.getMessage()+" when creating "+tbl.name+" for "+id);
                        }
                    }else{
                        Logger.debug(id+"(db) -> Not creating "+tbl.getName()+" because already read from database...");
                    }
                });
                // Create the views
                views.forEach(
                        x -> {
                            try( Statement stmt = con.createStatement() ){
                                stmt.execute( x );
                            } catch (SQLException e) {
                                Logger.error(e.getMessage());
                            }
                        });

            try {
                if( !con.getAutoCommit())
                    con.commit();
                if( connected && keepConnection ){
                    state=STATE.HAS_CON;
                }else{
                    con.close();
                    state=STATE.IDLE;
                }
                StringJoiner errors = new StringJoiner("\r\n");
                tables.values().stream().filter(x-> !x.lastError.isEmpty()).forEach( x -> errors.add(x.getLastError(true)));
                return errors.toString();
            } catch (SQLException e) {
                Logger.error(e);
                return e.getMessage();
            }
        }
        return "No valid connection";
    }
    /**
     * Write a select query and then retrieve the content of a single column from it base on the (case insensitive) name
     * @param query The query to execute
     * @return ArrayList with the data or an empty list if nothing found/something went wrong
     */
    public Optional<List<List<Object>>> doSelect(String query, boolean includeNames ){

        if( !isValid(1) ){
            if( !connect(false) ){
                Logger.error( id+"(db) -> Couldn't connect to database: "+id);
                return Optional.empty();
            }
        }
        var data = new ArrayList<List<Object>>();
        try( Statement stmt = con.createStatement() ){
            ResultSet rs = stmt.executeQuery(query);
            int cols = rs.getMetaData().getColumnCount();
            if( includeNames ){
                var record = new ArrayList<>();
                for( int a=1;a<cols;a++ ){
                    record.add(rs.getMetaData().getColumnName(a));
                }
                data.add(record);
            }
            while( rs.next() ){
                var record = new ArrayList<>();
                for( int a=0;a<cols;a++ ){
                    record.add(rs.getObject(a+1));
                }
                data.add( record );
            }
        } catch (SQLException e) {
            Logger.error(id+"(db) -> Error running query: "+query+" -> "+e.getErrorCode());
        }
        return Optional.ofNullable(data);
    }

    public synchronized boolean buildInsert(String table, DataProviding dp, String macro) {
        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        if( getTable(table).isEmpty() ){
            Logger.error(id+"(db) ->  No such table "+table);
            return false;
        }

        if (getTable(table).map(t -> t.buildInsert(dp, macro)).orElse(false)) {
            if(tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries)
                flushPrepared();
            return true;
        }else{
            Logger.error(id+"(db) -> Build insert failed for "+table);
        }
        return false;
    }
    public synchronized void addQuery(String query) {
        if (!hasRecords())
            firstSimpleStamp = Instant.now().toEpochMilli();
        simpleQueries.add(query);

        if( simpleQueries.size()>maxQueries && !busySimple )
            flushSimple();
    }

    /**
     * Flush all the buffers to the database
     */
    public void flushAll(){
        flushSimple();
        flushPrepared();
    }

    /**
     * Flush the simple queries to the database
     * @return True if flush requested
     */
    protected boolean flushSimple(){
        if( isValid(1)){
            busySimple = true;
            var temp = new ArrayList<String>();
            while(!simpleQueries.isEmpty())
                temp.add(simpleQueries.remove(0));
            scheduler.submit( new DoSimple(temp) );
            return true;
        }else{
            connect(false);
        }
        return false;
    }

    /**
     * Flush all the PreparedStatements to the database
     * @return True if flush requested
     */
    protected boolean flushPrepared(){
        if (!busyPrepared ) { // Don't ask for another flush when one is being done
            if(isValid(1)) {
                busyPrepared=true; // Set Flag so we know the buffer is being flushed
                scheduler.submit(new DoPrepared());
                return true;
            }else{
                connect(false);
            }
        }
        return false;
    }
    /**
     * Read the setup of a database server from the settings.xml
     * @param dbe The element containing the info
     * @return The database object made with the info
     */
    public static SQLDB readFromXML( Element dbe ){
        if( dbe == null )
            return null;                

        Element dbTag = XMLtools.getFirstChildByTag(dbe, "db");

        if( dbTag==null )
            return null;

        String user = XMLtools.getStringAttribute(dbTag, "user", "");           // A username with writing rights
        String pass =  XMLtools.getStringAttribute(dbTag, "pass", "");          // The password for the earlier defined username
        String dbname = dbTag.getTextContent();				                                // The name of the database
        String address = XMLtools.getChildValueByTag( dbe, "address", "");			// Set the address of the server on which the DB runs (either hostname or IP)

        SQLDB db;
        String type = dbe.getAttribute("type");								    	// Set the database type:mssql,mysql or mariadb
        switch( type.toLowerCase() ){
            case "mssql": db = SQLDB.asMSSQL(address, dbname, user, pass); break;
            case "mysql": db = SQLDB.asMYSQL(address, dbname, user, pass); break;
            case "mariadb": db = SQLDB.asMARIADB(address, dbname, user, pass); break;
            case "postgresql": db = SQLDB.asPOSTGRESQL(address, dbname, user, pass); break;
            default:
                Logger.error("Invalid database type: "+type);
                return null;         
        }

        db.id = dbe.getAttribute("id");

        /* Setup */
        db.readFlushSetup(XMLtools.getFirstChildByTag(dbe, "flush"));
        // How many seconds before the connection is considered idle (and closed)
        db.idleTime = TimeTools.parsePeriodStringToSeconds(XMLtools.getChildValueByTag(dbe,"idleclose","5m"));

        /* Tables */
        XMLtools.getChildElements(dbe,"table").stream().forEach( x -> SqlTable.readFromXml(x).ifPresent(table ->
        {
            table.toggleServer();
            db.tables.put(table.name,table);
        }));

        db.getCurrentTables(false);
        db.lastError = db.createContent(true);
        return db;
    }

    /**
     * Write the setup of this database to the settings.xml
     * @param fab A fab pointing to the databases node
     */
    public void writeToXml(XMLfab fab){

        String flush = TimeTools.convertPeriodtoString(maxAge, TimeUnit.SECONDS);
        String address = irl.substring( irl.indexOf("//")+2,irl.lastIndexOf("/"));

        String idle = "-1";
        if( idleTime!=-1)
            idle = TimeTools.convertPeriodtoString(maxAge, TimeUnit.SECONDS);

        fab.selectOrAddChildAsParent("server","id", id.isEmpty()?"remote":id).attr("type",type.toString().toLowerCase())
                .alterChild("db",dbName).attr("user",user).attr("pass",pass)
                .alterChild("flush").attr("age",flush).attr("batchsize",maxQueries)
                .alterChild("idleclose",idle)
                .alterChild("address",address);

        fab.build();
    }

    /**
     * Write the table information to the database node
     * @param fab The xmlfab to use
     * @param tableName The name of the table to write or * to write all
     * @return The amount of tables written
     */
    public int writeTableToXml( XMLfab fab, String tableName ){
        int cnt=0;
        for( var table : tables.values() ){
            if( table.name.equalsIgnoreCase(tableName) || tableName.equals("*")) {
                if( fab.hasChild("table","name",table.name).isEmpty()){
                    table.writeToXml( fab, false);
                    cnt++;
                }
            }
        }
        if( cnt!=0)
            fab.build();
        return cnt;
    }
    /**
     *
     * @param table  The table to insert into
     * @param values The values to insert
     * @return -2=No such table, -1=No such statement,0=bad amount of values,1=ok
     */
    public synchronized int addDirectInsert(String table, Object... values) {
        if( values == null){
            Logger.error(id+" -> Tried to insert a null in "+table);
            return -3;
        }
        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        int res = getTable(table).map(t -> t.doInsert(values)).orElse(-2);
        switch (res) {
            case 1:
                if( tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries )
                    flushPrepared();
                break;
            case 0:
                Logger.error("Bad amount of values for insert into " + id + ":" + table);
                break;
            case -1:
                Logger.error("No such prepStatement found in " + id + ":" + table);
                break;
            case -2:
                Logger.error("No such table ("+table+") found in " + id);
                break;
        }
        return res;
    }
    /**
     * Check and update the current state of the database
     * @param secondsPassed How many seconds passed since the last check (interval so fixed)
     * @throws Exception Catch any exception so the thread doesn't get killed
     */
    public void checkState( int secondsPassed ) throws Exception{
        switch(state){
            case FLUSH_REQ: // Required a flush
                if( !simpleQueries.isEmpty() ) {
                    Logger.info(id+"(db) -> Flushing simple");
                    flushSimple();
                }

                if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ){ // If any table has records
                    flushPrepared();
                }

                if (isValid(1)) { // If not valid, flush didn't work either
                    state = STATE.HAS_CON; // If valid, the state is has_connection
                }else{
                    state = STATE.NEED_CON; // If invalid, need a connection
                }
                break;
            case HAS_CON: // If we have a connection, but not using it
                if( !hasRecords() ){
                    idleCount += secondsPassed;
                    if( idleCount > idleTime && idleTime > 0){
                        Logger.info(getID()+"(id) -> Connection closed because idle: " + id +" for "+TimeTools.convertPeriodtoString( idleCount, TimeUnit.SECONDS)+" > "+
                                TimeTools.convertPeriodtoString( idleTime, TimeUnit.SECONDS) );
                        disconnect();
                        state = STATE.IDLE;
                    }
                }else{
                    Logger.debug(id+"(id) -> Waiting for max age to pass...");
                    if( !simpleQueries.isEmpty() ){
                        long age = (Instant.now().toEpochMilli()- firstSimpleStamp)/1000;
                        Logger.debug(id+"(id) -> Age of simple: "+age+"s versus max: "+maxAge);
                        if( age > maxAge ){
                            state=STATE.FLUSH_REQ;
                            Logger.info(id+"(id) -> Requesting simple flush because of age");
                        }
                    }
                    if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ) {
                        long age = (Instant.now().toEpochMilli() - firstPrepStamp) / 1000;
                        Logger.debug(id+"(id) -> Age of prepared: " + age + "s");
                        if (age > maxAge) {
                            state = STATE.FLUSH_REQ;
                        }
                    }
                    idleCount=0;
                }
                break;
            case IDLE: // Database is idle
                if( hasRecords() ){ // If it has records
                    if( connect(false) ){ // try to connect but don't reconnect if connected
                        state=STATE.HAS_CON; // connected
                    }else{
                        state=STATE.NEED_CON; // connection failed
                    }
                }
                break;
            case NEED_CON: // Needs a connection
                Logger.info(id+" -> Need con, trying to connect...");
                if( connect(false) ){
                    if( hasRecords() ){ // If it is connected and has records
                        state=STATE.HAS_CON;
                        Logger.info(id+" -> Got a connection.");
                    }else{  // Has a connection but doesn't need it anymore
                        state=STATE.IDLE;
                        Logger.info(id+" -> Got a connection, but don't need it anymore...");
                    }
                }
                break;
            default:
                Logger.warn(id+"(db) -> Unknown state: "+state);
                break;
        }
    }

    /**
     * Execute the simple queries one by one
     */
    public class DoSimple implements Runnable{

        ArrayList<String> temp = new ArrayList<>(); // Array to store the queries that are being executed
        int total; // Counter for the amount of queries to be executed

        public DoSimple( ArrayList<String> data){
            temp.addAll(data); // Add all the given queries to the temp arraylist
            total=temp.size(); // Set total to the amount of queries received
        }
        @Override
        public void run() {
            // Process the regular queries
            if( !doBatchRun(temp) ) { // if still not ok, do rest one by one
                for( int a = 0; a<temp.size();a++){
                    try (PreparedStatement pst = con.prepareStatement(temp.get(a))){
                        pst.execute();
                        if (!con.getAutoCommit())
                            con.commit();
                        temp.set(a,""); // if executed, clear the entry in the temp arraylist
                    } catch (SQLException e) {
                        if( e.getErrorCode()==19){
                            temp.set(a,""); // Don't care about this error, clear the entry
                        }else if( e.getMessage().contains("syntax error") || e.getMessage().contains("no such ")
                                || e.getMessage().contains("has no ") || e.getMessage().contains("PRIMARY KEY constraint")){ //sql errors
                            Logger.tag("SQL").error(getID()+"\t"+temp.get(a)); // Bad query, log it
                            Logger.info(id+" (db)-> Error code: "+e.getErrorCode());
                            temp.set(a,"");
                        }else{
                            Logger.error(temp.get(a)+" -> "+e.getMessage());
                            Logger.info(id+" (db)-> Error code: "+e.getErrorCode());
                            temp.set(a,"");
                        }
                    }
                }
            }

            try {
                temp.removeIf(String::isEmpty); // Clear the empty entries
                if( !temp.isEmpty()){ // If there are entries left
                    firstSimpleStamp = Instant.now().toEpochMilli(); // alter the timestamp
                    simpleQueries.addAll(temp); // Add the leftover queries back to the buffer
                }
            }catch(ConcurrentModificationException e){
                Logger.error(id+" (db) -> Clean failed");
            }catch (Exception e){
                Logger.error(e);
            }

            Logger.debug(id+" (db) -> Queries done: "+(total-temp.size())+"/"+total);
            busySimple =false;
        }
    }

    /**
     * Execute queries in a batch
     * @param queries The queries to execute
     * @return
     */
    private boolean doBatchRun(ArrayList<String> queries){

        boolean batchOk=false;
        int retries=2;

        while(retries>=1 &&!batchOk) {
            try (var ps = con.createStatement()){
                boolean auto = con.getAutoCommit();
                for (String q : queries) {
                    ps.addBatch(q);
                }
                if (auto)
                    con.setAutoCommit(false);
                try {
                    var res = ps.executeBatch();
                    con.commit();
                    for (int a = 0; a < res.length; a++)
                        queries.set(a, "");
                    batchOk = true;
                } catch (BatchUpdateException g) {
                    var result = g.getUpdateCounts();
                    boolean first = false;
                    for (int x = 0; x < result.length; x++) {
                        if (result[x] > 0 || result[x] == Statement.SUCCESS_NO_INFO) {
                            Logger.debug("Removing because " + result[x] + " -> " + queries.get(x));
                            queries.set(x, "");
                        } else if (!first) {
                            first = true;
                            Logger.error("Error: " + g.getMessage());
                            Logger.error(getID() + " -> Bad query: " + queries.get(x));
                            queries.set(x, "");
                        }
                    }
                    batchOk = false;
                }
                con.setAutoCommit(auto);
            } catch (SQLException e) {
                Logger.error(e);
                batchOk = false;
            }
            retries--;
        }
        return batchOk;
    }

    /**
     * Execute the stored prepared statements
     */
    private class DoPrepared implements Runnable{

        @Override
        public void run() {
            // Process the prepared statements
            tables.values().stream().filter( SqlTable::hasRecords ).forEach(
                    t -> t.getPreps().forEach(
                            id ->
                            {
                                boolean ok=true;
                                int errors=0;
                                while( t.hasRecords(id)&&ok ){ //do again if new queries arrived or part of the batch failed
                                    int cnt;
                                    try (PreparedStatement ps = con.prepareStatement(t.getPreparedStatement(id))){
                                        cnt = t.fillStatement(id,ps);
                                        if( cnt > 0 ){
                                            ps.executeBatch();
                                            t.clearRecords( id, cnt );
                                            if (!con.getAutoCommit())
                                                con.commit();
                                            if( hasRecords() ) // if there are records left, the timestamp should be reset
                                                firstSimpleStamp = Instant.now().toEpochMilli();
                                        }else{
                                            ok=false;
                                        }
                                    } catch (BatchUpdateException e) {
                                        // One or multiple queries in the batch failed
                                        Logger.error(getID()+" (db)-> Batch error, clearing batched:"+e.getMessage());
                                        Logger.error(e.getErrorCode());
                                        Logger.error(getID()+" (db)-> Removed bad records: "+t.clearRecords( id, e.getLargeUpdateCounts() )); // just drop the data or try one by one?
                                    } catch (SQLException e) {
                                        errors++;
                                        if( e.getMessage().contains("no such table")&& SQLDB.this instanceof SQLiteDB){
                                            Logger.error(getID()+"(db) -> Got no such sqlite table error, trying to resolve...");
                                            try {
                                                var c = con.createStatement();
                                                c.execute(t.create());
                                                if (!con.getAutoCommit())
                                                    con.commit();
                                            } catch (SQLException f) {
                                                Logger.error(f);
                                            }
                                        }
                                        if( errors>10) {
                                            Logger.error(getID()+" -(db)> 10x SQL Error:"+e.getMessage() );
                                            Logger.error(getID()+" (db)-> "+SQLDB.this.toString());
                                            ok = false;
                                        }
                                    } catch (Exception e) {
                                        Logger.error(getID()+"(db) -> General Error:"+e);
                                        Logger.error(e);
                                        ok=false;
                                    }
                                }
                            }
                    )
            );
            // If there are still records left, this becomes the next first
            if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ){
                firstPrepStamp = Instant.now().toEpochMilli();
            }
            busyPrepared=false; // Finished work, so reset the flag
        }
    }
}