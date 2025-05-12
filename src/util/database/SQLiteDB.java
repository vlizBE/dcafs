package util.database;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SQLiteDB extends SQLDB{

    static final String GET_SQLITE_TABLES = "SELECT name FROM sqlite_master WHERE type ='table' AND name NOT LIKE 'sqlite_%';";
   
    private Path dbPath;

    /* Variables related to the rollover */
    private DateTimeFormatter format = null;
    private String oriFormat="";
    private ScheduledFuture<?> rollOverFuture;
    private TimeTools.RolloverUnit rollUnit = TimeTools.RolloverUnit.NONE;
    private int rollCount = 0;

    private LocalDateTime rolloverTimestamp;

    private String currentForm = "";
    private Instant lastCheck;
    private ScheduledFuture<?> timeCheckFuture;
    /**
     * Create an instance of a database with rollover
     * @param dbPath Path to the database
     */
    public SQLiteDB( String id, Path dbPath ) {

        this.id=id;
        var p = dbPath.toString();
        this.dbPath = Path.of( p.endsWith(".sqlite")?p:p+".sqlite");

        try {
            Files.createDirectories(Path.of(getPath()).getParent());
        } catch (IOException e) {
            Logger.error( getID() + " -> Issue trying to create "+dbPath.getParent().toString()+" -> "+e.getMessage());
        } catch (NullPointerException e ){
            Logger.error( getID() + " -> Issue trying to create db, path is null");
        }
    }
    /* ************************************************************************************************************** */
    public static SQLiteDB createDB( String id, Path db ){
        return new SQLiteDB( id, db );
    }
    /* ************************************************************************************************************** */
    @Override
    public String toString(){
        String status = getPath() +" -> " +getRecordsCount()+"/"+maxQueries;
        if( rollUnit!=TimeTools.RolloverUnit.NONE){
            if( rollOverFuture==null ){
                status += " -> No proper rollover determined...";
            }else {
                status += " ->  rollover in " + TimeTools.convertPeriodtoString(rollOverFuture.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS);
            }
        }
        status += isValid(1)?"":" (NC)";
        return status;
    }
    /**
     * Get the current path this database can be found at
     * @return The path to the database as a string
     */
    public String getPath(){

        //without rollover
        if( currentForm.isEmpty() )
            return dbPath.toString();

        String path = dbPath.toString();
        updateFileName(LocalDateTime.now(ZoneId.of("UTC")));

        //with rollover and on a specific position
        if( path.contains("{rollover}"))
            return path.replace("{rollover}", currentForm);

        // with rollover but on default position
        return path.replace(".sqlite", currentForm+".sqlite");
    }
    /**
     * Open the connection to the database
     * @param force If true the current connection will be closed (if any)
     * @return True if successful
     */
    @Override
    public boolean connect(boolean force){  
        if( state==STATE.CON_BUSY)
            return false;      
        try {
            if( con != null ){ // if a connection has been made earlier
                if( !con.isValid(2) || force){ // but no longer valid or a reconnect is wanted
                    con.close(); // close the connection
                }else{   
                    return true; // connection still valid and no reconnect is needed, so return true
                }
            }
        } catch (SQLException e) {
            Logger.error(e.getMessage());
        }
        
        try {
            Class.forName("org.sqlite.JDBC"); 
		} catch (ClassNotFoundException ex) {
            Logger.error( getID() + " -> Driver issue with SQLite!" );	        	
        	return false;
        }

        // Make sure to use the proper path
        String irl = "jdbc:sqlite:"+getPath();

        try{
            state = STATE.CON_BUSY;
            con = DriverManager.getConnection(irl, user, pass);
            con.setAutoCommit(false); //Changed
            Logger.info( getID() + " -> Connection: "+con+ " irl:"+irl);
            state=STATE.HAS_CON;
    	} catch ( SQLException ex) {              
            String message = ex.getMessage();
            int eol = message.indexOf("\n");
            if( eol != -1 )
                message = message.substring(0,eol);          
            Logger.error( getID() + " -> Failed to make connection to SQLite database! "+message );
            state=STATE.NEED_CON;
            return false;
        }    
    	return true;
    }

    /**
     * Check which tables currently exist in the database and add them to this object
     */
    @Override
    public boolean getCurrentTables( boolean clear){

        if( !connect(false))
            return false;

        if( clear )
            tables.clear();

        try( Statement stmt = con.createStatement() ){
            ResultSet rs = stmt.executeQuery(GET_SQLITE_TABLES);
            if (rs != null) {
                try {
                    while (rs.next()) {
                        String tableName = rs.getString(1);
                        if( tables.get(tableName)==null) {//don't overwrite
                            var t= new SqlTable(tableName);
                            tables.put(tableName, t);
                        }
                        tables.get(tableName).flagAsReadFromDB();
                    }
                } catch (SQLException e) {
                    Logger.error( getID() + " -> Error during table read: "+e.getErrorCode());
                    return false;
                }
            }
        }catch( SQLException e ){
            Logger.error(e);
        }
        for( SqlTable table : tables.values() ){
            if( table.isReadFromDB() ){// Don't overwrite existing info
                Logger.debug( getID() + " -> The table "+table.getName()+" has already been setup, not adding the columns");
                continue;
            }

            try( Statement stmt = con.createStatement() ){
                ResultSet rs = stmt.executeQuery("PRAGMA table_info("+table.getName()+");");
                if (rs != null) {
                    try {
                        while (rs.next()) {
                            String column = rs.getString(rs.findColumn("name"));
                            String type = rs.getString(rs.findColumn("type"));

                            switch (type.toLowerCase()) {
                                case "integer" -> table.addInteger(column);
                                case "real" -> table.addReal(column);
                                case "text" -> {
                                    if (column.equalsIgnoreCase("timestamp")) {
                                        table.addTimestamp(column);
                                    } else {
                                        table.addText(column);
                                    }
                                }
                                default -> Logger.warn("Unknown type: " + type);
                            }
                            try{
                                table.setNotNull( rs.getBoolean(rs.findColumn("notnull")) );
                                table.setPrimaryKey( rs.getBoolean(rs.findColumn("pk")) );
                            }catch (SQLException e) {
                                Logger.error(e);
                                return false;
                            }
                        }
                    } catch (SQLException e) {
                        Logger.error( getID() + " -> Error during table read: "+e.getErrorCode());
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
     * Read the rollover and table settings from the given xml element
     * @param dbe The element with the setup info
     * @return Returns an optional sqliteDB
     */
    public static Optional<SQLiteDB> readFromXML( Element dbe, String workPath ){
        if( dbe == null )
            return Optional.empty();

        String id = XMLtools.getStringAttribute(dbe,"id","");
        var path = XMLtools.getPathAttribute(dbe,"path",Path.of(workPath));

        if( path.isEmpty() )
            return Optional.empty();

        SQLiteDB db = SQLiteDB.createDB(id,path.get());
        
        /* RollOver */
        var rollOpt = XMLtools.getFirstChildByTag(dbe, "rollover");
        if( rollOpt.isPresent()){
            var roll =rollOpt.get();
            int rollCount = XMLtools.getIntAttribute(roll, "count", 1);
            String unit = XMLtools.getStringAttribute(roll, "unit", "").toLowerCase();
            String format = roll.getTextContent();
            
            TimeTools.RolloverUnit rollUnit = TimeTools.convertToRolloverUnit( unit );
            if( rollUnit !=null){
                Logger.info("Setting rollover: "+format+" "+rollCount+" "+rollUnit);
                db.setRollOver(format,rollCount,rollUnit);
            }else{
                Logger.error(id+" -> Bad Rollover given" );
                return Optional.empty();
            }
        }
        /* Setup */
        XMLtools.getFirstChildByTag(dbe, "flush").ifPresent(db::readFlushSetup);

        // How many seconds before the connection is considered idle (and closed)
        db.idleTime = (int)TimeTools.parsePeriodStringToSeconds(XMLtools.getChildStringValueByTag(dbe,"idleclose","5m"));

        /* Views */
        for( Element view : XMLtools.getChildElements(dbe, "view")){
            String name = view.getAttribute("name");
            String query = view.getTextContent();
            db.views.add( "CREATE VIEW  IF NOT EXISTS "+name+" AS "+query);
        }

        /* Tables */
        XMLtools.getChildElements(dbe,"table").forEach(x -> SqlTable.readFromXml(x).ifPresent(table -> db.tables.put(table.name,table)));

        /* Create the content */
        db.getCurrentTables(false);
        db.lastError=db.createContent(false);
        return Optional.of(db);
    }

    /**
     * Write the settings from the database to xml
     * @param fab A Xmlfab pointing to the databases node as root
     */
    public void writeToXml( XMLfab fab ){
        String flush = TimeTools.convertPeriodtoString(maxAge, TimeUnit.SECONDS);
        String idle = "-1";
        if( idleTime!=-1)
            idle = TimeTools.convertPeriodtoString(maxAge, TimeUnit.SECONDS);

        fab.selectOrAddChildAsParent("sqlite","id", id).attr("path",dbPath.toString());
        if( hasRollOver() )
            fab.alterChild("rollover",oriFormat).attr("count",rollCount).attr("unit",rollUnit.toString().toLowerCase());
        fab.alterChild("flush").attr("age",flush).attr("batchsize",maxQueries)
           .alterChild("idleclose",idle)
           .build();
    }

    /* **************************************************************************************************/

    /**
     * Set the rollover for this sqlite
     * @param dateFormat The format part of the filename
     * @param rollCount The amount of unit
     * @param unit The unit for the rollover, options: MIN,HOUR,DAY,WEEK,MONTH,YEAR
     * @return This database
     */
    public SQLiteDB setRollOver( String dateFormat, int rollCount, TimeTools.RolloverUnit unit ){

        if(  unit == TimeTools.RolloverUnit.NONE || unit == null) {
            Logger.warn(id+" -> Bad rollover given");
            return this;
        }
        this.rollCount=rollCount;
        rollUnit=unit;
        oriFormat=dateFormat;

        format = DateTimeFormatter.ofPattern(dateFormat);
        rolloverTimestamp = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        rolloverTimestamp = TimeTools.applyTimestampRollover(true,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+" -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        updateFileName(rolloverTimestamp);

        rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+" -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));

        long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
        if( next > 1000) {
            rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            Logger.info(id+" -> Next rollover in "+TimeTools.convertPeriodtoString(rollOverFuture.getDelay(TimeUnit.SECONDS),TimeUnit.SECONDS));
        }else{
            Logger.error(id+" -> Bad rollover for "+rollCount+" counts and unit "+unit);
        }
        return this;
    }
    public SQLiteDB setRollOver( String dateFormat, int rollCount, String unit ){
        return setRollOver(dateFormat,rollCount, TimeTools.convertToRolloverUnit(unit));
    }
    /**
     * Cancel the next rollover events
     */
    public void cancelRollOver(){
        if( rollOverFuture!=null)
            rollOverFuture.cancel(true);
    }
    public void forceRollover(){
        scheduler.submit(new DoRollOver(false));
    }
    @Override
    public boolean disconnect(){
        if (con != null) {
            try {
                if (con.isClosed())
                    return false;

                if( hasRecords() ){
                    Logger.info(getID()+" has queries, flushing those first");
                    state = STATE.FLUSH_REQ;
                    try {
                        checkState(0);
                    } catch (Exception e) {
                        Logger.error(e);
                    }
                    int max=50;
                    while(hasRecords()&& max>=0){
                        try {
                            Thread.sleep(200);
                            max--;
                        } catch (InterruptedException e) {
                            Logger.error(e);
                        }
                    }
                }

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
     * Update the filename of the database currently used
     */
    public void updateFileName(LocalDateTime ldt){
        if( format==null)
            return;
        try{
            if( ldt!=null ){
                currentForm = ldt.format(format);
            }
        }catch( java.time.temporal.UnsupportedTemporalTypeException f ){
            Logger.error( getID() + " -> Format given is unsupported! Database creation cancelled.");
            return;
        }
        Logger.info("Updated filename after rollover to "+dbPath.toString());
    }
    /**
     * Check if this SQLite uses rollover
     * @return True if it has rollover
     */
    public boolean hasRollOver(){
        return rollUnit != TimeTools.RolloverUnit.NONE;
    }
    /**
     * After executing any queries still in the buffer: - closes the current
     * connection to the database - creates a new file and connects to it. - creates
     * the tables - if the rollover is every x months, schedule the next one
     */
    private class DoRollOver implements Runnable {
        boolean renew;

        public DoRollOver( boolean renew ){
            this.renew=renew;
        }
        @Override
        public void run() {
            Logger.info(id+" -> Doing rollover");
            if (!isValid(1)) {
                connect(false);
            }
            if(renew)
                updateFileName(rolloverTimestamp); // first update the filename

            getTables().forEach(SqlTable::clearReadFromDB); // Otherwise they won't get generated

            disconnect();// then disconnect, this also flushes the queries first
            Logger.info("Disconnected to connect to new one...");

            if( !createContent(true).isEmpty() ){
                Logger.error(id+" -> Failed to create the database");
            }
            if( renew ) {
                Logger.info(id+" -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
                Logger.info(id+" -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC), rolloverTimestamp).toMillis();
                rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Disconnect from the current database file and connect to the new one
     * @param newName The new name of the file. If not ending with .sqlite, this will be appended
     * @return True if no errors occurred
     */
    public boolean changeFilename( String newName ){
        getTables().forEach(SqlTable::clearReadFromDB); // Otherwise they won't get generated
        disconnect();// then disconnect, this also flushes the queries first
        Logger.info("Disconnected to connect to new one...");

        if( !newName.endsWith(".sqlite"))
            newName=newName+".sqlite";

        dbPath = dbPath.getParent().resolve(newName);

        if( !createContent(true).isEmpty() ){
            Logger.error(id+" -> Failed to create the database");
            return false;
        }
        return true;
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
                Logger.error("No such prepstatement found in " + id + ":" + table);
                break;
            case -2:
                Logger.error("No such table ("+table+") found in " + id);
                break;
        }
        return res;
    }
}