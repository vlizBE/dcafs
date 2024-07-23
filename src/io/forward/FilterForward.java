package io.forward;

import io.Writable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.taskblocks.CheckBlock;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FilterForward extends AbstractForward {

    protected ArrayList<Predicate<String>> rules = new ArrayList<>();// Rules that define the filters
    private final ArrayList<Writable> reversed = new ArrayList<>();
    private String delimiter = ",";
    private int ignoreFalse =0;
    private int freePasses=0;
    private boolean negate=false;

    public FilterForward(String id, String source, BlockingQueue<Datagram> dQueue ){
        super(id,source,dQueue,null);
    }
    public FilterForward(Element ele, BlockingQueue<Datagram> dQueue  ){
        super(dQueue,null);
        readFromXML(ele);
    }

    @Override
    protected boolean addData(String data) {

        if( doFilter(data)){
            targets.stream().forEach(wr -> wr.writeLine( data ) );
            targets.removeIf(wr -> !wr.isConnectionValid() );
            if( !label.isEmpty() ){
                dQueue.add( Datagram.build(data).writable(this).label(label) );
            }
            if( log )
                Logger.tag("RAW").info( "1\t" + (label.isEmpty()?"void":label)+"|"+getID() + "\t" + data);
        }else{
            reversed.removeIf( t-> !t.writeLine(data) );
        }
        if( !cmds.isEmpty())
            cmds.forEach( cmd->dQueue.add(Datagram.system(cmd).writable(this)));
        if( noTargets() && reversed.isEmpty() ){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( Datagram.system("ff:remove,"+id));
            return false;
        }
        return true;
    }

    /**
     * Add a target for the data that doesn't make it through the filter
     * @param wr The target Writable
     */
    public void addReverseTarget(Writable wr ){
        if( !reversed.contains(wr)) {
            reversed.add(wr);
            Logger.info(getID() + " -> Adding reverse target to " + wr.getID());
            if (!valid) {
                valid = true;
                sources.forEach(source -> dQueue.add( Datagram.system(source).writable(this)) );
            }
        }else{
            Logger.info(id+" -> Trying to add duplicate reverse target "+wr.getID());
        }

    }
    @Override
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n" );
        join.add("filter:"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
        join.add(getRules());

        StringJoiner ts = new StringJoiner(", ","    Approved data target: ","" );
        targets.forEach( x -> ts.add(x.getID()));
        if( !targets.isEmpty())
            join.add(ts.toString());

        StringJoiner ts2 = new StringJoiner(", ","    Rejected data target: ","" );
        reversed.forEach( x -> ts2.add(x.getID()));
        if( !reversed.isEmpty())
            join.add(ts2.toString());

        if( !label.isEmpty()) {
            if (label.startsWith("generic")) {
                join.add("    Given to generic/store " + label.substring(8));
            } else {
                join.add("    To label " + label);
            }
        }
        return join.toString();
    }
    @Override
    public boolean removeTarget( Writable target ){
        return targets.remove(target)||reversed.remove(target);
    }
    /**
     * Read a filter from an element in the xml
     * @param ele The element containing the filter info
     * @return The FilterForward created based on the xml element
     */
    public static FilterForward readXML(Element ele, BlockingQueue<Datagram> dQueue ){
        return new FilterForward( ele,dQueue );
    }
    protected String getXmlChildTag(){
        return "filter";
    }
    /**
     * Write this object to the given Xmlfab
     *
     * @param fab The Xmlfab to which this should be written, pointing to the das root
     */
    public void writeToXML(XMLfab fab ){
        xml = fab.getXMLPath();
        xmlOk=true;

        fab.digRoot(getXmlChildTag()+"s"); // go down to <filters>
        if( fab.selectChildAsParent(getXmlChildTag(),"id",id).isEmpty() ){
            fab.comment("Some info on what the "+id+" "+getXmlChildTag()+" does");
            fab.addParentToRoot(getXmlChildTag()).attr("id",id); // adds a parent to the root
        }

        writeBasicsToXML(fab);

        if( rules.isEmpty() ) {
            fab.build();
            return;
        }

        if( rules.size()==1 && sources.size()==1){
            fab.attr("type",rulesString.get(0)[1]).content(rulesString.get(0)[2]);
            if( !rulesString.get(0)[3].isEmpty() )
                fab.attr("delimiter",rulesString.get(0)[3]);

        }else{
            fab.content("");
            fab.removeAttr("type");
            fab.comment("Rules go here, use ff:rules to know the types");
            rulesString.forEach( rule -> {
                fab.addChild("rule",rule[2]).attr("type",rule[1]);
                if( !rule[3].isEmpty() )
                    fab.attr("delimiter",rule[3]);
            } );
        }
        fab.build();
    }

    /**
     * Read the FilterWritable setup from the xml element
     * @param filter The element containing the setup
     * @return True if all went fine
     */
    public boolean readFromXML( Element filter ){

        if( !readBasicsFromXml(filter) )
            return false;

        delimiter = XMLtools.getStringAttribute(filter,"delimiter",delimiter); // Allow for global delimiter
        ignoreFalse = XMLtools.getIntAttribute(filter,"ignores",0);
        negate = XMLtools.getBooleanAttribute(filter,"negate",false);

        rules.clear();

        if( XMLtools.hasChildByTag(filter,"rule") ){ // if rules are defined as nodes
            // Process all the types except 'start'
            XMLtools.getChildElements(filter, "rule")
                    .stream()
                    .filter( rule -> !rule.getAttribute("type").equalsIgnoreCase("start"))
                    .forEach( rule -> {
                        String delimiter = XMLtools.getStringAttribute(rule,"delimiter",this.delimiter);
                        addRule( rule.getAttribute("type"), rule.getTextContent(),delimiter);
                    } );

            ArrayList<String> starts = new ArrayList<>();

            // Process all the 'start' filters
            XMLtools.getChildElements(filter, "rule")
                    .stream()
                    .filter( rule -> rule.getAttribute("type").equalsIgnoreCase("start"))
                    .forEach( rule -> starts.add( rule.getTextContent() ) );

            if( starts.size()==1){
                addRule( "start", starts.get(0));
            }else if( starts.size()>1){
                addStartOptions( starts.toArray(new String[1]) );
            }
        }else if( filter.getTextContent() != null ){ // If only a single rule is defined
            String type = XMLtools.getStringAttribute(filter,"type","");
            if( !type.isEmpty()){
                addRule(type,filter.getTextContent(),XMLtools.getStringAttribute(filter,"delimiter",this.delimiter));
            }
        }
        return true;
    }
    /**
     * Add a rule to the filter
     * @param type predefined type of the filter eg. start,nostart,end ...
     * @param value The value for the type eg. start:$GPGGA to start with $GPGGA
     * @return -1 -> unknown type, 1 if ok
     */
    public int addRule( String type, String value, String delimiter ){
        String[] values = value.split(",");
        rulesString.add( new String[]{"",type,value,delimiter} );

        value = Tools.fromEscapedStringToBytes(value);
        Logger.info(id+" -> Adding rule "+type+" > "+value);

        switch (StringUtils.removeEnd(type, "s")) {
            case "start" -> addStartsWith(value);
            case "nostart" -> addStartsNotWith(value);
            case "end" -> addEndsWith(value);
            case "contain" -> addContains(value);
            case "c_start" -> addCharAt(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
            case "c_end" -> addCharFromEnd(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
            case "minlength" -> addMinimumLength(Tools.parseInt(value, -1));
            case "maxlength" -> addMaximumLength(Tools.parseInt(value, -1));
            case "nmea" -> addNMEAcheck(Tools.parseBool(value, true));
            case "regex" -> addRegex(value);
            case "math" -> addCheckBlock(delimiter, value);
            case "minitems" -> addMinItems( delimiter, Tools.parseInt(value, -1));
            case "maxitems" -> addMaxItems( delimiter, Tools.parseInt(value, -1));
            case "items" -> addItemCount( delimiter, Tools.parseInt(value, -1));
            default -> {
                Logger.error(id + " -> Unknown type chosen " + type);
                return -1;
            }
        }
        return 1;
    }
    public int addRule( String type, String value){
        return addRule(type,value,"");
    }
    public static String getHelp(String eol){
        StringJoiner join = new StringJoiner(eol);
        join.add("start   -> Which text the data should start with" )
            .add("    fe. <filter type='start'>$</filter> --> The data must start with $");
        join.add("nostart -> Which text the data can't start with")
            .add("    fe. <filter type='nostart'>$</filter> --> The data can't start with $");
        join.add("end     -> Which text the data should end with")
            .add("    fe. <filter type='end'>!?</filter> --> The data must end with !?");
        join.add("contain -> Which text the data should contain")
            .add("    fe. <filter type='contain'>zda</filter> --> The data must contain zda somewhere");
        join.add("c_start -> Which character should be found on position c from the start (1=first)")
            .add("    fe. <filter type='c_start'>1,+</filter> --> The first character must be a +");
        join.add("c_end   -> Which character should be found on position c from the end (1=last)")
            .add("    fe. <filter type='c_end'>3,+</filter> --> The third last character must be a +");
        join.add("minlength -> The minimum length the data should be")
            .add("    fe. <filter type='minlength'>6</filter> --> if data is shorter than 6 chars, filter out");
        join.add("maxlength -> The maximum length the data can be")
            .add("    fe.<filter type='maxlength'>10</filter>  --> if data is longer than 10, filter out");
        join.add("nmea -> True or false that it's a valid nmea string")
            .add("    fe. <filter type='nmea'>true</filter> --> The data must end be a valid nmea string");
        join.add("regex -> Matches the given regex")
            .add("    fe. <filter type='regex'>\\s[a,A]</filter> --> The data must contain an empty character followed by a in any case");
        join.add("math -> Checks a mathematical comparison")
            .add("    fe. <filter type='math' delimiter=','>i1 below 2500 and i1 above 10</filter>" );
        join.add("minitems -> Minimum amount of items after a split on delimiter")
                .add("    fe. <filter type='minitems' delimiter=','>8</filter>" );
        join.add("maxitems -> Maximum amount of items after a split on delimiter")
                .add("    fe. <filter type='maxitems' delimiter=','>8</filter>" );
        join.add("itemcount -> Amount of items after a split on delimiter")
                .add("    fe. <filter type='itemcount' delimiter=','>8</filter>" );
        return join.toString();
    }
    /**
     * Remove the given rule from the set
     * @param index The index of the rule to remove
     * @return True if a rule was removed
     */
    public boolean removeRule( int index ){
        if( index < rules.size() && index != -1 ){
            rulesString.remove(index);
            rules.remove(index);
            return true;
        }
        return false;
    }
    /**
     * Add a combined filter rule to the filter
     * @param combined Add a rule that is in the type:value format
     * @return -2 -> if the : is missing
     */
    public int addRule( String combined ){
        if( combined.isEmpty())
            return 0;
        if( !combined.contains(":")){
            Logger.error("Rule should be type:value, "+combined +" isn't like that.");
            return -2;
        }
        String type=combined.substring(0, combined.indexOf(":"));
        String val=combined.substring(combined.indexOf(":")+1);
        String delim="";
        if( type.equalsIgnoreCase("math")) {
            delim = val.substring(val.indexOf(",")+1);
            val = val.substring(0, val.indexOf(","));
        }
        return addRule(type,val,delim);
    }

    /* Filters */
    public void addStartsWith( String with ){
        rules.add( p -> p.startsWith(with) );
    }
    public void addRegex( String regex ){
        rules.add( p -> p.matches(regex));
    }
    public void addStartsNotWith( String with ){
        rules.add( p -> !p.startsWith(with) );
    }
    public void addStartOptions( String... withs ){
        Logger.info(id+" -> Multi start"+String.join(",",withs));
        rulesString.add( new String[]{"",String.join(" or ",withs),"start with"} );
        rules.add( p ->  Stream.of(withs).anyMatch( p::startsWith));
    }
    public void addContains( String contains ){
        rules.add( p -> p.contains(contains) );
    }
    public void addEndsWith( String with ){
        rules.add( p -> p.endsWith(with) );
    }
    public void addCharAt( int index, char c ){
        rules.add( p -> index >=0 && index < p.length() && p.charAt(index)==c);
    }
    public void addCharFromEnd( int index, char c ){
        rules.add( p -> index >=0 && p.length() > index && p.charAt(p.length()-index-1)==c );
    }
    public void addMinimumLength( int length ){ rules.add( p -> p.length() >= length); }
    public void addMaximumLength( int length ){ rules.add( p -> p.length() <= length); }
    public void addMinItems( String delimiter, int cnt ){ rules.add( p -> p.split(delimiter).length>=cnt); }
    public void addMaxItems( String delimiter, int cnt ){ rules.add( p -> p.split(delimiter).length<=cnt); }
    public void addItemCount( String delimiter, int cnt ){ rules.add( p -> p.split(delimiter).length==cnt); }

    public void addNMEAcheck( boolean ok ){ rules.add( p -> (/*p.startsWith("$")&&*/MathUtils.doNMEAChecksum(p))==ok ); }
    /* Complicated ones? */
    public boolean addCheckBlock( String delimiter, String value){

        var is = Pattern.compile("[i][0-9]{1,2}")// Extract all the references
                .matcher(value)
                .results()
                .distinct()
                .map(MatchResult::group)
                .map( s-> NumberUtils.toInt(s.substring(1)))
                .sorted() // so the highest one is at the bottom
                .toArray(Integer[]::new);

        var block = CheckBlock.prepBlock(null,value);
        if( !block.build() )
            return false;
        rules.add( p -> {
            try {
                String[] vals = p.split(delimiter);
                for( int index : is) {
                    if( !block.alterSharedMem(index, NumberUtils.toDouble(vals[index])) ){
                        Logger.error(id+" (ff) -> Tried to add a NaN to shared mem");
                        return false;
                    }
                }
                return block.start(null);
            } catch (ArrayIndexOutOfBoundsException e) {
                Logger.error(id + "(ff) -> Index out of bounds when trying to find the number in "+p+" for math check.");
                return false;
            }
        });
        return true;
    }

    @Override
    public boolean writeLine(String data) {
        return writeString(data);
    }
    public boolean doFilter( String data ){

        for( Predicate<String> check : rules ){
            boolean result = check.test(data);
            if( !result || negate ){
                if( freePasses==0 ) {
                    if( debug )
                        Logger.info(id+" -> "+data + " -> Failed");
                    return false;
                }else{
                    freePasses--;
                    if( debug )
                        Logger.info(id+" -> "+data + " -> Free Pass");
                    return true;
                }
            }
        }
        if( debug )
            Logger.info(id+" -> "+data + " -> Ok");
        freePasses = ignoreFalse;
        return true;
    }
}
