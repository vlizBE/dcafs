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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SQLiteDB extends SQLDB{

    static final String GET_SQLITE_TABLES = "SELECT name FROM sqlite_master WHERE type ='table' AND name NOT LIKE 'sqlite_%';";
   
    private Path dbPath;

    /* Variables related to the rollover */
    private DateTimeFormatter format = null;
    private String oriFormat="";
    public enum RollUnit{NONE,MINUTE,HOUR,DAY,WEEK,MONTH,YEAR}
    private ScheduledFuture<?> rollOverFuture;
    private RollUnit rollUnit = RollUnit.NONE;
    private int rollCount = 0;

    private LocalDateTime rolloverTimestamp;

    private String currentForm = "";

    /**
     * Create an instance of a database with rollover
     * @param db Path to the database
     * @param dateFormat Format to use in the filename to show the rollover
     * @param rollCount How many of the unit are between rollovers
     * @param unit The unit of the rollover period
     */
    public SQLiteDB(String id, Path db, String dateFormat, int rollCount, RollUnit unit) {

        this.dbPath = db;
        this.id=id;

        if( db.getParent()==null){            
            dbPath = db.toAbsolutePath();
            Logger.info( getID() + " -> No parent given, so using absolute path: "+dbPath.toString());
        }
        
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            Logger.error( getID() + " -> Issue trying to create "+dbPath.getParent().toString());
        } catch (NullPointerException e ){
            Logger.error( getID() + " -> Issue trying to create db, path is null");
        }
        setRollOver(dateFormat,rollCount,unit);
    }    
    public SQLiteDB( String id,Path db ) {
        this( id,db,"",0,RollUnit.NONE);
    }
    /* **************************************************************************************************/
    public static SQLiteDB createDB( String id, Path db ){
        return new SQLiteDB( id, db );
    }
    public static SQLiteDB createDB( String id,Path db, String format,int count,  RollUnit unit ){
        return new SQLiteDB( id,db, format,count, unit );
    }
    /* **************************************************************************************************/
    @Override
    public String toString(){
        String status = getPath() +" -> " +getRecordsCount()+"/"+maxQueries;
        if( rollUnit!=RollUnit.NONE){
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
        return currentForm.isEmpty()?dbPath.toString():(dbPath.toString().replace(".sqlite", "")+currentForm+".sqlite");
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
        String path = dbPath.toString();
        if( format !=null ){
            if( path.endsWith(".sqlite") ){
                path =path.substring(0, path.length()-7);
            }
            String delimit = "";
            path += delimit;
        }
        if( !path.endsWith(".sqlite") )
            path+=currentForm+".sqlite";

        String irl = "jdbc:sqlite:"+path;  

        try{
            state = STATE.CON_BUSY;
            con = DriverManager.getConnection(irl, user, pass);
            con.setAutoCommit(false); //Changed
            Logger.info( getID() + " -> Connection: " + irl +con);
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
                        tables.get(tableName).toggleReadFromDB();;
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

                            switch( type.toLowerCase() ){
                                case "integer": table.addInteger(column); break;
                                case "real":    table.addReal(column); break;
                                case "text": 
                                    if( column.equalsIgnoreCase("timestamp")){
                                        table.addTimestamp(column);
                                    }else{
                                        table.addText(column);
                                    }
                                    break;
                                default: Logger.warn("Unknown type: "+type);break;
                            }
                            try{
                                table.isNotNull( rs.getBoolean(rs.findColumn("notnull")) );
                                table.isPrimaryKey( rs.getBoolean(rs.findColumn("pk")) );
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
     * Prepares a SQLiteTable object for a table with the given name, which will be using 'IF NOT EXISTS'
     * @param table The title of the table
     * @return The SQLiteTable object
     */
    public SqlTable addTableIfNotExists( String table ){
        tables.put( table, SqlTable.withName(table));
        tables.get(table).enableIfnotexists();
        return tables.get(table);
    }
    /**
     * Read the rollover and table settings from the given xml element
     * @param dbe The element with the setup info
     * @return true if successful
     */
    public static SQLiteDB readFromXML( Element dbe, String workPath ){
        if( dbe == null )
            return null;

        String id = XMLtools.getStringAttribute(dbe,"id","");
        var p = Path.of(dbe.getAttribute("path"));
        if( !p.isAbsolute() ){
            p = Path.of(workPath).resolve(p);
        }
        SQLiteDB db = SQLiteDB.createDB(id,p);
        
        /* RollOver */
        Element roll = XMLtools.getFirstChildByTag(dbe, "rollover");
        if( roll != null ){
            int rollCount = XMLtools.getIntAttribute(roll, "count", 1);
            String unit = XMLtools.getStringAttribute(roll, "unit", "").toLowerCase();
            String format = roll.getTextContent();
            
            RollUnit rollUnit=null;
            switch(unit){
                case "minute":case "min": rollUnit=RollUnit.MINUTE; break;
                case "hour": rollUnit=RollUnit.HOUR; break;
                case "day": rollUnit=RollUnit.DAY; break;
                case "week": rollUnit=RollUnit.WEEK; break;
                case "month": rollUnit=RollUnit.MONTH; break;
                case "year": rollUnit=RollUnit.YEAR; break;
            }
            if( rollUnit !=null){
                Logger.info("Setting rollover: "+format+" "+rollCount+" "+rollUnit);
                db.setRollOver(format, rollCount, rollUnit);
            }else{
                Logger.error(db.getID()+" -> Bad Rollover given" );
                return null;
            }
        }
        /* Setup */
        db.readBatchSetup(XMLtools.getFirstChildByTag(dbe, "setup"));

        /* Views */
        for( Element view : XMLtools.getChildElements(dbe, "view")){
            String name = view.getAttribute("name");
            String query = view.getTextContent();
            db.views.add( "CREATE VIEW  IF NOT EXISTS "+name+" AS "+query);
        }

        /* Tables */
        XMLtools.getChildElements(dbe,"table").stream().forEach( x -> {
            SqlTable.readFromXml(x).ifPresent(table -> db.tables.put(table.name,table));
        });

        /* Create the content */
        db.getCurrentTables(false);
        db.lastError=db.createContent(false);
        return db;
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

        fab.selectOrCreateParent("sqlite","id", id).attr("path",dbPath.toString());
        if( hasRollOver() )
            fab.alterChild("rollover",oriFormat).attr("count",rollCount).attr("unit",rollUnit.toString().toLowerCase());
        fab.alterChild("setup").attr("idletime",idle).attr("flushtime",flush).attr("batchsize",maxQueries)
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
    public SQLiteDB setRollOver( String dateFormat, int rollCount, RollUnit unit ){

        if(  unit == RollUnit.NONE )
            return this;

        this.rollCount=rollCount;
        rollUnit=unit;
        oriFormat=dateFormat;

        format = DateTimeFormatter.ofPattern(dateFormat);
        rolloverTimestamp = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        updateFileName(rolloverTimestamp);
        updateRolloverTimestamp();
        long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
        if( next > 1000) {
            rollOverFuture = scheduler.schedule(new DoRollOver(), next, TimeUnit.MILLISECONDS);
            Logger.info(id+" -> Next rollover in "+TimeTools.convertPeriodtoString(rollOverFuture.getDelay(TimeUnit.SECONDS),TimeUnit.SECONDS));
        }else{
            Logger.error(id+" -> Bad rollover for "+rollCount+" counts and unit "+unit);
        }
        return this;
    }
    public SQLiteDB setRollOver( String dateFormat, int rollCount, String unit ){
         RollUnit rollUnit=null;
            switch(unit){
                case "minute":case "min": rollUnit=RollUnit.MINUTE; break;
                case "hour": rollUnit=RollUnit.HOUR; break;
                case "day": rollUnit=RollUnit.DAY; break;
                case "week": rollUnit=RollUnit.WEEK; break;
                case "month": rollUnit=RollUnit.MONTH; break;
                case "year": rollUnit=RollUnit.YEAR; break;
        }
        return setRollOver(dateFormat,rollCount,rollUnit);
    }
    /**
     * Cancel the next rollover events
     */
    public void cancelRollOver(){
        if( rollOverFuture!=null)
            rollOverFuture.cancel(true);
    }
    public void forceRollover(){
        rollOverFuture.cancel(true);
        scheduler.submit(new DoRollOver());
    }
    /**
     * Update the filename of the database currently used
     * @return True if successful or not needed (if no rollover)
     */
    public boolean updateFileName(LocalDateTime ldt){
        if( format==null)
            return true;
        try{
            if( ldt!=null ){
                currentForm = ldt.format(format);
            }
        }catch( java.time.temporal.UnsupportedTemporalTypeException f ){
            Logger.error( getID() + " -> Format given is unsupported! Database creation cancelled.");
            return false;
        }
        return true;
    }

    /**
     * Create a view in this database
     * @param name The name of the view
     * @param query The query for the view
     * @return This database
     */
    public SQLiteDB addView( String name, String query ){
        addQuery("CREATE VIEW IF NOT EXISTS "+name+" AS "+query);
        return this;
    }

    /**
     * Alter the rollover timestamp to the next rollover moment
     */
    private void updateRolloverTimestamp(){
        Logger.debug(id+" -> Original date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        rolloverTimestamp = rolloverTimestamp.withSecond(0).withNano(0);

        if(rollUnit==RollUnit.MINUTE){
            int min = rollCount-rolloverTimestamp.getMinute()%rollCount; // So that 'every 5 min is at 0 5 10 15 etc
            rolloverTimestamp = rolloverTimestamp.plusMinutes(min==0?rollCount:min);//make sure it's not zero
        }else{
            rolloverTimestamp = rolloverTimestamp.withMinute(0);
            if(rollUnit==RollUnit.HOUR){
                rolloverTimestamp = rolloverTimestamp.plusHours( rollCount);
            }else{
                rolloverTimestamp = rolloverTimestamp.withHour(0);
                if(rollUnit==RollUnit.DAY){
                    rolloverTimestamp = rolloverTimestamp.plusDays( rollCount);
                }else{
                    if(rollUnit==RollUnit.WEEK){
                        rolloverTimestamp = rolloverTimestamp.minusDays(rolloverTimestamp.getDayOfWeek().getValue()-1);
                        rolloverTimestamp = rolloverTimestamp.plusWeeks(rollCount);
                    }else{
                        rolloverTimestamp = rolloverTimestamp.withDayOfMonth(1);
                        if(rollUnit==RollUnit.MONTH){
                            rolloverTimestamp=rolloverTimestamp.plusMonths(rollCount);
                        }else{
                            rolloverTimestamp = rolloverTimestamp.withMonth(1);
                            if(rollUnit==RollUnit.YEAR){
                                rolloverTimestamp=rolloverTimestamp.plusMonths(rollCount);
                            }
                        }
                    }
                }
            }
        }
        Logger.debug(id+" -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
    }

    /**
     * Check if this SQLite uses rollover
     * @return True if it has rollover
     */
    public boolean hasRollOver(){
        return rollUnit != RollUnit.NONE;
    }
    /**
     * After executing any queries still in the buffer: - closes the current
     * connection to the database - creates a new file and connects to it. - creates
     * the tables - if the rollover is every x months, schedule the next one
     */
    private class DoRollOver implements Runnable {

        @Override
        public void run() {
            Logger.info(id+" -> Doing rollover");
            if (!isValid(1)) {
                connect(false);
            }

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

            disconnect();

            updateFileName(rolloverTimestamp);
            updateRolloverTimestamp();
            if( !createContent(true).isEmpty() ){
                Logger.error(id+" -> Failed to create the database");
            }

            long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
            rollOverFuture = scheduler.schedule( new DoRollOver(),next,TimeUnit.MILLISECONDS);
        }
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
}