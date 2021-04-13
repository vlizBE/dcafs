package util.database;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.tinylog.Logger;
import org.w3c.dom.Element;

import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

public class SQLDB extends Database{

    private String tableRequest = "";
    private String columnRequest = "";
    private String dbName="";
    protected HashMap<String, SqlTable> tables = new HashMap<>(); // Map of the tables
    protected ArrayList<String> views = new ArrayList<>();
    protected ArrayList<String> simpleQueries = new ArrayList<>();

    protected Connection con = null;

    DBTYPE type;
    enum DBTYPE {MSSQL,MYSQL,MARIADB}

    boolean busySimple =false;
    boolean busyPrepared =false;

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);;
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
                break;
            case MARIADB:
                irl="jdbc:mariadb://" + address + (address.contains(":")?"/":":3306/") + dbName;
                tableRequest="SHOW FULL TABLES;";
                columnRequest="SHOW COLUMNS FROM ";
                break;
        }

    }
    public SQLDB(){}
    /* **************************************************************************************************/
    public static SQLDB asMSSQL( String address,String dbName, String user, String pass ){
        return new SQLDB( DBTYPE.MSSQL,address,dbName,user,pass );
    }
    public static SQLDB asMARIADB( String address,String dbName, String user, String pass ){
    	return new SQLDB( DBTYPE.MARIADB,address,dbName,user,pass );
    }
    public static SQLDB asMYSQL( String address, String dbName, String user, String pass ){
        return new SQLDB( DBTYPE.MYSQL,address,dbName,user,pass);
    }
    /* **************************************************************************************************/
    public String toString(){
        return type.toString().toLowerCase()+"@"+getTitle() +" -> " +getRecordsCount()+"/"+maxQueries;
    }

    public String getTitle(){
        if( isMySQL() )
            return irl.substring(irl.lastIndexOf("/")+1);
        return irl.substring(irl.lastIndexOf("=")+1);
    }
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
            Logger.error(e.getMessage());
        }        
       
        try {
            switch( type ){
                case MSSQL:	  
                    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");  
                    break;
                case MYSQL:   
                    Class.forName("com.mysql.cj.jdbc.Driver"); 					  
                    break;
                case MARIADB: 
                    Class.forName("org.mariadb.jdbc.Driver");                       
                    break;
            }
		} catch (ClassNotFoundException ex) {
            Logger.error( id+" -> Driver issue with SQLite!" );	        	
        	return false;
        }                

        try{
            con = DriverManager.getConnection(irl, user, pass);           
            Logger.info( id+" -> Connection: " + irl +con);
            state = STATE.HAS_CON;
    	} catch ( SQLException ex) {              
            String message = ex.getMessage();
            int eol = message.indexOf("\n");
            if( eol != -1 )
                message = message.substring(0,eol);          
            Logger.error( id+" -> Failed to make connection to database! "+message );
            state = STATE.NEED_CON;
            return false;
        }       
    	return true;
    }
    @Override
    public boolean disconnect() {
        if (con != null) {
            try {
                if (con.isClosed())
                    return false;
                con.close();
                Logger.info(id+" -> Closed connection");
                return true;
            } catch (SQLException e) {
                Logger.error(e);
                return false;
            }
        }
        return false;
    }
    /**
     * Check whether or not the database is valid with a given timeout in seconds
     *
     * @param timeout The timeout in seconds
     * @return True if the connection is still valid
     */
    public boolean isValid(int timeout) {
        try {
            if (con == null) {
                return false;
            } else {
                return con.isValid(timeout);
            }
        } catch (SQLException e) {
            Logger.error(id+" -> SQLException when checking if valid");
            return false;
        }
    }
    public int getRecordsCount() {
        return tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() + simpleQueries.size();
    }
    public boolean hasRecords() {
        return !simpleQueries.isEmpty() || tables.values().stream().anyMatch(t -> t.getRecordCount() != 0);
    }
    /**
     * Get the SQLiteTable associated with the given id
     *
     * @param id The id of the table
     * @return The table if found or null if not
     */
    public Optional<SqlTable> getTable(String id) {
        if (tables.get(id) == null && !tablesRetrieved) { // No table info
            tablesRetrieved = true;
            this.getCurrentTables(false);
        }
        return Optional.ofNullable(tables.get(id));
    }
    public Stream<SqlTable> getTables() {
        return tables.values().stream();
    }
    public String getTableInfo(String eol){

        StringJoiner j = new StringJoiner(eol,"Info about "+id+eol,"");
        j.setEmptyValue("No tables stored.");
        tables.values().forEach( table -> j.add(table.getInfo()));
        return j.toString();
    }
    public boolean buildGenericFromTable(XMLfab fab, String tablename, String genID, String delim){
        var table = getTable(tablename);
        if( table.isPresent() ){
            return table.get().buildGeneric(fab,id,genID,delim);
        }
        return false;
    }
    /**
     * Check which tables are currently in the database and add them to this object
     * @param clear Clear the stored databases first
     * @return True if successful
     */
    @Override
    public boolean getCurrentTables(boolean clear){
        
        if( !connect(false) )
            return false;

        try( Statement stmt = con.createStatement() ){
            ResultSet rs = stmt.executeQuery(tableRequest);
            if (rs != null) {
                try {
                    if( clear )
                        tables.clear();
                    while (rs.next()) {               
                        String tableName = rs.getString(1);
                        String tableType=rs.getString(2);
                        if( tableType.equalsIgnoreCase("base table") && !tableName.startsWith("sym_") ){ // ignore symmetricsDS tables and don't overwrite
                            SqlTable table = tables.get(tableName); // look for it in the stored tables
                            if( table == null ){ // if not found, it's new
                                table = new SqlTable(tableName); // so create it
                                tables.put(tableName, table); //and add it to the hashmap
                            }
                            table.toggleReadFromDB(); // either way, it's already present in the database
                            Logger.info("Found: "+tableName+" -> "+tableType);
                        }                        
                    }
                } catch (SQLException e) {
                    Logger.error(id+" -> Error during table read: "+e.getErrorCode());
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
                Logger.info(id+" -> The table "+table.getName()+" has already been setup, not adding the columns");
                continue;
            }
            try( Statement stmt = con.createStatement() ){
                ResultSet rs = stmt.executeQuery(columnRequest+table.getName()+";");
                if (rs != null) {
                    try {
                        boolean first = true;
                        while (rs.next()) {               
                            String name = rs.getString(1);
                            String colType=rs.getString(2).toLowerCase();
                            if( first && (name.equalsIgnoreCase("timestamp") && (colType.contains("text") || colType.startsWith("date")|| colType.contains("stamp"))) ){
                                table.addTimestamp(name);
                                first=false;                                
                            }else if( name.equalsIgnoreCase("timestamp") && colType.equalsIgnoreCase("long") ){
                                table.addEpochMillis(name);
                            }else if( colType.contains("date") || colType.contains("text") || colType.contains("char") ){
                                table.addText(name);
                            }else if( colType.equalsIgnoreCase("double") || colType.equalsIgnoreCase("decimal") || colType.equalsIgnoreCase("float")|| colType.equalsIgnoreCase("real")){
                                table.addReal(name);
                            }else if( colType.contains("int") || colType.contains("bit") || colType.contains("boolean")){
                                table.addInteger(name);
                            }else{
                                Logger.info(id+" -> Found unknown column type in "+table.getName()+": "+name+" -> "+colType);
                            }                            
                        }
                        Logger.info(table.getInfo());
                    } catch (SQLException e) {
                        Logger.error(id+" -> Error during table read: "+e.getErrorCode());
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
                tables.values().forEach(x -> {
                    Logger.info(id+" -> Checking to create "+x.getName()+" read from?"+x.isReadFromDB());
                    if( !x.isReadFromDB() ){
                        try( Statement stmt = con.createStatement() ){
                            stmt.execute( x.create() );
                            if( tables.get(x.getName())!=null && x.hasIfNotExists() ){
                                Logger.warn(id+" -> Already a table with the name "+x.getName()+" nothing done because 'IF NOT EXISTS'.");
                            }
                        } catch (SQLException e) {
                            Logger.error(id+" -> Failed to create table with: "+x.create() );
                            Logger.error(e.getMessage());
                            x.setLastError(e.getMessage()+" when creating "+x.name+" for "+id);
                        }
                    }else{
                        Logger.info(id+" -> Not creating "+x.getName()+" because already read from database...");
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
                Logger.error( "Couldn't connect to database: "+id);
                return Optional.empty();
            }
        }
        var data = new ArrayList<List<Object>>();
        try( Statement stmt = con.createStatement() ){
            ResultSet rs = stmt.executeQuery(query);
            int cols = rs.getMetaData().getColumnCount();
            if( includeNames ){
                var record = new ArrayList<Object>();
                for( int a=1;a<cols;a++ ){
                    record.add(rs.getMetaData().getColumnName(a));
                }
                data.add(record);
            }
            while( rs.next() ){
                var record = new ArrayList<Object>();
                for( int a=0;a<cols;a++ ){
                    record.add(rs.getObject(a+1));
                }
                data.add( record );
            }
        } catch (SQLException e) {
            Logger.error("Error running query: "+query+" -> "+e.getErrorCode());
        }
        return Optional.ofNullable(data);
    }
    public synchronized boolean buildInsert(String table, ConcurrentMap<String, Double> rtvals,
                                            ConcurrentMap<String, String> rttext, String macro) {
        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        if( getTable(table).isEmpty() ){
            Logger.error(id+" -> No such table "+table);
            return false;
        }

        if (getTable(table).map(t -> t.buildInsert(rtvals, rttext, macro)).orElse(false)) {
            if(tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries)
                flushPrepared();
            return true;
        }else{
            Logger.error(id+" -> Build insert failed for "+table);
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
    public void flushAll(){
        flushSimple();
        flushPrepared();
    }
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
    protected boolean flushPrepared(){
        if (!busyPrepared ) {
            if(isValid(1)) {
                busyPrepared=true;
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
            default:
                Logger.error("Invalid database type: "+type);
                return null;         
        }

        db.id = dbe.getAttribute("id");

        /* Setup */
        db.readBatchSetup(XMLtools.getFirstChildByTag(dbe, "setup"));

        /* Tables */
        XMLtools.getChildElements(dbe,"table").stream().forEach( x -> {
            SqlTable.readFromXml(x).ifPresent(table ->
            {
                table.toggleServer();
                db.tables.put(table.name,table);
            });
        });

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

        fab.selectOrCreateParent("server","id", id.isEmpty()?"remote":id).attr("type",type.toString().toLowerCase())
                .alterChild("db",dbName).attr("user",user).attr("pass",pass)
                .alterChild("setup").attr("idletime",idle).attr("flushtime",flush).attr("batchsize",maxQueries)
                .alterChild("address",address);


        // Tables?
        for( var table : tables.values() ){
            table.writeToXml(fab,false);
        }
        fab.build();
    }
    /**
     *
     * @param table  The table to insert into
     * @param values The values to insert
     * @return -2=No such table, -1=No such statement,0=bad amount of values,1=ok
     */
    public synchronized int doDirectInsert(String table, Object... values) {
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
                Logger.error("No such prepstatement found in " + id + ":" + table);
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
            case FLUSH_REQ:
                if( !simpleQueries.isEmpty() ) {
                    Logger.info(id+" -> Flushing simple");
                    flushSimple();
                }

                if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ){
                    flushPrepared();
                }

                if (isValid(1)) { // If not valid, flush didn't work either
                    state = STATE.HAS_CON;
                }else{
                    state = STATE.NEED_CON;
                }
                break;
            case HAS_CON: // If we have a connection, but not using it
                if( !hasRecords() ){
                    idleCount += secondsPassed;
                    if( idleCount > idleTime && idleTime > 0){
                        Logger.info(getID()+" -> Connection closed because idle: " + id +" for "+TimeTools.convertPeriodtoString( idleCount, TimeUnit.SECONDS)+" > "+
                                TimeTools.convertPeriodtoString( idleTime, TimeUnit.SECONDS) );
                        disconnect();
                        state = STATE.IDLE;
                    }
                }else{
                    Logger.debug(id+" -> Waiting for max age to pass...");
                    if( !simpleQueries.isEmpty() ){
                        long age = (Instant.now().toEpochMilli()- firstSimpleStamp)/1000;
                        Logger.debug(id+" -> Age of simple: "+age+"s versus max: "+maxAge);
                        if( age > maxAge ){
                            state=STATE.FLUSH_REQ;
                            Logger.info(id+" -> Requesting simple flush because of age");
                        }
                    }
                    if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ) {
                        long age = (Instant.now().toEpochMilli() - firstPrepStamp) / 1000;
                        Logger.debug(id+" -> Age of prepared: " + age + "s");
                        if (age > maxAge) {
                            state = STATE.FLUSH_REQ;
                        }
                    }
                    idleCount=0;
                }
                break;
            case IDLE:
                if( hasRecords() ){
                    if( connect(false) ){ // try to connect but don't reconnect if connected
                        state=STATE.HAS_CON; // connected
                    }else{
                        state=STATE.NEED_CON; // connection failed
                    }
                }
                break;
            case NEED_CON:
                Logger.info(id+" -> Need con, trying to connect...");
                if( connect(false) ){
                    if( hasRecords() ){
                        state=STATE.HAS_CON;
                        Logger.info(id+" -> Got a connection.");
                    }else{
                        state=STATE.IDLE;
                        Logger.info(id+" -> Got a connection, but don't need it anymore...");
                    }
                }
                break;
            default:
                break;
        }
    }

    public class DoSimple implements Runnable{

        ArrayList<String> temp = new ArrayList<>();
        int total=0;

        public DoSimple( ArrayList<String> data){
            temp.addAll(data);
            total=temp.size();
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
                        temp.set(a,"");
                    } catch (SQLException e) {
                        if( e.getErrorCode()==19){
                            temp.set(a,"");
                        }else if( e.getMessage().contains("syntax error") || e.getMessage().contains("no such ")
                                || e.getMessage().contains("has no ") || e.getMessage().contains("PRIMARY KEY constraint")){ //sql errors
                            Logger.tag("SQL").error(getID()+"\t"+temp.get(a));
                            Logger.info("Error code:"+e.getErrorCode());
                            temp.set(a,"");
                        }else{
                            Logger.error(temp.get(a)+" -> "+e.getMessage());
                            Logger.info("Error code:"+e.getErrorCode());
                            temp.set(a,"");
                        }
                    }
                }
            }

            try {
                temp.removeIf( x->x.isEmpty());
                if( !temp.isEmpty()){
                    firstSimpleStamp = Instant.now().toEpochMilli();
                    simpleQueries.addAll(temp);
                }
            }catch(ConcurrentModificationException e){
                Logger.error("Clean failed");
            }catch (Exception e){
                Logger.error(e);
            }

            Logger.debug("Queries done: "+(total-temp.size())+"/"+total);
            busySimple =false;
        }
    }

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
                                    int cnt=0;
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
                                        Logger.error("Batch error, clearing batched:"+e.getMessage());
                                        Logger.error(e.getErrorCode());
                                        Logger.error("Removed bad records: "+t.clearRecords( id, e.getLargeUpdateCounts() )); // just drop the data or try one by one?
                                    } catch (SQLException e) {
                                        Logger.error("SQL Error:"+e.getMessage());
                                        errors++;
                                        if( errors>10)
                                            ok=false;
                                    } catch (Exception e) {
                                        Logger.error("General Error:"+e);
                                        Logger.error(e);
                                        ok=false;
                                    }
                                }
                            }
                    )
            );
            // If there are still records left, this becomes the nex first
            if(tables.values().stream().anyMatch(t -> t.getRecordCount() != 0) ){
                firstPrepStamp = Instant.now().toEpochMilli();
            }
            busyPrepared=false;
        }
    }
}