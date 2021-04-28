package util.database;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

public class SqlTable {

    String name = "";

    enum COLUMN_TYPE {
        INTEGER, REAL, TEXT, TIMESTAMP, EPOCH, OBJECT, LOCALDTNOW, UTCDTNOW, DATETIME
    }

    ArrayList<Column> columns = new ArrayList<>();
    boolean ifnotexists = false;
    boolean server = false;

    ArrayList<Object[]> data = new ArrayList<>(); // The data for the default insert statement
    String prepStatement = "";  // The default insert statement

    PrepStatement defaultPrep;
    HashMap<String,PrepStatement> preps = new HashMap<>();
    String lastError="";

    private boolean readFromDatabase=false;

    public SqlTable(String name) {
        this.name = name;
        preps.put("", new PrepStatement());
    }
    public void toggleServer(){
        server=true;
    }
    public void setLastError(String error ){
        this.lastError=error;
    }
    public String getLastError( boolean clear ){
        String t = lastError;
        if( clear)
            lastError= "";
        return t;
    }
    public static Optional<SqlTable> readFromXml(Element tbl) {
        String tableName = tbl.getAttribute("name").trim();
        SqlTable table = SqlTable.withName(tableName);
        boolean ok = true;
        for (Element node : XMLtools.getChildElements(tbl)) {
            if (node != null) {
                String val = node.getTextContent().trim();
                if (val.equals(".")) {
                    Logger.error("Column still without a name! " + tableName);
                    ok = false;
                    break;
                }
                String alias = XMLtools.getStringAttribute(node,"alias","");

                switch (node.getNodeName()) {
                    case "real":
                        table.addReal(val, alias);
                        break;
                    case "integer":case "int":
                        table.addInteger(val, alias);
                        break;
                    case "timestamp":
                        if (alias.isEmpty()) {
                            table.addTimestamp(val);
                        } else {
                            table.addText(val, alias);
                        }
                        break;
                    case "millis":
                        if (alias.isEmpty()) {
                            table.addEpochMillis(val);
                        } else {
                            table.addInteger(val, alias);
                        }
                        break;
                    case "text":
                        table.addText(val, alias);
                        break;
                    case "localdtnow": table.addLocalDateTime(val, alias,true); break;
                    case "utcdtnow": table.addUTCDateTime(val, alias,true); break;
                    case "datetime": table.addLocalDateTime(val, alias,false); break;
                }

                /* Setup of the column */
                String setup = node.getAttribute("setup").toLowerCase();
                table.isPrimaryKey(setup.contains("primary"));
                table.isNotNull(setup.contains("notnull"));
                table.isUnique(setup.contains("unique"));
                if (node.hasAttribute("def"))
                    table.withDefault(node.getAttribute("def"));
            }
        }
        if (ok)
            return Optional.ofNullable(table);
        return Optional.empty();
    }
    /**
     * Create a SQLiteTable object for a table with the given name
     * 
     * @param name The name of the table
     * @return The created object
     */
    public static SqlTable withName(String name) {
        return new SqlTable(name);
    }
    public void toggleReadFromDB(){
        readFromDatabase=true;
    }
    public boolean isReadFromDB(){
        return readFromDatabase;
    }
    /**
     * Get the name of the table
     * 
     * @return The table name
     */
    public String getName() {
        return name;
    }

    public boolean hasIfNotExists() {
        return ifnotexists;
    }

    public SqlTable enableIfnotexists() {
        ifnotexists = true;
        return this;
    }

    /**
     * Add a column that contains integer data
     * 
     * @param title The title of the oolumn
     * @return This object
     */
    public SqlTable addInteger(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.INTEGER));        
        return this;
    }

    /**
     * Add a column that contains integer data, using the given alias to link to
     * rtvals
     * 
     * @param title The title of the oolumn
     * @param alias The alias to use to find the data
     * @return This object
     */
    public SqlTable addInteger(String title, String alias, int def) {        
        addColumn( new Column(title, alias, COLUMN_TYPE.INTEGER) );
        return this;
    }

    public SqlTable addInteger(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.INTEGER));
        return this;
    }

    /**
     * Add a column that contains real data
     * 
     * @param title The title of the oolumn
     * @return This object
     */
    public SqlTable addReal(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.REAL));
        return this;
    }

    /**
     * Add a column that contains real data, using the given alias to link to rtvals
     * 
     * @param title The title of the oolumn
     * @param alias The alias to use to find the data
     * @return This object
     */
    public SqlTable addReal(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.REAL));
        return this;
    }

    /**
     * Add a column that contains text data
     * 
     * @param title The title of the oolumn
     * @return This object
     */
    public SqlTable addText(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.TEXT));
        return this;
    }

    /**
     * Add a column that contains text data, using the given alias to link to rtvals
     * 
     * @param title The title of the oolumn
     * @param alias The alias to use to find the data
     * @return This object
     */
    public SqlTable addText(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.TEXT));
        return this;
    }

    /* Timestamp */
    /**
     * Add a column that contains timestamp data (in text format)
     * 
     * @param title The title of the oolumn
     * @return This object
     */
    public SqlTable addTimestamp(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.TIMESTAMP));
        return this;
    }

    /**
     * Add a column that contains timestamp data in text format, using the given
     * alias to link to rtvals
     * 
     * @param title The title of the oolumn
     * @param alias The alias to use to find the data
     * @return This object
     */
    public SqlTable addTimestamp(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.TIMESTAMP));
        return this;
    }
    public SqlTable addLocalDateTime(String title, String alias,boolean now) {
        addColumn(new Column(title, alias, now?COLUMN_TYPE.LOCALDTNOW:COLUMN_TYPE.DATETIME));
        return this;
    }
    public SqlTable addUTCDateTime(String title, String alias,boolean now) {
        addColumn(new Column(title, alias, now?COLUMN_TYPE.UTCDTNOW:COLUMN_TYPE.DATETIME));
        return this;
    }
    /* Epoc Millis */
    /**
     * Add a column that contains timestamp data (in integer format).
     * 
     * @param title The title of the oolumn
     * @return This object
     */
    public SqlTable addEpochMillis(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.EPOCH));
        return this;
    }

    /**
     * Add a column that contains timestamp data in integer format, using the given
     * alias to link to rtvals
     * 
     * @param title The title of the oolumn
     * @param alias The alias to use to find the data
     * @return This object
     */
    public SqlTable addEpochMillis(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.EPOCH));
        return this;
    }
    public SqlTable addObject(String title, String alias) {
        addColumn(new Column(title, alias, COLUMN_TYPE.INTEGER));
        return this;
    }
    public SqlTable withDefault(String def) {
        int index = columns.size() - 1;
        columns.get(index).setDefault(def);
        return this;
    }
    /**
     * Add a column to the collection of columns, this also updates the PreparedStatement
     * @param c The column to add
     */
    private void addColumn( Column c ){
        columns.add(c);
        preps.get("").addColumn(columns.size()-1);
    }
    /**
     * Define that the last created column is the primary key
     * 
     * @return This object
     */
    public SqlTable isPrimaryKey() {
        int index = columns.size() - 1;
        columns.get(index).primary = true;
        return this;
    }

    /**
     * Define whether or not the last created column is the primary key
     * 
     * @param pk True if primary key, false if not
     * @return This object
     */
    public SqlTable isPrimaryKey(boolean pk) {
        int index = columns.size() - 1;
        columns.get(index).primary = pk;
        return this;
    }

    /**
     * Define that the last created column is not allowed to be null
     * 
     * @return This object
     */
    public SqlTable isNotNull() {
        int index = columns.size() - 1;
        columns.get(index).notnull = true;
        return this;
    }

    /**
     * Define whether or not the last created column is not allowed to be null
     * 
     * @param nn True if not allowed to be null, false if so
     * @return This object
     */
    public SqlTable isNotNull(boolean nn) {
        int index = columns.size() - 1;
        columns.get(index).notnull = nn;
        return this;
    }

    /**
     * Define that the last created column must only contain unique values
     * 
     * @return This object
     */
    public SqlTable isUnique() {
        int index = columns.size() - 1;
        columns.get(index).unique = true;
        return this;
    }

    public SqlTable isUnique(boolean unique) {
        columns.get(columns.size() - 1).unique = unique;
        return this;
    }

    /**
     * Remove a single column from the table format
     * 
     * @param title The
     * @return
     */
    public int removeColumn(String title) {
        int a = columns.size();
        columns.removeIf(x -> x.title.equalsIgnoreCase(title));
        return a - columns.size();
    }

    /**
     * Get the amount of columns in this table
     * 
     * @return The column count
     */
    public int getColumnCount() {
        return columns.size();
    }

    /**
     * Check if this table has columns
     * 
     * @return True if it is not empty
     */
    public boolean hasColumns() {
        return !columns.isEmpty();
    }

    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String create() {
        return toString();
    }

    /**
     * Get all the info about this table
     * 
     * @return Info message
     */
    public String getInfo() {
        StringJoiner join = new StringJoiner("\r\n", "Table '" + name + "'\r\n", "");
        for (Column column : columns) {
            join.add("> " + column.toString()
                    + (column.alias.equals(column.title) ? "" : " (alias=" + column.alias + ")"));
        }
        return join + "\r\n";
    }

    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String toString() {
        StringJoiner join = new StringJoiner(", ",
                "CREATE TABLE " + (ifnotexists ? "IF NOT EXISTS" : "") + " " + name + " (", " );");
        columns.forEach(x -> join.add(x.toString()));
        return join.toString();
    }

    /**
     * Get a stringjoiner that has been set up to create the INSERT statement and
     * needs the values added
     * 
     * @return The stringjoiner to create the INSERT statement
     */
    public StringJoiner getInsertJoiner() {
        StringJoiner cols = new StringJoiner(",");
        for (Column col : columns) {
            cols.add(col.title);
        }
        return new StringJoiner(",", "INSERT INTO " + name + " (" + cols + ") VALUES (", ");");
    }
    public int getRecordCount() {        
        return preps.values().stream().mapToInt( p -> p.getData().size()).sum();        
    }
    public boolean hasRecords(){
        return preps.values().stream().anyMatch( p -> !p.getData().isEmpty());
    }
    public boolean hasRecords(String id){
        return getPrep(id).map( p -> !p.getData().isEmpty()).orElse(false);
    }
    private Optional<PrepStatement> getPrep( String id ){
        return Optional.ofNullable(preps.get(id));
    }
    public String getPreparedStatement( String id ) {
        if( id.isEmpty())
            return getPreparedStatement();
        return getPrep(id).map( PrepStatement::getStatement).orElse("");
    }
    public Set<String> getPreps(){
        return preps.keySet();
    }
    public String getPreparedStatement() {
        PrepStatement prep = preps.get("");
        if( prep.getStatement().isEmpty() )
            buildDefStatement();
        return prep.getStatement();
    }
    public int fillStatement( String id, PreparedStatement ps ) {
        PrepStatement prep = preps.get(id);
        if( prep==null || ps==null)
            return -1;

        int count=0;
        int size = prep.getData().size();
        for (int a=0;a<size;a++) { //foreach can cause concurrency issues

            if( size > prep.getData().size() ){
                Logger.error(name+":"+(id.isEmpty()?"def":id) +" -> Data shrunk during processing...? ori:"+size+" now "+prep.getData().size());
                return -3;
            }
            Object[] d = prep.getData().get(a);
            if( d==null ){
                Logger.error( name+":"+(id.isEmpty()?"def":id)+" -> Asked for a record at "+a+" which is null... skipping");
                continue;
            }
            int index = 0;
            try {
                for ( int colIndex : prep.getIndexes() ) {
                    if(  d[index] == null){
                        return -3;
                    }
                    Column c = columns.get(colIndex);
                    try{     
                        ps.setObject( index+1,d[index] );
                        index++;
                    }catch( java.lang.ClassCastException | NullPointerException e){
                        Logger.error(name+":"+id+" -> Failed to cast "+d[index]+" to "+c.type);
                        Logger.error(e);
                        break;
                    }                    
                }
                count++;                
                ps.addBatch();
            } catch ( Exception e ) {
                Logger.error(e);
                return -1;
            } 
        }
        return count;
    }
    public int fillStatement( PreparedStatement ps ) {
        return fillStatement( "",ps);
    }
    public int clearRecords( String id, long[] updateCounts ){
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return -1;
        }
        var dd = prep.getData();
        int offset=0;
        prep.enableLock();
        for( int index=0;index<updateCounts.length;index++){
            if( updateCounts[index]==Statement.EXECUTE_FAILED){                
                dd.remove(index-offset);
                offset++;      
            }  
        }
        prep.disableLock();
        return offset;
    }
    public boolean clearRecords( String id, int count ){
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return false;
        }
        prep.enableLock();
        var dd = prep.getData();
        if (count > 0) {
            dd.subList(0, count).clear();
        }

        if( !dd.isEmpty() ){
            Logger.debug(id+" -> Not all records removed ("+dd.size()+" left)");
            // Probably not needed in live situations? Not sure how those are introduced
            dd.removeIf( Objects::isNull );
        }
        prep.disableLock();
        return true;
    }
    public void clearRecords( int count ){
        clearRecords( "",count);  
    }
    public int doInsert(String id, Object[] values){
        return getPrep(id).map( p -> p.addData(values)?1:0).orElse(-1);
    }
    public int doInsert(Object[] values){
        return getPrep("").map( p -> p.addData(values)?1:0).orElse(-1);
    }
    /**
     * Use the given rtvals to fill in the create statement, alias/title must match elements
     * @param rtvals The rtvals object containing the values
     * @return The INSERT statement or an empty string if a value wasn't found
     */
    public boolean buildInsert( ConcurrentMap<String,Double> rtvals,ConcurrentMap<String,String> rttext ){
        return buildInsert("",rtvals,rttext,"");
    }
    public boolean buildInsert( ConcurrentMap<String,Double> rtvals,ConcurrentMap<String,String> rttext,String macro ){
        return buildInsert("",rtvals,rttext,macro);
    }
    /**
     * Use the given rtvals object and macro to fill in the INSERT statement (@macro defined in xml)
     * @param rtvals The rtvals object containing the values
     * @param macro The string to replace the @macro in the alias with
     * @return The INSERT statement or an empty string if a value wasn't found
     */
    public boolean buildInsert( String id, ConcurrentMap<String,Double> rtvals,ConcurrentMap<String,String> rttext, String macro ){
       
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return false;
        }

        Object[] record = new Object[columns.size()];
        int index=-1;
        for( int colPos : prep.getIndexes() ){
            Column col = columns.get(colPos);
            index++;    
            String def = col.getDefault();
            if( def.equalsIgnoreCase("@macro"))
                def=macro;
            
            String ref = col.alias.replace("@macro", macro);
            Object val = null;
            try{

                if( col.type==COLUMN_TYPE.TIMESTAMP ){
                    record[index] = index==0?TimeTools.formatLongUTCNow():rttext.get(ref);
                    continue;
                }else if( col.type == COLUMN_TYPE.EPOCH){
                    record[index]=Instant.now().toEpochMilli();
                    continue;
                }else if( col.type == COLUMN_TYPE.TEXT){
                    val = rttext.get(ref);
                }else if( col.type == COLUMN_TYPE.INTEGER){
                    val = rtvals.get(ref);
                    if( val!=null) {
                        if( val instanceof Double){
                            val = ((Double)val).intValue();
                        }
                    }
                }else if( col.type == COLUMN_TYPE.REAL){
                    val = rtvals.get(ref);
                }else if( col.type == COLUMN_TYPE.LOCALDTNOW){
                    val = LocalDateTime.now();
                }else if( col.type == COLUMN_TYPE.UTCDTNOW){
                    val = OffsetDateTime.now(ZoneOffset.UTC);
                }else if( col.type == COLUMN_TYPE.DATETIME){
                    val = TimeTools.parseDateTime(ref,TimeTools.SQL_LONG_FORMAT);
                }
            }catch( NullPointerException e ){
                Logger.error("Null pointer when looking for "+ref + " type:"+col.type);
            }

            if( val == null ){
                if( col.hasDefault ){
                    record[index]= def;
                }else{
                    Logger.error("Tried to create insert for "+name+" but couldn't find: "+ref);
                    return false;
                }
            }else{
                record[index] = val;
            }
        }        
        return prep.addData(record);
    }
    /**
     * Inner class that holds all the info regarding a single column
     */
    private class Column{
        COLUMN_TYPE type;
        String title="";
        String alias="";
        boolean unique=false;
        boolean notnull=false;
        boolean primary=false;

        boolean hasDefault=false;
        String defString="";

        public Column( String title, String alias, COLUMN_TYPE type){
            this.title=title;
            if( alias.equals("")) // if no alias is given, we assume it's the same as the title
                alias=name+"_"+title;
            this.alias=alias;
            this.type=type;
            switch( type ){
                case TIMESTAMP: case EPOCH: notnull=true; break; // these aren't allowed to be null by default
                case INTEGER: 
                case REAL: 
                case TEXT: 
                default:
                    break;
            }
        }
        public void setDefault(String def){
            this.defString=def;
            hasDefault=true;
        }
        public String getDefault(){
            if( type==COLUMN_TYPE.TEXT ){
                return "'"+defString+"'";
            }else{
                return defString;
            }
        }
        /**
         * Get the string that will be used in the CREATE statement for this column
         */
        public String toString(){ 
            
            if( type == COLUMN_TYPE.TIMESTAMP && !server ) // Timestamp should be timestamp on a server
                return title+" TEXT" + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
            if( type == COLUMN_TYPE.EPOCH )
                return title+" REAL" + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");

            return title+" "+type + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
        }
    }
	public static void addBlankToXML(Document xml, Element db, String table, String format) {
        Element tab = XMLtools.createChildElement(xml, db, "table");
        tab.setAttribute("name", table);
        for( char c : format.toCharArray() ){
            switch(c){
                case 't': XMLtools.createChildTextElement(xml, tab, "timestamp","columnname"); break;
                case 'r': XMLtools.createChildTextElement(xml, tab, "real","columnname"); break;
                case 'i': XMLtools.createChildTextElement(xml, tab, "integer","columnname"); break;
                case 'c': XMLtools.createChildTextElement(xml, tab, "text","columnname"); break;
                case 'm': XMLtools.createChildTextElement(xml, tab, "epochmillis","columnname"); break;
            }
        }
    }
    public void writeToXml( XMLfab fab, boolean build ){
        fab.addChild("table").attr("name",name).down();
        for( var col : columns ){
            fab.addChild(col.type.toString().toLowerCase(),col.title);
            if( !col.alias.isEmpty() && !col.alias.equalsIgnoreCase(name+"_"+col.title)) {
                fab.attr("alias", col.alias);
            }else{
                fab.removeAttr("alias");
            }
            if( !col.defString.isEmpty())
                fab.attr("def",col.defString);
            String setup = (col.primary?"primary ":"")+(col.notnull?"notnull ":"")+(col.unique?"unique ":"");
            if( !setup.isEmpty())
                fab.attr("setup",setup.trim());
        }
        fab.up();

        if (build)
            fab.build();
    }
    /**
     * Builds a generic based on this table
     * @param fab The fab to create the node, pointing to generics node
     * @param db The database that contains the table
     * @param id The id for this generic
     * @param delim The delimiter used by the generic
     * @return True if build was ok
     */
    public boolean buildGeneric(XMLfab fab, String db, String id, String delim){

        fab.selectOrCreateParent("generic","id",id).clearChildren();
        fab.attr("id",id).attr("dbid",db).attr("table",this.name).attr("delimiter",delim);

        int index=0;
        boolean macro=false;
        for( Column col : columns ){ //check for macro
            if( col.alias.contains("@macro")){
                fab.addChild("macro").attr("index",0);
                index++;
                macro=true;
                break;
            }
        }
        for( Column col : columns ){
            if( col.defString.contains("@macro"))
                continue;
            switch( col.type ){
                case INTEGER: fab.addChild("integer",macro?col.alias:col.title).attr("index",index++);break;
                case REAL:    fab.addChild("real",macro?col.alias:col.title).attr("index",index++);break;
                case TEXT:    fab.addChild("text",macro?col.alias:col.title).attr("index",index++);break;
                case TIMESTAMP:
                    if( index !=0)
                        fab.addChild("timestamp",macro?col.alias:col.title).attr("index",index++);
                    break;
                default:
                    break;
            }
        }
        return fab.build()!=null;
    }
    /**
     * Creates a template for a prepared statement of an INSERT query
     * @param id The id of this preparedstatement with which it can be referenced
     * @param params The columns that this statement will provide data to
     * @return True if all columns were found
     */
    public boolean buildInsertStatement( String id, String... params){

        PrepStatement stat = buildPrep(params);
        
        if( stat.getIndexes().size()!=params.length ){
            Logger.error("Couldn't find all parameters");
            return false;
        }
        
        preps.put(id, stat);

        StringJoiner qMarks = new StringJoiner(",", "", ");");
        StringJoiner cols = new StringJoiner(",", "INSERT INTO " + name + " (", ") VALUES (");
        stat.getIndexes().forEach(c -> {
            qMarks.add("?");
            cols.add( columns.get(c).title );
        });
        stat.setStatement( cols + qMarks.toString() );

        return true;
    }
    private void buildDefStatement(){
        PrepStatement stat = preps.get("");

        StringJoiner qMarks = new StringJoiner(",", "", ");");
        StringJoiner cols = new StringJoiner(",", "INSERT INTO " + name + " (", ") VALUES (");
        stat.getIndexes().forEach(c -> {
            qMarks.add("?");
            cols.add( columns.get(c).title );
        });
        stat.setStatement( cols + qMarks.toString() );
    }
    private PrepStatement buildPrep( String... params ){
        PrepStatement stat = new PrepStatement();
        for( String col : params ){
            for( int a=0;a<columns.size();a++){
                if( columns.get(a).title.equalsIgnoreCase(col) ){
                    stat.addColumn(a);
                    Logger.info("Found column "+col+" at index "+a);
                    break;
                }
            }
        }
        return stat;
    }
    private static class PrepStatement{
        ArrayList<Object[]> data = new ArrayList<>();
        ArrayList<Object[]> temp = new ArrayList<>();
        ArrayList<Integer> indexes = new ArrayList<>(); // which columns
        String statement="";
        boolean locked=false;

        public void addColumn( int index ){
            indexes.add(index);
        }
        public List<Integer> getIndexes(){
            return indexes;        
        }
        public List<Object[]> getData(){
            return data;
        }
        public boolean addData( Object[] d ){

            if( d.length!=indexes.size() )
                return false;
            if(locked){
                return temp.add(d);
            }else{
                return data.add(d);
            }

        }
        public void setStatement( String stat ){
            statement=stat;
        }
        public String getStatement(){            
            return statement;
        }
        public void enableLock(){locked=true;}
        public void disableLock(){
            locked=false;
            if( !temp.isEmpty()) {
                data.addAll(temp);
                //Logger.info("Moved " + temp.size() + " from temp");
                temp.clear();
            }
        }
    }
}