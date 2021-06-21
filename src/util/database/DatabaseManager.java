package util.database;

import org.influxdb.dto.Point;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DatabaseManager implements QueryWriting{
    
    private final Map<String, SQLiteDB> lites = new HashMap<>();
    private final Map<String, SQLDB> sqls = new HashMap<>();
    private final Map<String, Influx> influxes = new HashMap<>();

    private static final int CHECK_INTERVAL=5;
    private final ScheduledExecutorService scheduler;// scheduler for the request data action
    private static final String XML_PARENT_TAG = "databases";
    private String workPath;
    private Path settingsPath;
    /**
     * Create a manager that uses its own scheduler
     */
    public DatabaseManager( String workPath) {
        this.workPath=workPath;
        settingsPath = Path.of(workPath,"settings.xml");
        scheduler = Executors.newScheduledThreadPool(1);

        readFromXML();
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
        return !lites.isEmpty() || !sqls.isEmpty() || !influxes.isEmpty();
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
    private void readFromXML() {
        XMLfab.getRootChildren(settingsPath,"dcafs","settings","databases","sqlite")
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach( db -> addSQLiteDB(db.getAttribute("id"),SQLiteDB.readFromXML(db,workPath) ));

        XMLfab.getRootChildren(settingsPath,"dcafs","settings","databases","server")
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach( db -> {
                                    switch(db.getAttribute("type")){
                                        case "influx":
                                            addInfluxDB( db.getAttribute("id"), Influx.readFromXML(db) );
                                            break;
                                        case "":break;
                                        default:
                                            addSQLDB(db.getAttribute("id"), SQLDB.readFromXML(db));
                                            break;
                                    }
                                }
                        );
    }
    public Database reloadDatabase( String id ){
        var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","databases");
        var sqlite = fab.getChild("sqlite","id",id);
        if( sqlite.isPresent()){
            return addSQLiteDB(id,SQLiteDB.readFromXML( sqlite.get(),workPath));
        }else{
            var sqldb= fab.getChild("server","id",id);
            if( sqldb.isPresent())
                return addSQLDB(id, SQLDB.readFromXML(sqldb.get()));
        }
        return null;
    }

    /* ***************************************************************************************************************/
    /**
     * Run the queries of all the managed databases, mainly run before shutdown
     */
    public void flushAll() {
        lites.values().forEach( SQLiteDB::flushAll );
        sqls.values().forEach(SQLDB::flushAll);
    }
    /* **************************************  Q U E R Y W R I T I N G************************************************/
    @Override
    public int doDirectInsert(String id, String table, Object... values) {
        lites.entrySet().stream().filter(ent -> ent.getKey().equalsIgnoreCase(id)).forEach(db -> db.getValue().doDirectInsert(table,values));
        sqls.entrySet().stream().filter(ent -> ent.getKey().equalsIgnoreCase(id)).forEach(db -> db.getValue().doDirectInsert(table,values));
        int applied=0;
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id))
                return sqlite.doDirectInsert(table,values);
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id))
                return sqldb.doDirectInsert(table,values);
        }
        return 0;
    }

    @Override
    public boolean buildInsert(String id, String table, ConcurrentMap<String, Double> rtvals, ConcurrentMap<String, String> rttext, String macro) {
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id))
                return sqlite.buildInsert(table,rtvals,rttext,macro);
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id))
                return sqldb.buildInsert(table,rtvals,rttext,macro);
        }
        return false;
    }
    @Override
    public boolean addQuery( String id, String query){
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id)) {
                sqlite.addQuery(query);
                return true;
            }
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id)) {
                sqldb.addQuery(query);
                return true;
            }
        }
        return false;
    }
    @Override
    public boolean writeInfluxPoint( String id, Point p){
        for( Influx influx : influxes.values() ){
            if( influx.getID().equalsIgnoreCase(id)) {
                influx.writePoint(p);
                return true;
            }
        }
        return false;
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
            for( Influx db : influxes.values() ){
                try{
                    db.checkState(CHECK_INTERVAL);
                }catch(Exception e){
                    Logger.error(e);
                }
            }
        }
    }

    /**
     * Get the sum of all the max buffersizes
     * @return Sum of buffermaxes
     */
    public int getTotalMaxCount(){
        int total=0;
        for( var db : lites.values())
            total+=db.maxQueries;
        for( var db : sqls.values())
            total+=db.maxQueries;
        return total;
    }

    /**
     * Get the total amount of queries in memory
     * @return Buffered query count
     */
    public int getTotalQueryCount(){
        int total=0;
        for( var db : lites.values())
            total+=db.getRecordsCount();
        for( var db : sqls.values())
            total+=db.getRecordsCount();
        return total;
    }

    /**
     *
     * @param fab
     * @param type
     * @param id
     */
    public static void addBlankServerToXML( XMLfab fab, String type, String id ){
            fab.addParent("server").attr("id", id.isEmpty()?"remote":id).attr("type",type)
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
    public static boolean addBlankTableToXML( XMLfab fab, String id, String table, String format){

        var serverOpt = fab.selectParent("server","id",id);
        if( serverOpt.isPresent() ){
            fab.selectParent("server","id",id);
        }else{
            var sqliteOpt = fab.selectParent("sqlite","id",id);
            if( sqliteOpt.isEmpty())
                return false;
            fab.selectParent("sqlite","id",id);
        }
        SqlTable.addBlankToXML( fab,table,format );
        return true;
    }
}