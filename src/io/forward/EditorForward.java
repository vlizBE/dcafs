package io.forward;

import util.data.RealtimeValues;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class EditorForward extends AbstractForward{
    ArrayList<Function<String,String>> edits = new ArrayList<>(); // for the scale type
    String delimiter = ",";

    public EditorForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        super(id,source,dQueue,rtvals);
    }
    public EditorForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals  ){
        super(dQueue,rtvals);
        readOk = readFromXML(ele);
    }
    /**
     * Read an editor from an element in the xml
     * @param ele The element containing the editor info
     * @return The EditorForward created based on the xml element
     */
    public static EditorForward readXML(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        return new EditorForward( ele,dQueue, rtvals );
    }

    /**
     * Get an overview of all the available edit types
     * @param eol The end of line to use for the overview
     * @return A listing of all the types with examples
     */
    public static String getHelp(String eol) {
        StringJoiner join = new StringJoiner(eol);
        join.add("All examples will start from 16:25:12 as base data");
        join.add("resplit -> Use the delimiter to split the data and combine according to the value")
                .add("    fe. <edit type='resplit' delimiter=':' leftover='append'>i0-i1</edit>  --> 16-25:12")
                .add("    fe. <edit type='resplit' delimiter=':' leftover='remove'>i0-i1</edit>  --> 16-25")
                .add("    fe. <edit type='resplit' delimiter=':' leftover='remove'>i2-i1-i0</edit>  --> 12-25-16")
                .add("charsplit -> Splits the given data on the char positions and combines with first used delimiter")
                .add("    fe. <edit type='charsplit'>1,4,7 </edit>  --> 1,6:2,5:1,2")
                .add("redate -> Get the value at index according to delimiter, then go 'from' one date(time) format to the format in the value given")
                .add("    fe. <edit type='redate' from='yy:dd:MM' >dd_MMMM_yy</edit>  --> 25_december_16")
                .add("retime -> Same as redate but for only time")
                .add("    fe. <edit type='retime' from='HH:mm:ss' >HH-mm</edit>  --> 16-25")
                .add("replace -> Replace 'find' with the value given (NOTE: 'Value given' can't be a whitespace character)")
                .add("    fe. <edit type='replace' find='1'>4</edit>  --> 46:25:42")
                .add("prepend -> Add the given data to the front")
                .add("    fe. <edit type='prepend' >time=</edit>  --> time=16:25:12")
                .add("append -> Add the given data at the end")
                .add("    fe. <edit type='append' > (UTC)</edit>  --> time=16:25:12 (UTC)")
                .add("insert -> Add the given data at the chosen position")
                .add("    fe. <edit type='insert' position='4' >!</edit>  --> time!=16:25:12 (UTC)")
                .add("")
                .add("--REMOVE--")
                .add("trim -> Remove all whitespace characters from the start and end of the data")
                .add("    fe. <edit type='trim'/>")
                .add("remove -> Remove all occurrences of the value given  (NOTE: 'Value given' can't be a whitespace character)")
                .add("    fe. <edit type='remove' >1</edit>  --> 6:25:2")
                .add("cutstart -> Cut the given amount of characters from the front")
                .add("    fe. <edit type='cutstart' >2</edit>  --> time=:25:12")
                .add("cutend -> Cut the given amount of characters from the end")
                .add("    fe. <edit type='cutend' >2</edit>  --> time=16:25:").add("")
                .add("--REGEX BASED--")
                .add("rexsplit -> Use the value as a regex to split the data and combine again with the delimiter")
                .add("    fe. <edit type='rexsplit' delimiter='-'>\\d*</edit>  --> 16-25-12")
                .add("rexreplace -> Use a regex based on 'find' and replace it with the value given")
                .add("    fe. <edit type='rexreplace' find='\\d*'>x</edit>  --> x:x:x")
                .add("rexremove -> Remove all matches of the value as a regex ")
                .add("    fe. <edit type='rexremove' >\\d*</edit>  --> ::")
                .add("rexkeep -> Only retain the result of the regex given as value")
                .add("    fe. <edit type='rexkeep' >\\d*</edit>  --> 162512")
                .add("millisdate -> Convert epoch millis to a timestamp with given format")
                .add("    fe. <edit type='millisdate'>yyyy-MM-dd HH:mm:ss.SSS</edit> ");

        return join.toString();
    }
    @Override
    protected boolean addData(String data) {

        if( debug ) // extra info given if debug is active
            Logger.info(getID()+" -> Before: "+data); // how the data looked before

        for( var edit:edits){
            data = edit.apply(data);
        }

        if( debug ){ // extra info given if debug is active
            Logger.info(getID()+" -> After: "+data);
        }
        String finalData = data;
        targets.removeIf(t-> !t.writeLine(finalData) ); // Send this data to the targets, remove those that refuse it

        if( log )
            Logger.tag("RAW").info( "1\t" + (label.isEmpty()?"void":label)+"|"+getID() + "\t" + data);

        if( !label.isEmpty() ){ // If the object has a label associated
            dQueue.add( Datagram.build(data).label(label).writable(this) ); // add it to the queue
        }
        // If there are no target, no label, this no longer needs to be a target
        if( targets.isEmpty() && label.isEmpty() && !log){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( Datagram.system("ef:remove,"+id) ); // todo
            return false;
        }
        return true;
    }

    @Override
    public boolean writeToXML(XMLfab fab) {
        fab.digRoot("editors");
        fab.selectOrAddChildAsParent("editor","id",id);

        /* Attributes and nodes that are the same for all forwards fe.label and src */
        writeBasicsToXML(fab);

        return fab.build();
    }

    @Override
    public boolean readFromXML(Element editor) {

        if( !readBasicsFromXml(editor))
            return false;
        delimiter = XMLtools.getStringAttribute(editor,"delimiter",delimiter);
        edits.clear();
        if( XMLtools.hasChildByTag(editor,"edit") ) { // if rules are defined as nodes
            // Process all the types except 'start'
            XMLtools.getChildElements(editor, "edit").forEach( this::processNode );
        }else{
            return processNode(editor);
        }
        return true;
    }
    private boolean processNode( Element edit ){
        String deli = XMLtools.getStringAttribute(edit,"delimiter",delimiter);
        String content = edit.getTextContent();
        String from = XMLtools.getStringAttribute(edit,"from",",");
        String error = XMLtools.getStringAttribute(edit,"error","NaN");
        String find = XMLtools.getStringAttribute(edit,"find","");
        String leftover = XMLtools.getStringAttribute(edit,"leftover","append");

        int index = XMLtools.getIntAttribute(edit,"index",-1);
        int position = XMLtools.getIntAttribute(edit,"index",-1);

        if( content == null ){
            Logger.error(id+" -> Missing content in an edit.");
            return false;
        }
        if( index == -1 ){
            index=0;
        }
        switch(edit.getAttribute("type")){
            case "charsplit":
                addCharSplit( deli,content );
                Logger.info(id+" -> Added charsplit with delimiter "+deli+" on positions "+content);
                break;
            case "resplit":
                addResplit(deli,content,error,leftover.equalsIgnoreCase("append"));
                Logger.info(id+" -> Added resplit edit on delimiter "+deli+" with formula "+content);
                break;
            case "rexsplit":
                addRexsplit(deli,content);
                Logger.info(id+" -> Get items from "+content+ " and join with "+deli);
                break;
            case "redate":
                addRedate(from,content,index,deli);
                Logger.info(id+" -> Added redate edit on delimiter "+deli+" from "+from+" to "+content);
                break;
            case "retime":
                addRetime(from,content,index,deli);
                Logger.info(id+" -> Added retime edit on delimiter "+deli+" from "+from+" to "+content);
                break;
            case "replace":
                if( find.isEmpty() ){
                    Logger.error(id+" -> Tried to add an empty replace.");
                }else{
                    addReplacement(find,content);
                }
                break;
            case "rexreplace":
                if( find.isEmpty() ){
                    Logger.error(id+" -> Tried to add an empty replace.");
                }else{
                    addRegexReplacement(find,content);
                }
                break;
            case "remove":
                addReplacement(content,"");
                Logger.info(id+" -> Remove occurrences off "+content);
                break;
            case "trim":
                addTrim( );
                Logger.info(id+" -> Trimming spaces");
                break;
            case "rexremove":
                addRexRemove(content);
                Logger.info(id+" -> Remove matches off "+content);
                break;
            case "rexkeep":
                addRexsplit("",content);
                Logger.info(id+" -> Keep result of "+content);
                break;
            case "prepend":case "prefix":
                addPrepend(content);
                Logger.info(id+" -> Added prepend of "+content);
                break;
            case "append": case "suffix":
                addAppend(content);
                Logger.info(id+" -> Added append of "+content);
                break;
            case "insert":
                addInsert(position,content);
                Logger.info(id+" -> Added insert of "+content);
                break;
            case "cutstart":
                if( NumberUtils.toInt(content,0)!=0) {
                    addCutStart(NumberUtils.toInt(content, 0));
                    Logger.info(id + " -> Added cut start of " + content + " chars");
                }else{
                    Logger.warn(id + " -> Invalid number given to cut from start "+content);
                }
                break;
            case "cutend":
                if( NumberUtils.toInt(content,0)!=0) {
                    addCutEnd(NumberUtils.toInt(content, 0));
                    Logger.info(id + " -> Added cut end of " + content + " chars");
                }else{
                    Logger.warn(id + " -> Invalid number given to cut from end "+content);
                }
                break;
            case "toascii":
                converToAscii(deli);
                Logger.info(id + " -> Added conversion to char");
                break;
            case "millisdate":
                addMillisToDate(content,index,deli);
                Logger.info( getID() +" -> Added millis conversion to "+content);
                break;
            default:
                Logger.error(id+" -> Unknown type used : '"+edit.getAttribute("type")+"'");
                return false;
        }
        return true;
    }
    public void addCharSplit( String deli, String positions){
        rulesString.add( new String[]{"","charsplit","At "+positions+" to "+deli} );
        String[] pos = Tools.splitList(positions);
        var indexes = new ArrayList<Integer>();
        if( !pos[0].equals("0"))
            indexes.add(0);
        Arrays.stream(pos).forEach( p -> indexes.add( NumberUtils.toInt(p)));

        String delimiter;
        if( pos.length >= 2){
            delimiter = ""+positions.charAt(positions.indexOf(pos[1])-1);
        }else{
            delimiter=deli;
        }

        Function<String,String> edit = input ->
        {
            if(indexes.get(indexes.size()-1) > input.length()){
                Logger.error("Can't split "+input+" if nothing exits at "+indexes.get(indexes.size()-1));
                return input;
            }
            try {
                StringJoiner result = new StringJoiner(delimiter);
                for (int a = 0; a < indexes.size() - 1; a++) {
                    result.add(input.substring(indexes.get(a), indexes.get(a + 1)));
                }
                String leftover=input.substring(indexes.get(indexes.size() - 1));
                if( !leftover.isEmpty())
                    result.add(leftover);
                return result.toString();
            }catch( ArrayIndexOutOfBoundsException e){
                Logger.error("Failed to apply charsplit on "+input);
            }
            return input;
        };
        edits.add(edit);
    }
    public void addMillisToDate( String to, int index, String delimiter ){
        rulesString.add( new String[]{"","millisdate","millis -> "+to} );
        Function<String,String> edit = input ->
        {
            String[] split = input.split(delimiter);
            if( split.length > index){
                long millis = NumberUtils.toLong(split[index],-1L);
                if( millis == -1L ){
                    Logger.error( getID() + " -> Couldn't convert "+split[index]+" to millis");
                    return input;
                }
                var ins = Instant.ofEpochMilli(millis);
                try {
                    if( to.equalsIgnoreCase("sql")){
                        split[index] = ins.toString();
                    }else{
                        split[index] = DateTimeFormatter.ofPattern(to).withZone(ZoneId.of("UTC")).format(ins);
                    }
                    if (split[index].isEmpty()) {
                        Logger.error(getID() + " -> Failed to convert datetime " + split[index]);
                        return input;
                    }
                    return String.join(delimiter, split);
                }catch(IllegalArgumentException | DateTimeException e){
                    Logger.error( getID() + " -> Invalid format in millis to date: "+to+" -> "+e.getMessage());
                    return input;
                }
            }
            Logger.error(id+" -> To few elements after split for redate");
            return input;
        };
        edits.add(edit);
    }
    /**
     * Alter the formatting of a date field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    public void addRedate( String from, String to, int index, String delimiter ){
        rulesString.add( new String[]{"","redate",from+" -> "+to} );
        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        Function<String,String> edit = input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatDate(split[index], from, to);
                if( split[index].isEmpty()) {
                    Logger.error( getID() + " -> Failed to convert datetime "+split[index]);
                    return input;
                }
                return String.join(delimiter,split);
            }
            Logger.error(id+" -> To few elements after split for redate");
            return input;
        };
        edits.add(edit);
    }
    /**
     * Alter the formatting of a time field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    public void addRetime( String from, String to, int index, String delimiter ){
        rulesString.add( new String[]{"","retime",from+" -> "+to} );
        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        Function<String,String> edit = input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatTime(split[index],from,to);
                if( split[index].isEmpty())
                    return input;
                return String.join(delimiter,split);
            }
            Logger.error(id+" -> To few elements after split for redate");
            return input;
        };
        edits.add(edit);
    }
    public void addRexsplit( String delimiter, String regex){
        rulesString.add( new String[]{"","rexsplit","deli:"+delimiter+" ->"+regex} );

        var results = Pattern.compile(regex);

        Function<String,String> edit = input ->
        {
            var items = results.matcher(input)
                    .results()
                    .map(MatchResult::group)
                    .toArray(String[]::new);
            return String.join(delimiter,items);
        };
        edits.add(edit);
    }
    /**
     * Split a data string according to the given delimiter, then stitch it back together based on resplit
     * @param delimiter The string to split the data with
     * @param resplit The format of the new string, using i0 etc to get original values
     */
    public void addResplit( String delimiter, String resplit, String error, boolean append){

        rulesString.add( new String[]{"","resplit","deli:"+delimiter+" ->"+resplit} );

        var is = Pattern.compile("[i][0-9]{1,3}")
                .matcher(resplit)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);

        var filler = resplit.split("[i][0-9]{1,3}");

        if(is.length==0) {
            Logger.warn(id+"(ef)-> No original data referenced in the resplit");
          //  return;
        }

        int[] indexes = new int[is.length];

        for( int a=0;a<is.length;a++){
            indexes[a] = Integer.parseInt(is[a].substring(1));
        }

        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }

        Function<String,String> edit = input ->
        {
            String[] inputEles = input.split(deli); // Get the source data

            StringJoiner join = new StringJoiner("",filler.length==0?"":rtvals.parseRTline(filler[0],error),"");
            for( int a=0;a<indexes.length;a++){
                try {
                    join.add(inputEles[indexes[a]]);
                    if( filler.length>a+1)
                        join.add( rtvals.parseRTline(filler[a+1],error));
                    inputEles[indexes[a]] = null;
                }catch( IndexOutOfBoundsException e){
                    Logger.error("Out of bounds when processing: "+input);
                }
            }
            if( indexes.length!=inputEles.length && append){
                StringJoiner rest = new StringJoiner(delimiter,delimiter,"");
                for( var a : inputEles){
                    if( a!=null)
                        rest.add(a);
                }
                join.add(rest.toString());
            }
            return join.toString();
        };
        edits.add(edit);
    }

    /**
     * Add a string to the start of the data
     * @param addition The string to add at the start
     */
    public void addPrepend( String addition ){
        rulesString.add( new String[]{"","prepend","add:"+addition} );
        edits.add( input -> addition+input );
    }
    /**
     * Add a string to the end of the data
     * @param addition The string to add at the end
     */
    public void addAppend( String addition ){
        rulesString.add( new String[]{"","append","add:"+addition} );
        edits.add(  input -> input+addition );
    }
    public void addInsert( int position, String addition ){
        rulesString.add( new String[]{"","insert","add:"+addition+" at "+position} );
        edits.add( input -> input.substring(0,position)+addition+input.substring(position) );
    }
    public void addReplacement( String find, String replace){
        edits.add( input -> input.replace(Tools.fromEscapedStringToBytes(find),Tools.fromEscapedStringToBytes(replace)) );
        rulesString.add( new String[]{"","replace","from "+find+" -> "+replace} );
    }
    public void addTrim( ){
        rulesString.add( new String[]{"","Trim","Trim spaces "} );
        edits.add(String::trim);
    }
    public void addRexRemove( String find ){
        rulesString.add( new String[]{"","regexremove","Remove "+find} );
        edits.add( input -> input.replaceAll(find,"" ) );
    }
    public void addRegexReplacement( String find, String replace){
        String r = replace.isEmpty()?" ":replace;
        rulesString.add( new String[]{"","regexreplace","from "+find+" -> '"+r+"'"} );
        edits.add( input -> input.replaceAll(find,r ) );
    }
    public void addCutStart(int characters ){
        rulesString.add( new String[]{"","cropstart","remove "+characters+" chars from start of data"} );
        edits.add( input -> input.length()>characters?input.substring(characters):"" );
    }
    public void addCutEnd( int characters ){
        rulesString.add( new String[]{"","cutend","remove "+characters+" chars from end of data"} );
        edits.add( input -> input.length()>characters?input.substring(0,input.length()-characters):"" );
    }
    public void converToAscii(String delimiter){
        rulesString.add( new String[]{"","tochar","convert delimited data to char's"} );
        edits.add( input -> {
            var join = new StringJoiner("");
            Arrays.stream(input.split(delimiter)).forEach( x -> join.add( ""+(char)NumberUtils.createInteger(x).intValue()));
            return join.toString();
        } );
    }
    /**
     * Test the workings of the editor by giving a string to process
     * @param input The string to process
     * @return The resulting string
     */
    public String test( String input ){
        Logger.info(id+" -> From: "+input);
        for( var edit:edits){
            input = edit.apply(input);
        }
        Logger.info(id+" -> To:   "+input);
        return input;
    }
    @Override
    protected String getXmlChildTag() {
        return "editor";
    }
}
