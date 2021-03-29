package util.database;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.tinylog.Logger;
import org.w3c.dom.Document;

import util.xml.XMLfab;
import util.database.SQLiteDB.RollUnit;
import util.xml.XMLtools;

public class DatabaseManager{
    
    private final Map<String, SQLiteDB> lites = new HashMap<>();
    private final Map<String, SQLDB> sqls = new HashMap<>();
    private final Map<String, Influx> influxes = new HashMap<>();

    private static final int CHECK_INTERVAL=5;
    private final ScheduledExecutorService scheduler;// scheduler for the request data action
    private static final String XML_PARENT_TAG = "databases";
    /**
     * Create a manager that uses the gives scheduler
     * 
     * @param scheduler
     */
    public DatabaseManager(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Create a manager that uses its own scheduler
     */
    public DatabaseManager() {
        scheduler = Executors.newScheduledThreadPool(1);
    }

    /**
     * Adds a SQLiteDB to the manager, this adds: - Check if the oldest query in the
     * buffer is older than the max age - Takes care of roll over - Adds the
     * listener
     * 
     * @param id The name to reference this database with
     * @param db The SQLiteDB
     * @return The database added
     */
    public SQLiteDB addSQLiteDB(String id, SQLiteDB db) {
        if (lites.size() == 0 && sqls.size() == 0)
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);

        SQLiteDB old = lites.get(id);
        if (old != null) // Check if we are overwriting an older version, and if so cancel any rollover
            old.cancelRollOver();

        lites.put(id, db);
        return db;
    }

    public SQLDB addSQLDB(String id, SQLDB db) {
        if (lites.size() == 0 && sqls.size() == 0)
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);
        sqls.put(id, db);
        return db;
    }
    public Influx addInfluxDB( String id, Influx db){
        influxes.put(id,db);
        return db;
    }
    /**
     * Check if the manager has a database with the given id
     * 
     * @return True if a database was found
     */
    public boolean hasDB(String id) {
        return lites.get(id) != null || sqls.get(id) != null;
    }

    public SQLiteDB getSQLiteDB(String id) {
        return lites.get(id);
    }
    public Database getDatabase( String id){
        SQLiteDB lite = lites.get(id);
        if( lite != null )
            return lite;
        return sqls.get(id);
    }
    public boolean hasDatabases() {
        return !lites.isEmpty() || !sqls.isEmpty();
    }
    /* ****************************************************************************************************************/
    /**
     * Get status update on the various managed databases
     * 
     * @return A string showing for each database: current filename, amount and max
     *         queries, if there's rollover
     */
    public String getStatus() {
        StringJoiner join = new StringJoiner("\r\n", "", "\r\n");
        lites.forEach((id, db) -> join.add( id + " : " + db.toString() ));
        sqls.forEach((id, db)  -> join.add( id + " : " + db.toString() + (db.isValid(1)?"":" (NC)")));
        influxes.forEach( (id,db) -> join.add( id+ " : " + db.toString() + (db.isValid(1)?"":" (NC)")));
        return join.toString();
    }

    /* ***************************************************************************************************************/
    /**
     * Run the queries of all the managed databases, mainly run before shutdown
     */
    public void flushAll() {
        lites.values().forEach( SQLiteDB::flushAll );
        sqls.values().forEach(SQLDB::flushAll);
    }

    /* **************************************  R U N N A B L E S ****************************************************/
    /**
     * Checks if the oldest query present in the buffer isn't older than the maximum
     * age. If so, the queries are executed
     */
    private class CheckQueryAge implements Runnable {
        @Override
        public void run() {
            for (SQLiteDB db : lites.values()) {
                try {
                    db.checkState(CHECK_INTERVAL);
                } catch (Exception e) {
                   Logger.error(e);
                }
            }
            for (SQLDB db : sqls.values()){
                try {
                    db.checkState(CHECK_INTERVAL);
                } catch (Exception e) {
                    Logger.error(e);
                }
            }
        }
    }

    public int getTotalQueryCount(){
        int total=0;
        for( var db : lites.values())
            total+=db.getRecordsCount();
        for( var db : sqls.values())
            total+=db.getRecordsCount();
        return total;
    }

    /**
     * Adds an empty server node to the databases node, if databases doesn't exist it will be created 
     * @param xml The loaded settings.xml
     */
    public static void addBlankServerToXML( Document xml, String type, String id ){
        XMLfab.withRoot(xml, "settings",XML_PARENT_TAG)                
                    .addParent("server").attr("id", id.isEmpty()?"remote":id).attr("type",type)
                        .addChild("db","name").attr("user").attr("pass")    
                        .addChild("setup").attr("idletime",-1).attr("flushtime","30s").attr("batchsize",30)                         
                        .addChild("address","localhost")
               .build();
    }
    /**
     * Adds an empty server node to the databases node, if databases doesn't exist it will be created 
     * @param xml The loaded settings.xml
     */
    public static void addBlankSQLiteToXML( Document xml, String id ){
        XMLfab.withRoot(xml, "settings",XML_PARENT_TAG)                
                    .addParent("sqlite").attr("id", id.isEmpty()?"lite":id).attr("path","db/"+id+".sqlite")
                        .addChild("rollover","yyMMdd").attr("count",1).attr("unit","day")                       
                        .addChild("setup").attr("idletime","2m").attr("flushtime","30s").attr("batchsize",30)                        
               .build();
    }
    public static boolean addBlankTableToXML( Document xml, String id, String table, String format){
        var opt = XMLfab.getRootChildren(xml, "das",XML_PARENT_TAG,"sqlite")
                .filter( db -> db.getAttribute("id").equals(id) )
                .findFirst();
        if( opt.isEmpty())
            return false;
        SqlTable.addBlankToXML( xml,opt.get(),table,format );
        XMLtools.updateXML(xml);
        return true;
    }
}