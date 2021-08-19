package util.database;

import das.Commandable;
import io.Writable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.data.DataProviding;
import org.influxdb.dto.Point;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DatabaseManager implements QueryWriting, Commandable {

    static final String UNKNOWN_CMD = "unknown command";

    private final Map<String, SQLiteDB> lites = new HashMap<>();
    private final Map<String, SQLDB> sqls = new HashMap<>();
    private final Map<String, InfluxDB> influxes = new HashMap<>();

    private static final int CHECK_INTERVAL=5;
    private final ScheduledExecutorService scheduler;// scheduler for the request data action
    private static final String XML_PARENT_TAG = "databases";
    private String workPath;
    private Path settingsPath;
    private DataProviding dataProvider;
    /**
     * Create a manager that uses its own scheduler
     */
    public DatabaseManager( String workPath, DataProviding dataProvider) {
        this.workPath=workPath;
        this.dataProvider=dataProvider;

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
    public InfluxDB addInfluxDB(String id, InfluxDB db){
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

    /**
     * Check if a database has a valid connection
     * @param id The id of the database
     * @param timeout The timeout in seconds to allow
     * @return True if it has a valid connection
     */
    public boolean isValid(String id,int timeout) {
        var db = getDatabase(id);
        if( db == null )
            return false;
        return db.isValid(timeout);
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
                                            addInfluxDB( db.getAttribute("id"), InfluxDB.readFromXML(db) );
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
    public boolean buildInsert(String ids, String table, DataProviding dp, String macro) {
        int ok=0;
        for( var id : ids.split(",")) {
           for (SQLiteDB sqlite : lites.values()) {
               if (sqlite.getID().equalsIgnoreCase(id))
                  ok+=sqlite.buildInsert(table, dp, macro)?1:0;
           }
           for (SQLDB sqldb : sqls.values()) {
               if (sqldb.getID().equalsIgnoreCase(id))
                   ok+=sqldb.buildInsert(table, dp, macro)?1:0;
           }
       }
       return ok==ids.split(",").length;
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

    /**
     * Run a select query on the given database
     * @param id The database to use
     * @param query The query to run
     * @return An optional result
     */
    public Optional<List<List<Object>>> doSelect(String id, String query){
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id)) {
                return sqlite.doSelect(query);
            }
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id)) {
                return sqldb.doSelect(query);
            }
        }
        return Optional.empty();
    }
    @Override
    public boolean writeInfluxPoint( String id, Point p){
        for( InfluxDB influxDB : influxes.values() ){
            if( influxDB.getID().equalsIgnoreCase(id)) {
                influxDB.writePoint(p);
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
            for( InfluxDB db : influxes.values() ){
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
            fab.addParentToRoot("server").attr("id", id.isEmpty()?"remote":id).attr("type",type)
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
                    .addParentToRoot("sqlite").attr("id", id.isEmpty()?"lite":id).attr("path","db/"+id+".sqlite")
                        .addChild("rollover","yyMMdd").attr("count",1).attr("unit","day")                       
                        .addChild("setup").attr("idletime","2m").attr("flushtime","30s").attr("batchsize",30)                        
               .build();
    }
    public static boolean addBlankTableToXML( XMLfab fab, String id, String table, String format){

        var serverOpt = fab.selectChildAsParent("server","id",id);
        if( serverOpt.isPresent() ){
            fab.selectChildAsParent("server","id",id);
        }else{
            var sqliteOpt = fab.selectChildAsParent("sqlite","id",id);
            if( sqliteOpt.isEmpty())
                return false;
            fab.selectChildAsParent("sqlite","id",id);
        }
        SqlTable.addBlankToXML( fab,table,format );
        return true;
    }
    /* ********************************** C O M M A N D A B L E *********************************************** */

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {

        if( request[0].equalsIgnoreCase("myd"))
            return doMYsqlDump(request,wr,html);

        String[] cmds = request[1].split(",");

        StringJoiner join = new StringJoiner(html?"<br":"\r\n");
        Database db=null;

        String id = cmds.length>=2?cmds[1]:"";
        String dbName = cmds.length>=3?cmds[2]:"";
        String address = cmds.length>=4?cmds[3]:"";
        String user = cmds.length>=5?cmds[4]:"";
        String pass="";

        if( user.contains(":")){
            pass = user.substring(user.indexOf(":")+1);
            user = user.substring(0,user.indexOf(":"));
        }

        switch( cmds[0] ){
            case "?":
                join.add(TelnetCodes.TEXT_MAGENTA+"The databasemanager connects to databases, handles queries and fetches table information");
                join.add(TelnetCodes.TEXT_GREEN+"Glossary"+TelnetCodes.TEXT_YELLOW)
                        .add("  alias -> the alias of a column is the reference to use instead of the column name to find the rtval, empty is not used")
                        .add("  macro -> an at runtime determined value that can be used to define the rtval reference").add("");
                join.add(TelnetCodes.TEXT_GREEN+"Connect to a database"+TelnetCodes.TEXT_YELLOW)
                        .add("  dbm:addmssql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
                        .add("  dbm:addmysql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
                        .add("  dbm:addmariadb,id,db name,ip:port,user:pass -> Adds a MariaDB server on given ip:port with user:pass")
                        .add("  dbm:addsqlite,id(,filename) -> Creates an empty sqlite database, filename and extension optional default db/id.sqlite")
                        .add("  dbm:addinfluxdb,id,db name,ip:port,user:pass -> Adds a Influxdb server on given ip:port with user:pass")
                        .add("").add(TelnetCodes.TEXT_GREEN+"Working with tables"+TelnetCodes.TEXT_YELLOW)
                        .add("  dbm:addtable,id,tablename,format (format eg. tirc timestamp(auto filled system time),int,real,char/text)")
                        .add("  dbm:addcol,<dbid:>tablename,columntype:columnname<,alias (columntypes r(eal),t(ime)s(tamp),i(nteger),t(ext)")
                        .add("  dbm:tablexml,id,tablename -> Write the table in memory to the xml file, use * as tablename for all")
                        .add("  dbm:tables,id -> Get info about the given id (tables etc)")
                        .add("  dbm:fetch,id -> Read the tables from the database directly, not overwriting stored ones.")
                        .add("  dbm:store,dbId,tableid -> Trigger a insert for the database and table given")
                        .add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
                        .add("  dbm:addserver,id -> Adds a blank database server node to xml")
                        .add("  dbm:addrollover,id,count,unit,pattern -> Add rollover to a SQLite database")
                        .add("  dbm:alter,id,param:value -> Alter things like idle, flush and batch (still todo)")
                        .add("  dbm:reload,id -> (Re)loads the database with the given id fe. after changing the xml")
                        .add("  dbm:status -> Show the status of all managed database connections")
                        .add("  st -> Show the current status of the databases (among other things)");
                return join.toString();
            case "reload":
                if( cmds.length<2)
                    return "No id given";
                var dbr = reloadDatabase(cmds[1]);
                if( dbr!=null){
                    String error = dbr.getLastError();
                    return error.isEmpty()?"Database reloaded":error;
                }
                return "No such database found";
            case "addserver":
                DatabaseManager.addBlankServerToXML( XMLfab.withRoot(settingsPath, "settings","databases"), "mysql", cmds.length>=2?cmds[1]:"" );
                return "Added blank database server node to the settings.xml";
            case "addmysql":
                var mysql = SQLDB.asMYSQL(address,dbName,user,pass);
                mysql.setID(id);
                if( mysql.connect(false) ){
                    mysql.getCurrentTables(false);
                    mysql.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
                    addSQLDB(id,mysql);
                    return "Connected to MYSQL database and stored in xml as id "+id;
                }else{
                    return "Failed to connect to database.";
                }
            case "addmssql":
                var mssql = SQLDB.asMSSQL(address,dbName,user,pass);
                mssql.setID(id);
                if( mssql.connect(false) ){
                    mssql.getCurrentTables(false);
                    mssql.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
                    addSQLDB(id,mssql);
                    return "Connected to MYSQL database and stored in xml as id "+id;
                }else{
                    return "Failed to connect to database.";
                }
            case "addmariadb":
                if( cmds.length<5)
                    return "Not enough arguments: dbm:addmariadb,id,db name,ip:port,user:pass";
                var mariadb = SQLDB.asMARIADB(address,dbName,user,pass);
                mariadb.setID(id);
                if( mariadb.connect(false) ){
                    mariadb.getCurrentTables(false);
                    mariadb.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
                    addSQLDB(id,mariadb);
                    return "Connected to MariaDB database and stored in xml with id "+id;
                }else{
                    return "Failed to connect to database.";
                }
            case "addpostgresql":
                if( cmds.length<5)
                    return "Not enough arguments: dbm:addpostgresql,id,db name,ip:port,user:pass";
                var postgres = SQLDB.asPOSTGRESQL(address,dbName,user,pass);
                postgres.setID(id);
                if( postgres.connect(false) ){
                    postgres.getCurrentTables(false);
                    postgres.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
                    addSQLDB(id,postgres);
                    return "Connected to PostgreSQL database and stored in xml with id "+id;
                }else{
                    return "Failed to connect to database.";
                }
            case "addsqlite":
                if( !dbName.contains(File.separator))
                    dbName = "db"+File.separator+(dbName.isEmpty()?id:dbName);
                if(!dbName.endsWith(".sqlite"))
                    dbName+=".sqlite";

                var sqlite = SQLiteDB.createDB(id,Path.of(dbName).isAbsolute()?"":workPath,Path.of(dbName));
                if( sqlite.connect(false) ){
                    addSQLiteDB(id,sqlite);
                    sqlite.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases") );
                    return "Created SQLite at "+dbName+" and wrote to settings.xml";
                }else{
                    return "Failed to create SQLite";
                }
            case "tablexml":
                if( cmds.length<3)
                    return "Not enough arguments: dbm:tablexml,dbid,tablename";
                var dbOpt = getDatabase(cmds[1]);
                if( dbOpt == null)
                    return "No such database "+cmds[1];
                // Select the correct server node
                var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","databases");
                if( fab.selectChildAsParent("server","id",cmds[1]).isEmpty())
                    fab.selectChildAsParent("sqlite","id",cmds[1]);
                if( fab.hasChild("table","name",cmds[2]).isPresent())
                    return "Already present in xml, not adding";

                if( dbOpt instanceof SQLDB){
                    int rs= ((SQLDB) dbOpt).writeTableToXml(fab,cmds[2]);
                    return rs==0?"None added":"Added "+rs+" tables to xml";
                }else{
                    return "Not a valid database target (it's an influx?)";
                }

            case "addrollover":
                if( cmds.length < 5 )
                    return "Not enough arguments, needs to be dbm:addrollover,dbId,count,unit,pattern";
                var s= getSQLiteDB(cmds[1]);
                if( s == null)
                    return cmds[1] +" is not an SQLite";
                s.setRollOver(cmds[4], NumberUtils.createInteger(cmds[2]),cmds[3]);
                s.writeToXml(XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
                s.forceRollover();
                return "Rollover added";
            case "addinfluxdb": case "addinflux":
                var influx = new InfluxDB(address,dbName,user,pass);
                if( influx.connect(false)){
                    addInfluxDB(id,influx);
                    influx.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases") );
                    return "Connected to InfluxDB and stored it in xml with id "+id;
                }else{
                    return "Failed to connect to InfluxDB";
                }
            case "addtable":
                if( cmds.length < 3 )
                    return "Not enough arguments, needs to be dbm:addtable,dbId,tableName<,format>";
                if( DatabaseManager.addBlankTableToXML( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"), cmds[1], cmds[2], cmds.length==4?cmds[3]:"" ) ) {
                    if( cmds.length==4)
                        return "Added a partially setup table to " + cmds[1] + " in the settings.xml, edit it to set column names etc";
                    return "Created tablenode for "+cmds[1]+" inside the db node";
                }
                return "No such database found nor influxDB.";
            case "addcolumn": case "addcol":
                if( cmds.length < 3 )
                    return "Not enough arguments, needs to be dbm:addcolumn,<dbId:>tablename,columntype:columnname<,alias>";
                if(!cmds[2].contains(":"))
                    return "Needs to be columtype:columnname";
                String dbid =  cmds[1].contains(":")?cmds[1].split(":")[0]:"";
                String table = cmds[1].contains(":")?cmds[1].split(":")[1]:cmds[1];
                String[] col = cmds[2].split(":");
                String alias = cmds.length==4?cmds[3]:"";

                switch(col[0]){
                    case "ts":col[0]="timestamp";break;
                    case "i":col[0]="integer";break;
                    case "r":col[0]="real";break;
                    case "text":col[0]="text";break;
                }

                fab = XMLfab.withRoot(settingsPath,"settings","databases");
                for( var dbtype : new String[]{"database","sqlite"}) {
                    for (var ele : fab.getChildren(dbtype)) {
                        if (!dbid.isEmpty() && !dbid.equalsIgnoreCase(ele.getAttribute("id")))
                            continue;
                        for (var tbl : XMLtools.getChildElements(ele, "table")) {
                            if (tbl.getAttribute("name").equalsIgnoreCase(table)) {
                                fab.selectOrAddChildAsParent(dbtype, "id", ele.getAttribute("id"))
                                        .selectOrAddChildAsParent("table", "name", table)
                                        .addChild(col[0], col[1]);
                                if( !alias.isEmpty()) {
                                    fab.attr("alias", alias).build();
                                }else{
                                    fab.build();
                                }
                                return "Column added: " + col[0] + "->" + col[1] + (alias.isEmpty() ? "" : " with alias " + alias);
                            }
                        }
                    }
                }
                return "Nothing added";
            case "fetch":
                if( cmds.length < 2 )
                    return "Not enough arguments, needs to be dbm:fetch,dbId";
                db = getDatabase(cmds[1]);
                if( db==null)
                    return "No such database";
                if( db.getCurrentTables(false) )
                    return "Tables fetched, run dbm:tables,"+cmds[1]+ " to see result.";
                if( db.isValid(1) )
                    return "Failed to get tables, but connection valid...";
                return "Failed to get tables because connection not active.";
            case "tables":
                if( cmds.length < 2 )
                    return "Not enough arguments, needs to be dbm:tables,dbId";
                db = getDatabase(cmds[1]);
                if( db==null)
                    return "No such database";
                return db.getTableInfo(html?"<br":"\r\n");
            case "alter":
                return "Not yet implemented";
            case "status": case "list":
                return getStatus();
            case "store":
                if( cmds.length < 3 )
                    return "Not enough arguments, needs to be dbm:store,dbId,tableid";
                if( buildInsert(cmds[1],cmds[2],dataProvider,"") )
                    return "Wrote record";
                return "Failed to write record";
            default:
                return UNKNOWN_CMD+": "+request[0]+":"+request[1];
        }
    }
    public String doMYsqlDump(String[] request, Writable wr, boolean html ){
        String[] cmds = request[1].split(",");
        switch( cmds[0] ){
            case "?": 	return " myd:run,dbid,path -> Run the mysqldump process for the given database";
            case "run":
                if( cmds.length != 3 )
                    return "Not enough arguments, must be mysqldump:run,dbid,path";
                Database db = getDatabase(cmds[1]);
                if( db == null )
                    return "No such database "+cmds[1];
                if( db instanceof SQLiteDB )
                    return "Database is an sqlite, not mysql/mariadb";
                if( db instanceof SQLDB ){
                    SQLDB sql =(SQLDB)db;
                    if( sql.isMySQL() ){
                        // do the dump
                        String os = System.getProperty("os.name").toLowerCase();
                        if( !os.startsWith("linux")){
                            return "Only Linux supported for now.";
                        }
                        try {
                            ProcessBuilder pb = new ProcessBuilder("bash","-c", "mysqldump "+sql.getTitle()+" > "+cmds[2]+";");
                            pb.inheritIO();
                            Process process;

                            Logger.info("Started dump attempt at "+ TimeTools.formatLongUTCNow());
                            process = pb.start();
                            process.waitFor();
                            // zip it?
                            if( Files.exists(Path.of(workPath,cmds[2]))){
                                if(FileTools.zipFile(Path.of(workPath,cmds[2]))==null) {
                                    Logger.error("Dump of "+cmds[1]+" created, but zip failed");
                                    return "Dump created, failed zipping.";
                                }
                                // Delete the original file
                                Files.deleteIfExists(Path.of(workPath,cmds[2]));
                            }else{
                                Logger.error("Dump of "+cmds[1]+" failed.");
                                return "No file created...";
                            }
                            Logger.info("Dump of "+cmds[1]+" created, zip made.");
                            return "Dump finished and zipped at "+TimeTools.formatLongUTCNow();
                        } catch (IOException | InterruptedException e) {
                            Logger.error(e);
                            Logger.error("Dump of "+cmds[1]+" failed.");
                            return "Something went wrong";
                        }
                    }else{
                        return "Database isn't mysql/mariadb";
                    }
                }else{
                    return "Database isn't regular SQLDB";
                }
            default:
                return UNKNOWN_CMD+": "+request[0]+":"+request[1];
        }
    }
}