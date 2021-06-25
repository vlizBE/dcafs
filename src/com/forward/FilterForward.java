package com.forward;

import com.Writable;
import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class FilterForward extends AbstractForward {

    protected ArrayList<Predicate<String>> rules = new ArrayList<>();// Rules that define the filters
    protected final ArrayList<Writable> reversed = new ArrayList<>();

    public FilterForward(String id, String source, BlockingQueue<Datagram> dQueue ){
        super(id,source,dQueue);
    }
    public FilterForward(Element ele, BlockingQueue<Datagram> dQueue  ){
        super(dQueue);
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

        if( targets.isEmpty() && label.isEmpty() && !log && reversed.isEmpty() ){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( Datagram.system("ff:remove,"+id));
            return false;
        }
        return true;
    }
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
     * @param fab The Xmlfab to which this should be written, pointing to the das root
     * @return True if writing was a success
     */
    public boolean writeToXML( XMLfab fab ){
        xml = fab.getXMLPath();
        xmlOk=true;

        fab.digRoot(getXmlChildTag()+"s"); // go down to <filters>
        if( fab.selectParent(getXmlChildTag(),"id",id).isEmpty() ){
            fab.comment("Some info on what the "+id+" "+getXmlChildTag()+" does");
            fab.addParent(getXmlChildTag()).attr("id",id); // adds a parent to the root
        }

        writeBasicsToXML(fab);

        if( rules.isEmpty() )
            return fab.build()!=null;

        if( rules.size()==1 && sources.size()==1){
            fab.attr("type",rulesString.get(0)[1]).content(rulesString.get(0)[2]);
        }else{
            fab.content("");
            fab.removeAttr("type");
            fab.comment("Rules go here, use ff:rules to know the types");
            rulesString.forEach( rule -> fab.addChild("rule",rule[2]).attr("type",rule[1]) );
        }
        return fab.build()!=null;
    }

    /**
     * Read the FilterWritable setup from the xml element
     * @param filter The element containing the setup
     * @return True if all went fine
     */
    public boolean readFromXML( Element filter ){

        if( !readBasicsFromXml(filter) )
            return false;

        rules.clear();

        if( XMLtools.hasChildByTag(filter,"rule") ){ // if rules are defined as nodes
            // Process all the types except 'start'
            XMLtools.getChildElements(filter, "rule")
                    .stream()
                    .filter( rule -> !rule.getAttribute("type").equalsIgnoreCase("start"))
                    .forEach( rule -> addRule( rule.getAttribute("type"), rule.getTextContent()) );

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
                addRule(type,filter.getTextContent());
            }
        }
        return true;
    }
    public void reload(){
        rules.clear();
    }   
    /**
     * Add a rule to the filter
     * @param type predefined type of the filter eg. start,nostart,end ...
     * @param value The value for the type eg. start:$GPGGA to start with $GPGGA
     * @return -1 -> unknown type, 1 if ok
     */
    public int addRule( String type, String value ){
        String[] values = value.split(",");
        rulesString.add( new String[]{"",type,value} );

        value = Tools.fromEscapedStringToBytes(value);
        Logger.info(id+" -> Adding rule "+type+" > "+value);

        switch( StringUtils.removeEnd(type,"s") ){
            case "start":     addStartsWith(value); break;
            case "nostart":   addStartsNotWith(value); break;
            case "end":       addEndsWith(value);   break;
            case "contain":   addContains(value);   break;
            case "c_start":   addCharAt(Tools.parseInt(values[0], -1)-1, value.charAt(value.indexOf(",")+1) );      break;
            case "c_end":     addCharFromEnd(Tools.parseInt(values[0], -1)-1, value.charAt(value.indexOf(",")+1) ); break;
            case "minlength": addMinimumLength(Tools.parseInt(value,-1)); break;
            case "maxlength": addMaximumLength(Tools.parseInt(value,-1)); break;
            case "nmea":      addNMEAcheck( Tools.parseBool(value,true));break;
            default: 
                Logger.error(id+" -> Unknown type chosen "+type);
                return -1;
        }
        return 1;
    }
    public static String getHelp(String eol){
        StringJoiner join = new StringJoiner(eol);
        join.add("start   -> Which text the data should start with" )
            .add("    fe. <filter type='star'>$</filter> --> The data must start with $");
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

        return addRule(type,val);
    }

    /* Filters */
    public void addStartsWith( String with ){
        rules.add( p -> p.startsWith(with) );
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

    public void addNMEAcheck( boolean ok ){ rules.add( p -> (/*p.startsWith("$")&&*/MathUtils.doNMEAChecksum(p))==ok ); }

    @Override
    public boolean writeLine(String data) {
        return writeString(data);
    }
    public boolean doFilter( String data ){
        
        for( Predicate<String> check : rules ){
            if( !check.test(data) ){
                if( debug )
                    Logger.info(id+" -> "+data + " -> Failed");
                return false;
            }
        }
        if( debug )
            Logger.info(id+" -> "+data + " -> Ok");
        return true;
    }
}
