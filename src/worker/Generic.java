package worker;

import util.data.RealtimeValues;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.FlagVal;
import util.data.IntegerVal;
import util.data.RealVal;
import util.database.QueryWriting;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public class Generic {

    enum DATATYPE{REAL,INTEGER,FLAG,TEXT,FILLER,TAG,LOCALDT,UTCDT}

    ArrayList<Entry> entries = new ArrayList<>();
    String delimiter=",";
    String id;
    private String[] dbid;
    private String table="";
    private String startsWith="";
    private String mqttID="";
    private boolean dbWrite = true;
    private int maxIndex=-1;
    private boolean tableMatch=false;

    private Path settingsPath;

    /* Macro */
    String macro="";
    String macroRef="";
    int macroIndex=-1;

    private long uses=0;

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
    public void setMacro(int index, String value ){
        maxIndex = Math.max(maxIndex, index);
        macroIndex=index;
        macroRef=value;
    }
    public Optional<Entry> getLastEntry(){
        if( entries.isEmpty())
            return Optional.empty();
        return Optional.ofNullable(entries.get(entries.size()-1));
    }
    /**
     * Add looking for a real/double on the given index
     *
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     */
    public void addReal(int index, String title, String mqttDevice, String def){
        var ent = addThing(index,title,DATATYPE.REAL);
        if( !mqttDevice.isEmpty())
            ent.enableMQTT(mqttDevice);
        if( !def.isEmpty() ){
            if( NumberUtils.isCreatable(def))
                ent.defReal=NumberUtils.toDouble(def);
        }
    }
    /**
     * Add looking for an integer/long on the given index
     *
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     */
    public void addInteger(int index, String title, String mqttDevice, String def){
        var ent = addThing(index,title,DATATYPE.INTEGER);
        if( !mqttDevice.isEmpty())
            ent.enableMQTT(mqttDevice);
        if( !def.isEmpty() ){
            if( NumberUtils.isCreatable(def))
                ent.defInt=NumberUtils.toInt(def);
        }
    }
    public void addFlag(int index, String title, String mqttDevice ){
        var ent = addThing(index,title,DATATYPE.FLAG);
        if( !mqttDevice.isEmpty())
            ent.enableMQTT(mqttDevice);
    }
    /**
     * Add looking for a string on the given index (WiP doesn't work yet)
     *
     * @param index The index to look at
     * @param title The name under which to store this ( or table_title if table is defined)
     */
    public void addText(int index, String title){
        addThing(index, title, DATATYPE.TEXT);
    }
    public void addTag(int index, String title){
        addThing(index, title, DATATYPE.TAG);
    }
    public void addFiller(int index, String title){
        tableMatch=true;
        addThing(index, title, DATATYPE.FILLER);
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
     * @param rtvals The RealtimeValues implementation to add the data to
     * @return an array with the data
     */
    public Object[] apply(String line, Double[] doubles, RealtimeValues rtvals, QueryWriting queryWriting, MqttWriting mqtt){
        uses++;

        if( line.equals("corrupt")){
            Logger.error(id+"(gen) -> Received the corrupt command");
            entries.forEach( e->e.reset(rtvals));
            return new Object[1];
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

        for( int a=0;a<entries.size();a++ ){
            Entry entry = entries.get(a);

            String ref = entry.getID();
            
            if( ref.contains("@macro") ) // If the ref contains @macro, replace it with the macro
                ref = ref.replace("@macro", macro);


            try{
               //
                switch (entry.type) {
                    case INTEGER -> {
                        int intVal;
                        if (doubles != null && doubles.length > entry.index && doubles[entry.index] != null) {
                            intVal = doubles[entry.index].intValue();
                        } else {
                            intVal = NumberUtils.toInt(split[entry.index].trim(), Integer.MAX_VALUE);
                            if( intVal == Integer.MAX_VALUE) {
                                try{
                                    intVal = NumberUtils.createInteger(split[entry.index]);
                                }catch(NumberFormatException e){
                                    Logger.warn("Failed to parse "+split[entry.index]+" to integer.");
                                }

                                if (entry.defInt != Integer.MAX_VALUE) {
                                    intVal = entry.defInt;
                                } else if (split[entry.index].isEmpty()) {
                                    Logger.error(id + " -> Got an empty value at " + entry.index + " instead of integer for " + ref);
                                } else {
                                    if (split[entry.index].startsWith("0")) {// If starting with leading 0
                                        int res = Tools.parseInt(split[entry.index], Integer.MAX_VALUE);
                                        if (res != Integer.MAX_VALUE) {
                                            intVal = res;
                                        }
                                    } else {
                                        Logger.error(id + " -> Failed to convert " + split[entry.index] + " to integer for " + ref);
                                    }
                                }
                            }
                        }
                        data[a] = intVal;
                        if (rtvals.hasInteger(ref)) {
                            rtvals.updateInteger(ref, intVal);
                        } else {
                            var iv = IntegerVal.newVal(entry.group, entry.name).value(intVal);
                            rtvals.addIntegerVal(iv, settingsPath);
                        }
                    }
                    case REAL -> {
                        double val=-999;
                        if (doubles != null && doubles.length > entry.index && doubles[entry.index] != null) {
                            val = doubles[entry.index];
                        } else {
                            if( split[entry.index].isEmpty() ){
                                val = entry.defReal;
                            }else {
                                val = NumberUtils.toDouble(split[entry.index].trim(), Double.NaN);
                            }
                            if( Double.isNaN(val)) {
                                Logger.warn("Failed to parse "+split[entry.index]+" to double.");
                                if (!Double.isNaN(entry.defReal)) {
                                    val = entry.defReal;
                                } else if (split[entry.index].isEmpty()) {
                                    Logger.error(id + " -> Got an empty value at " + entry.index + " instead of real for " + ref);
                                } else {
                                    Logger.error(id + " -> Failed to convert " + split[entry.index] + " to real for " + ref);
                                }
                            }
                        }
                        data[a] = val;
                        if (rtvals.hasReal(ref)) {
                            rtvals.updateReal(ref, val);
                        } else {
                            rtvals.addRealVal(RealVal.newVal(entry.group, entry.name).value(val), settingsPath);
                        }
                    }
                    case TEXT, TAG -> {
                        data[a] = split[entry.index];
                        rtvals.addTextVal(ref, split[entry.index], settingsPath);
                    }
                    case FLAG -> {
                        data[a] = Double.NaN;
                        if (rtvals.hasFlag(ref)) {
                            rtvals.setFlagState(ref, split[entry.index]);
                        } else {
                            rtvals.addFlagVal(FlagVal.newVal(entry.group, entry.name).setState(split[entry.index]), settingsPath);
                        }
                    }
                    case FILLER -> {
                        if (ref.endsWith("timestamp")) {
                            data[a] = TimeTools.formatLongUTCNow();
                        } else if (ref.endsWith("epoch")) {
                            data[a] = Instant.now().toEpochMilli();
                        } else if (ref.endsWith("localdt")) {
                            data[a] = OffsetDateTime.now();
                        } else if (ref.endsWith("utcdt")) {
                            data[a] = OffsetDateTime.now(ZoneOffset.UTC);
                        } else {
                            data[a] = ref;
                        }
                    }
                    case LOCALDT -> {
                        data[a] = OffsetDateTime.parse(split[entry.index], TimeTools.LONGDATE_FORMATTER);
                        rtvals.setText(ref, split[entry.index]);
                    }
                    case UTCDT -> {
                        var ldt = LocalDateTime.parse(split[entry.index], TimeTools.LONGDATE_FORMATTER_UTC);
                        data[a] = OffsetDateTime.of(ldt, ZoneOffset.UTC);
                        rtvals.setText(ref, split[entry.index]);
                    }
                }
                if( mqtt!=null && !mqttID.isEmpty() && !entry.mqttDevice.isEmpty() )
                    mqtt.sendToBroker(mqttID, entry.mqttDevice,ref,(double)data[a]);
            }catch( ArrayIndexOutOfBoundsException l ){
                Logger.error("Invalid index given to process "+id+" index:"+entry.index);
            }            
        }
        return data;
    }
    public String getCommonGroups(){
        StringJoiner join = new StringJoiner(",");
        entries.stream().map( e -> e.group).distinct().forEach( join::add);
        return join.toString();
    }
    /**
     * Returns the info about this generic
     */
    public String toString(){
        StringJoiner join = new StringJoiner("",id+" -> ","");
        if( dbid!=null ){
            join.add("Store in "+String.join(",",dbid)+":"+table+" " );
        }
        if( !getCommonGroups().isEmpty())
            join.add(" for group(s) '"+getCommonGroups()+"' ");

        join.add("has delimiter '"+delimiter+"'"+(startsWith.isBlank()?"":"and starts with '"+startsWith+"'") );
        if( uses == 0 ){
            join.add(", not used yet.");
        }else{
            join.add(", used "+uses+" times.");
        }
        join.add("\r\n");

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
    public static Generic readFromXML( Element gen, Path xmlPath ){

        Generic generic = Generic.create(gen.getAttribute("id"));
        String group = gen.getAttribute("group");
        generic.setDelimiter(XMLtools.getStringAttribute(gen,"delimiter",","));
        generic.setStartsWith(gen.getAttribute("startswith"));
        generic.setMQTTID(gen.getAttribute("mqtt"));
        generic.setTableMatch( XMLtools.getBooleanAttribute(gen, "exact", false));
        generic.settingsPath = xmlPath;

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
            String mqtt = XMLtools.getStringAttribute(ent, "mqtt", "");
            String def = XMLtools.getStringAttribute(ent,"def","");

            switch (ent.getNodeName()) {
                case "macro" -> generic.setMacro(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), ent.getTextContent());
                case "real", "double" -> generic.addReal(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title,mqtt,def);
                case "integer", "int" -> generic.addInteger(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title,mqtt,def);
                case "flag", "bool" -> generic.addFlag(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title,mqtt);
                case "text", "timestamp" -> generic.addText(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title);
                case "filler" -> generic.addFiller(-1, ent.getTextContent());
                case "tag" -> generic.addTag(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), ent.getTextContent());
                case "localdt" -> generic.addThing(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title, DATATYPE.LOCALDT);
                case "utcdt" -> generic.addThing(XMLtools.getIntAttribute(ent, INDEX_STRING, -1), title, DATATYPE.UTCDT);
                default -> Logger.warn("Tried to add generic part with wrong tag: " + ent.getNodeName());
            }
            if( ent.hasAttribute("group") ){
                generic.getLastEntry().ifPresent(g->g.setGroup(ent.getAttribute("group")));
            }else{
                generic.getLastEntry().ifPresent(g->g.setGroup(group));
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
    public void createRtvals(RealtimeValues rt){
        Logger.info("Creating rtvals that are defined in "+id);
        entries.forEach( ent -> {
            switch(ent.type){
                case REAL -> rt.addRealVal( RealVal.newVal(ent.group,ent.name),settingsPath);
                case INTEGER -> rt.addIntegerVal( IntegerVal.newVal(ent.group,ent.name),settingsPath);
                case FLAG -> rt.addFlagVal( FlagVal.newVal(ent.group,ent.name),settingsPath);
                case TEXT -> rt.addTextVal(ent.getID(),"",settingsPath);
                case FILLER,TAG,LOCALDT,UTCDT -> {}
            }
        });
    }
    /**
     * Class for entries that define a single value looked for in the raw data
     */
    public static class Entry{
        DATATYPE type;
        int index;
        String name;
        String mqttDevice="";
        String group="";
        double defReal = Double.NaN;
        int defInt = Integer.MAX_VALUE;

        public Entry(int index, String name, DATATYPE type){
            this.index=index;
            this.name =name;
            this.type=type;
        }
        public void reset(RealtimeValues rt){
            String id = group+"_"+name;
            switch(type){
                case REAL -> rt.updateReal(id,defReal);
                case INTEGER -> rt.updateInteger(id,defInt);
                case FLAG -> rt.setFlagState(id,false);
                case TEXT -> rt.setText(id,"");
                case FILLER,TAG,LOCALDT,UTCDT -> {}
            }
        }
        public void setGroup( String group ){
            this.group=group;
        }
        public void enableMQTT( String mqttDevice){
            this.mqttDevice=mqttDevice;
        }

        public String toString(){
            return "At ["+index+"] get a "+type+" called "+ name + (group.isEmpty()?" in default group":" in group '"+group+"'");
        }
        public String getID(){
            return group.isEmpty()? name:group+"_"+name;
        }
        public void setDefReal( double def){
            defReal=def;
        }
        public void setDefInt( int def){
            defInt=def;
        }
    }
}