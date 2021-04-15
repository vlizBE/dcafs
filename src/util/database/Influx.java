package util.database;

import org.apache.commons.lang3.StringUtils;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class Influx extends Database{

    InfluxDB influxDB;
    String dbname;
    HashMap<String,Measurement> measurements = new HashMap<>();
    enum FIELD_TYPE {
        INTEGER, FLOAT, STRING, TIMESTAMP
    }

    public Influx( String address, String dbname, String user, String pass){
        this.user=user;
        this.pass=pass;
        irl=address;
        if(StringUtils.countMatches(irl,":")==0){ // IPv4
            irl += ":8086";
        }
        this.dbname=dbname;
    }

    public static Influx readFromXML( Element dbe ) {

        if (dbe == null)
            return null;

        Element dbTag = XMLtools.getFirstChildByTag(dbe, "db");

        if (dbTag == null)
            return null;

        String user = XMLtools.getStringAttribute(dbTag, "user", "");           // A username with writing rights
        String pass = XMLtools.getStringAttribute(dbTag, "pass", "");          // The password for the earlier defined username
        String dbname = dbTag.getTextContent();                                                // The name of the database
        String irl = XMLtools.getChildValueByTag(dbe, "address", "");            // Set the address of the server on which the DB runs (either hostname or IP)
        if(StringUtils.countMatches(irl,":")==0){
            irl += ":8086";
        }
        var db = new Influx(irl,dbname,user,pass);

        db.readBatchSetup( XMLtools.getFirstChildByTag(dbe, "setup") );
        db.connect(false);
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

        fab.selectOrCreateParent("server","id", id.isEmpty()?"remote":id).attr("type","influx")
                .alterChild("db","name").attr("user",user).attr("pass",pass)
                .alterChild("setup").attr("idletime",idle).attr("flushtime",flush).attr("batchsize",maxQueries)
                .alterChild("address",address)
                .build();
    }
    public void writePoint( Point p){
        influxDB.write(p);
    }
    @Override
    public boolean connect(boolean force) {
        influxDB = InfluxDBFactory.connect("http://"+irl, user, pass);
        if( influxDB.ping().isGood() ){
            Logger.info(id+" -> Connected to InfluxDB "+dbname);
            influxDB.setDatabase(dbname);
            influxDB.enableBatch(maxQueries, (int) maxAge, TimeUnit.SECONDS);
            return true;
        }else{
            Logger.error(id+" -> Failed to connect");
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        influxDB.close();
        return true;
    }

    @Override
    public boolean isValid(int timeout) {
        if( influxDB == null)
            return false;
        return influxDB.ping().isGood();
    }

    @Override
    public int getRecordsCount() {
        return -1;
    }

    @Override
    public boolean hasRecords() {
        return false;
    }

    public synchronized int doDirectInsert(String table, Object... values){
        var mes = measurements.get(table);
        if( mes ==null )
            return 0;
        if( mes.fields.size()!= values.length)
            return 0;

        var p = Point.measurement(table);
        for( int a=0;a<mes.fields.size();a++ ){
            var field = mes.fields.get(a);
            switch( field.type ){
                case INTEGER: p.addField(field.name,(int)values[a]);       break;
                case FLOAT:   p.addField(field.name,(double) values[a]);   break;
                case STRING:  p.addField(field.name,values[a].toString()); break;
                case TIMESTAMP:
                    break;
            }
        }
        influxDB.write(p.build());
        return 1;
    }
    @Override
    public Optional<List<List<Object>>> doSelect(String query, boolean includeNames) {

        if( isValid(1000)){
           var res = influxDB.query( new Query(query)).getResults();
           if( !res.isEmpty()){
               var series = res.get(0).getSeries();
               if( !series.isEmpty()){
                   var recs = new ArrayList<List<Object>>();
                   if( includeNames ){
                       var names = new ArrayList<Object>();
                       series.get(0).getColumns().forEach(names::add);
                       recs.add(names);
                   }
                   recs.addAll(series.get(0).getValues());
                   return Optional.ofNullable(recs);
               }
           }
        }
        return Optional.empty();
    }

    @Override
    public void addQuery(String query) {

    }

    @Override
    public boolean buildInsert(String table, ConcurrentMap<String, Double> rtvals, ConcurrentMap<String, String> rttext, String macro) {
        return false;
    }

    @Override
    public boolean getCurrentTables(boolean clear){
        var res = influxDB.query(new Query("SHOW MEASUREMENTS",dbname)).getResults();
        if( res.isEmpty())
            return true;
        var tables = res.get(0).getSeries().get(0).getValues();

        for( var table : tables){
            var mes = new Measurement(table.toString().substring(1,table.toString().length()-1));
            measurements.put(mes.name,mes);
        }
        for( var mes : measurements.values() ){
            var field = influxDB.query(new Query("SHOW FIELD KEYS FROM "+mes.name,dbname)).getResults().get(0);
            var cols = field.getSeries().get(0).getValues();
            for( var col : cols){
                switch( col.get(1).toString() ){
                    case "integer": mes.addField(col.get(0).toString(), FIELD_TYPE.INTEGER); break;
                    case "string": mes.addField(col.get(0).toString(), FIELD_TYPE.STRING); break;
                    case "float": mes.addField(col.get(0).toString(), FIELD_TYPE.FLOAT); break;
                }
            }
        }
        return true;
    }

    @Override
    public String createContent(boolean keepConnection) {
        return "Not supported";
    }

    @Override
    public String getTableInfo(String eol) {
        return null;
    }

    @Override
    public boolean buildGenericFromTable(XMLfab fab, String tableName, String genID, String delim) {
        return false;
    }

    @Override
    public int buildGenericFromTables(XMLfab fab, boolean overwrite, String delim) {
        return 0;
    }
    public String toString(){
        return "INFluxDB@"+ getTitle()+" -> Buffer managed by lib";
    }
    public String getTitle(){
        return irl.substring(irl.lastIndexOf("=")+1);
    }
    private class Measurement{
        String name;
        ArrayList<Field> fields =new ArrayList<>();

        public Measurement(String name){
            this.name=name;
        }
        public void addField(String name, FIELD_TYPE type){
            fields.add( new Field(name,type));
        }
    }
    private class Field{
        String name;
        FIELD_TYPE type;
        public Field( String name,FIELD_TYPE type){
            this.name=name;
            this.type=type;
        }
    }
}
