package com.stream.forward;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class TextForward extends AbstractForward{
    ArrayList<Function<String,String>> edits = new ArrayList<>(); // for the scale type

    public TextForward(String id, String source, BlockingQueue<Datagram> dQueue ){
        super(id,source,dQueue);
    }
    public TextForward(Element ele, BlockingQueue<Datagram> dQueue  ){
        super(dQueue);
        readFromXML(ele);
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

        if( !label.isEmpty() ){ // If the object has a label associated
            var d = new Datagram(this,label,data); // build a datagram with it
            d.setOriginID("editor:"+id);
            dQueue.add( d ); // add it to the queue
        }
        // If there are no target, no label, this no longer needs to be a target
        if( targets.isEmpty() && label.isEmpty() ){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( new Datagram("editor:remove,"+id,1,"system") ); // todo
            return false;
        }
        return true;
    }

    @Override
    public boolean writeToXML(XMLfab fab) {
        return false;
    }

    @Override
    public boolean readFromXML(Element editor) {
        id = XMLtools.getStringAttribute( editor, "id", "");
        if( id.isEmpty() )
            return false;
        Logger.info(id+" -> Reading from xml");
        label = XMLtools.getStringAttribute( editor, "label", "");

        if( !label.isEmpty() ){ // this counts as a target, so enable it
            valid=true;
        }

        sources.clear();
        edits.clear();
        rulesString.clear();

        addSource(XMLtools.getStringAttribute( editor, "src", ""));
        XMLtools.getChildElements(editor, "src").forEach( ele ->sources.add(ele.getTextContent()) );

        if( XMLtools.hasChildByTag(editor,"edit") ) { // if rules are defined as nodes
            // Process all the types except 'start'

            XMLtools.getChildElements(editor, "edit")
                    .stream()
                    .forEach( edit ->
                    {
                        String deli = XMLtools.getStringAttribute(edit,"delimiter",",");
                        String content = edit.getTextContent();
                        String from = XMLtools.getStringAttribute(edit,"from",",");
                        String find = XMLtools.getStringAttribute(edit,"find","");
                        String leftover = XMLtools.getStringAttribute(edit,"leftover","append");

                        int index = XMLtools.getIntAttribute(edit,"index",-1);

                        if( content == null ){
                            Logger.error(id+" -> Missing content in an edit.");
                            return;
                        }
                        if( index == -1 ){
                            Logger.warn(id+" -> Using default index of 0");
                            index=0;
                        }
                        switch(edit.getAttribute("type")){
                            case "resplit":
                                addResplit(deli,content,leftover.equalsIgnoreCase("append"));
                                Logger.info(id+" -> Added resplit edit on delimiter "+deli+" with formula "+content);
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
                            case "rexreplace": case "regexreplace":
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
                            case "rexremove":
                                addRegexReplacement(content,"");
                                Logger.info(id+" -> Remove matches off "+content);
                                break;
                            case "rexsplit":
                                addRexsplit(deli,content);
                                Logger.info(id+" -> Get items from "+content+ " and join with "+deli);
                                break;
                            case "rexkeep":
                                addRexsplit("",content);
                                Logger.info(id+" -> Keep result of "+content);
                                break;
                            case "prepend":
                                addPrepend(content);
                                Logger.info(id+" -> Added prepend of "+content);
                                break;
                            case "append":
                                addAppend(content);
                                Logger.info(id+" -> Added append of "+content);
                                break;
                        }
                    });
        }

        return false;
    }

    /**
     * Alter the formatting of a date field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    public void addRedate( String from, String to, int index, String delimiter ){
        rulesString.add( new String[]{"","redata",from+" -> "+to} );
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
                split[index] = TimeTools.reformatDate(split[index],from,to);
                if( split[index].isEmpty())
                    return input;
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
    public void addResplit( String delimiter, String resplit, boolean append){

        rulesString.add( new String[]{"","resplit","deli:"+delimiter+" ->"+resplit} );

        var is = Pattern.compile("[i][0-9]{1,2}")
                .matcher(resplit)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);


        int[] indexes = new int[is.length];
        ArrayList<String> fillers = new ArrayList<>();
        for( int a=0;a<is.length;a++ ){
            // Get the indexes
            indexes[a] = Integer.parseInt(is[a].substring(1));

            // Get the filler elements
            int end = a+1<is.length?resplit.indexOf(is[a+1]):resplit.length();
            fillers.add( resplit.substring( resplit.indexOf( is[a] )+is[a].length(),end));

            resplit=resplit.substring(end);
        }
        String[] fill = fillers.toArray(new String[0]);
        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        Function<String,String> edit = input ->
        {
            String[] split = input.split(deli); // Get the source data
            StringJoiner join = new StringJoiner("");
            for( int a=0;a<indexes.length;a++){
                join.add(split[indexes[a]]).add(fill[a]);
                split[indexes[a]]=null;
            }
            if( indexes.length!=split.length && append){
                StringJoiner rest = new StringJoiner(delimiter,delimiter,"");
                for( int a=0;a<split.length;a++){
                    if( split[a]!=null)
                        rest.add(split[a]);
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
    public void addReplacement( String find, String replace){
        rulesString.add( new String[]{"","replace","from "+find+" -> "+replace} );
        edits.add( input -> input.replace(find,replace) );
    }
    public void addRegexReplacement( String find, String replace){
        rulesString.add( new String[]{"","regexreplace","from "+find+" -> "+replace} );
        edits.add( input -> input.replaceAll(find,replace) );
    }
    /**
     *
     * @param input
     * @return
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
