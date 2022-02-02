package worker;

import util.data.DataProviding;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.influxdb.dto.Point;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.database.QueryWriting;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.StringJoiner;

public class Generic {

    enum DATATYPE{REAL,INTEGER,TEXT,FILLER,TAG,LOCALDT,UTCDT}

    enum FILTERTYPE{REPLACE_ALL,REPLACE_FIRST}

    ArrayList<Entry> entries = new ArrayList<>();
    ArrayList<Filter> filters = new ArrayList<>();
    String delimiter=",";
    String id;
    private String[] dbid;
    private String table="";
    private String startsWith="";
    private String mqttID="";

    private String influxID="";
    private String influxMeasurement="";

    boolean dbWrite = true;
    int maxIndex=-1;
    boolean tableMatch=false;

    String group="";

    /* Macro */
    String macro="";
    String macroRef="";
    int macroIndex=-1;

    static final String INDEX_STRING = "index";

    public static Generic create( String id){        
        return new Generic(id);
    }
    public Generic( String id ){
        this.id=id;
    }
    public String getID(){
        return id;
    }
    public void setTableMatch(boolean match){
        tableMatch=match;
    }
    public boolean isTableMatch(){
        return tableMatch;
    }
    public void setMQTTID( String mqtt ){
        this.mqttID=mqtt;
    }
    public void setInfluxID( String influx ){
        if( influx==null || influx.isEmpty() )
            return;
        var spl = influx.split(":");
        influxID=spl[0];
        influxMeasurement=spl[1];
    }
    public Generic setMacro( int index, String value ){
        maxIndex = Math.max(maxIndex, index);
        macroIndex=index;
        macroRef=value;
        return this;
    }
    public void setDefaultGroup( String group ){
        this.group=group;
    }
    /**
     * Add looking for a real/double on the given index
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     * @return this object
     */
    public Generic addReal( int index, String title){       
        return addReal( index, title, "");
    }
    public Generic addReal( int index, String title, String mqttDevice){
        maxIndex = Math.max(maxIndex, index);
        Entry ent = new Entry(index, title, DATATYPE.REAL);
        if( !mqttDevice.isEmpty() )
            ent.enableMQTT(mqttDevice);
        entries.add( ent );       
        return this;
    }
    /**
     * Add looking for an integer/long on the given index
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     * @return this object
     */
    public Entry addInteger( int index, String title){
        return addInteger(index,title,"");
    }
    public Entry addInteger( int index, String title,String mqttDevice){
        maxIndex = Math.max(maxIndex, index);
        Entry ent = new Entry(index, title, DATATYPE.INTEGER);
        if( !mqttDevice.isEmpty())
            ent.enableMQTT(mqttDevice);
        entries.add( ent );  
        return ent;
    }
    /**
     * Add looking for a string on the given index (WiP doesn't work yet)
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     * @return this object
     */
    public Entry addText( int index, String title){
        return addThing(index,title,DATATYPE.TEXT);
    }
    public Entry addTag( int index, String title){
        return addThing(index,title,DATATYPE.TAG);
    }
    public Entry addFiller( int index, String title){
        tableMatch=true;
        return addThing(index,title,DATATYPE.FILLER);
    }
    private Entry addThing( int index, String title, DATATYPE type){
        maxIndex = Math.max(maxIndex, index);
        Entry ent = new Entry(index, title, type);
        entries.add( ent );
        return ent;
    }
    /**
     * Set the info required for writing in a database 
     * @param id The id of the database
     * @param table The table in the database
     * @return This object
     */
    public Generic storeInDB( String id, String table ){
        dbid=id.split(",");
        this.table=table;
        return this;
    }

    public boolean writesInDB(){
        return dbWrite;
    }
    /**
     * Add a filter that is applied before the split etc
     * @param type Which filtertype (only has replace_all, replace_first for now)
     * @param from What should be looked for
     * @param to What the 'from' should be replaced with
     * @return this object
     */
    public Generic addFilter( String type, String from, String to ){
        switch(type){
            case "replace_all": filters.add(new Filter(FILTERTYPE.REPLACE_ALL, from, to)); break;
            case "replace_first": filters.add(new Filter(FILTERTYPE.REPLACE_FIRST, from, to)); break;
            default: Logger.warn("Tried to add filter with wrong type: "+type); break;
        }
        return this;
    }
    public String[] getDBID(){
        return dbid;
    }
    public String getStartsWith() {
        return startsWith;
    }
    public String getTable(){
        return table;
    }
    /**
     * Set the delimiter used to split the raw data
     * @param deli The delimiter to use, if empty it will be assumed there's only one element
     * @return this object
     */
    public Generic setDelimiter( String deli ){
        if( !deli.isEmpty() )
            this.delimiter=deli;
        return this;
    }
    /**
     * Sets the string with which the raw data needs to start, if it doesn't, it isn't processed
     * @param start The string to start with
     * @return This object
     */
    public Generic setStartsWith( String start ){
        this.startsWith = start;
        return this;
    }
    /**
     * Apply this generic to the given line and use the RealtimeValues object store the values
     * @param line The raw data line
     * @param doubles The double array if any
     * @param dp The DataProviding implementation to add the data to
     * @return an array with the data
     */
    public Object[] apply(String line, Double[] doubles, DataProviding dp, QueryWriting queryWriting, MqttWriting mqtt){
         
        for( Filter filter : filters ){
            line = filter.apply(line);
        }

        String[] split;
        if( delimiter.isEmpty() ){
            split = new String[]{line};
        }else if( delimiter.equalsIgnoreCase("nrs")) {
            split = Tools.extractNumbers(line);
        }else{
            split = line.split(delimiter);
        }

        if( split.length <= maxIndex){
            Logger.warn("Generic '"+id+"' can't be applied, requires index ("+maxIndex+") higher than available elements ("+split.length+") -> "+line);
            return new Object[0];
        }
        if( macroIndex != -1 ){
            macro = split[macroIndex];
        }else if( !macroRef.isEmpty() ){
            macro=macroRef;
        }
        Object[] data = new Object[entries.size()];

        var pb = influxMeasurement.isEmpty()?null:Point.measurement(influxMeasurement);

        for( int a=0;a<entries.size();a++ ){
            Entry entry = entries.get(a);

            String ref = group.isEmpty()?entry.title:(group+"_"+entry.title);
            
            if( ref.contains("@macro") ) // If the ref contains @macro, replace it with the macro
                ref = ref.replace("@macro", macro);
            
            try{
                double val=-999;
                switch( entry.type ){
                    case INTEGER:
                            if( doubles!=null && doubles.length>entry.index && doubles[entry.index]!=null){
                                data[a] = doubles[entry.index].intValue();
                                dp.setInteger( ref, (int)data[a] );
                            }else if( NumberUtils.isCreatable(split[entry.index])){
                                data[a]=NumberUtils.toInt(split[entry.index],-999);
                                dp.setInteger( ref, (int)data[a] );
                            }else{
                                data[a]=null;
                            }
                            break;
                    case REAL:
                            if( doubles!=null && doubles.length>entry.index && doubles[entry.index]!=null){
                                data[a]=doubles[entry.index];
                                dp.getOrAddDoubleVal( ref ).updateValue( doubles[entry.index] );
                            }else if( NumberUtils.isCreatable(split[entry.index])) {
                                val = NumberUtils.toDouble(split[entry.index], val);
                                data[a] = val;
                                dp.getOrAddDoubleVal( ref ).updateValue( val );
                            }else{
                                data[a]=null;
                                Logger.error(id +" -> Failed to convert "+split[entry.index]+" to real for "+ ref);
                            }
                            break;                
                    case TEXT: case TAG:
                            data[a]=split[entry.index];
                            dp.setText( ref, split[entry.index]);
                            break;
                    case FILLER:
                            if( ref.endsWith("timestamp") ){
                                data[a]=TimeTools.formatLongUTCNow();
                            }else if(ref.endsWith("epoch") ) {
                                data[a] = Instant.now().toEpochMilli();
                            }else if( ref.endsWith("localdt") ){
                                data[a] = OffsetDateTime.now();
                            }else if( ref.endsWith("utcdt") ){
                                data[a] = OffsetDateTime.now(ZoneOffset.UTC);
                            }else{
                                data[a] = ref;
                            }
                            break;
                    case LOCALDT:
                        data[a]=OffsetDateTime.parse( split[entry.index], TimeTools.LONGDATE_FORMATTER );
                        dp.setText( ref, split[entry.index]);
                        break;
                    case UTCDT:
                        var ldt = LocalDateTime.parse( split[entry.index], TimeTools.LONGDATE_FORMATTER_UTC );
                        data[a]=OffsetDateTime.of(ldt,ZoneOffset.UTC);
                        dp.setText( ref, split[entry.index]);
                        break;
                }
                if( !influxID.isEmpty() && pb!=null ){
                   switch (entry.type ){
                       case INTEGER:  pb.addField(entry.title,(int)data[a]); break;
                       case REAL: pb.addField(entry.title,(double)data[a]); break;
                       case TEXT: pb.addField(entry.title, data[a].toString()); break;
                       case TAG: pb.tag(entry.title, data[a].toString()); break;
                   }
                }
                if( mqtt!=null && !mqttID.isEmpty() && !entry.mqttDevice.isEmpty() )
                    mqtt.sendToBroker(mqttID, entry.mqttDevice,ref,(double)data[a]);
            }catch( ArrayIndexOutOfBoundsException l ){
                Logger.error("Invalid index given to process "+id+" index:"+entry.index);
            }            
        }
        if( !influxID.isEmpty() ) {
            //pb.time(Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS); // Set is here because unsure what happens on delayed execution...?
            queryWriting.writeInfluxPoint(influxID,pb.build());
        }
        return data;
    }
    /**
     * Returns the info about this generic
     */
    public String toString(){
        StringJoiner join = new StringJoiner("",id+" -> ","");
        if( dbid!=null ){
            join.add("Store in "+String.join(",",dbid)+":"+table+" " );
        }
        if( !group.isEmpty())
            join.add(" for group "+group+" ");

        if (!influxID.isEmpty() ){
            join.add(" Store in InfluxDB "+influxID+":"+table+" ");
        }
        join.add("has delimiter '"+delimiter+"'"+(startsWith.isBlank()?"":"and starts with '"+startsWith+"'") );

        join.add("\r\n");

        if( !filters.isEmpty() ){
            for( Filter filter:filters){
                join.add("> "+filter.toString()+"\r\n");
            }
        }
        if( macroIndex!=-1 ){
            join.add("Macro is found on index "+macroIndex+"\r\n" );
        }
        if( !macroRef.isEmpty() ){
            join.add("Macro has a fixed value of "+macroRef+"\r\n");
        }
        for( Entry entry:entries){
            join.add("> "+entry.toString()+"\r\n");
        }
        return join.toString();
    }
    public static Generic readFromXML( Element gen ){

        Generic generic = Generic.create(gen.getAttribute("id"));
        generic.setDefaultGroup(gen.getAttribute("group"));
        generic.setDelimiter(XMLtools.getStringAttribute(gen,"delimiter",","));
        generic.setStartsWith(gen.getAttribute("startswith"));
        generic.setMQTTID(gen.getAttribute("mqtt"));
        generic.setInfluxID(gen.getAttribute("influx"));
        generic.setTableMatch( XMLtools.getBooleanAttribute(gen, "exact", false));

        if ( gen.hasAttribute("db")) {
            var db = gen.getAttribute("db").split(":");
            if( db.length==2 ) {
                generic.storeInDB(db[0], db[1]);
            }else{
                Logger.error( generic.getID()+" -> Failed to read db tag, must contain dbids:table, multiple dbids separated with ','");
            }
        }
        for (Element ent : XMLtools.getChildElements(gen)) {
            String title = ent.getTextContent();

            switch (ent.getNodeName()) {
                case "macro":
                    generic.setMacro(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), ent.getTextContent());
                    break;
                case "filter":
                    String repAll = ent.getAttribute("replaceall");
                    if (!repAll.isEmpty()) {
                        generic.addFilter("replace_all", repAll, ent.getAttribute("with"));
                    }
                    break;
                case "real":case "double":
                    generic.addReal(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title,
                            XMLtools.getStringAttribute(ent, "mqtt", ""));
                    break;
                case "integer":case "int":
                    generic.addInteger(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title,
                            XMLtools.getStringAttribute(ent, "mqtt", ""));
                    break;
                case "text": case "timestamp":
                    generic.addText(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title );
                    break;
                case "filler":
                    generic.addFiller(-1, ent.getTextContent());
                    break;
                case "tag":
                    generic.addTag(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), ent.getTextContent());
                    break;
                case "localdt":
                    generic.addThing(XMLtools.getIntAttribute(ent, INDEX_STRING, -1),title,DATATYPE.LOCALDT);
                    break;
                case "utcdt":
                    generic.addThing(XMLtools.getIntAttribute(ent, INDEX_STRING, -1),title,DATATYPE.UTCDT);
                    break;
                default: Logger.warn("Tried to add generic part with wrong tag: "+ent.getNodeName()); break;
            }
        }
        return generic;
    }
    public static boolean addBlankToXML( XMLfab fab, String id, String group, String[] format,String delimiter ){

        fab.addParentToRoot("generic").attr("id",id).attr("group",group);

        if( fab.hasChild("generic","id",id).isPresent()) {
            Logger.error("Tried to add a generic with an id already in use. ("+id+")");
            return false;
        }
        if( !delimiter.isEmpty())
            fab.attr("delimiter",delimiter);

        for( var row : format){
            if( row.isEmpty()) {
                Logger.warn("Invalid/Empty row");
                continue;
            }
            String type = row.substring(0,1);
            String name = row.substring(1).replaceFirst("^\\d+","");
            if( name.isEmpty() )
                name=".";
            int index = NumberUtils.toInt(row.substring(1).replace(name,""),-1);
            if( index == -1) {
                Logger.warn( "Bad index in : "+row);
                continue;
            }
            switch(type){
                case "m": fab.addChild("macro").attr(INDEX_STRING,index); break;
                case "r": case "d": fab.addChild("real",name).attr(INDEX_STRING,index); break;
                case "i": fab.addChild("int",name).attr(INDEX_STRING,index); break;
                case "t": fab.addChild("text",name).attr(INDEX_STRING,index); break;
                case "f": fab.addChild("filler",name).attr(INDEX_STRING,index); break; //filler
                case "g": fab.addChild("tag",name).attr(INDEX_STRING,index); break;
                case "s": break; //skip
                default: 
                    Logger.warn("Tried to add child with wrong type: "+type);
                    return false;
            }
        }

        return fab.build();
    }
    /**
     * Class to store filters that can be applied before the generic is applied
     */
    private static class Filter{
        String from;
        String to;
        FILTERTYPE type;

        public Filter( FILTERTYPE type, String from, String to ){
            this.to=to;
            this.from=from;
            this.type=type;
        }
        public String toString(){
            switch( type ){
                case REPLACE_ALL: return "Replace all '"+from+"' with '"+to+"'";					
				case REPLACE_FIRST: return "Replace first '"+from+"' with '"+to+"'";
				default:
					break;
            }
            return "";
        }
        /**
         * Apply this filter to the given data
         * @param line The data to apply the filter to
         * @return The resulting line
         */
        public String apply(String line){
            switch( type ){
                case REPLACE_ALL:
                    while( line.contains(from))
                        line=line.replace(from, to);
                    break;
                case REPLACE_FIRST: line=line.replace(from, to); break;
                default:
                    break;         
            }
            return line;
        }
    }

    /**
     * Class for entries that define a single value looked for in the raw data
     */
    public static class Entry{
        DATATYPE type;
        int index;
        String title;
        String mqttDevice="";

        public Entry(int index, String title, DATATYPE type){
            this.index=index;
            this.title=title;
            this.type=type;
        }
        public void enableMQTT( String mqttDevice){
            this.mqttDevice=mqttDevice;
        }
        public String toString(){
            return "At ["+index+"] get a "+type+" called "+title;
        }
    }
}