package das;

import io.Writable;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.*;

public class Configurator {

    Path settings;
    enum STATE {MODULES,INSTANCES, FILL_IN, YES_NO_OPT}
    STATE state = STATE.MODULES;
    XMLfab ref;
    XMLfab target;
    HashMap<Integer,ArrayList<String[]>> lvls = new HashMap<>();
    ArrayList<String[]> attr = new ArrayList<>();
    int steps=1;
    Writable wr;

    /* default Base White */
    String DEF_COLOR = TelnetCodes.TEXT_WHITE;

    String HINT_COLOR = TelnetCodes.TEXT_LIGHT_GRAY;
    String INFO_COLOR = TelnetCodes.TEXT_LIGHT_GRAY;
    String FB_COLOR   = TelnetCodes.TEXT_GRAY + ">> ";

    String Q_COLOR    = TelnetCodes.TEXT_GREEN;
    String REQ_COLOR  = TelnetCodes.TEXT_ORANGE;
    String ERROR_COLOR = TelnetCodes.TEXT_RED;
    String INTRO_COLOR = ERROR_COLOR;

    public Configurator( Path settings, Writable wr ){

        this.settings=settings;
        this.wr=wr;

        target = XMLfab.withRoot(settings,"dcafs");
        var doc = XMLtools.readResourceXML(this.getClass(),"/config.xml");
        if(doc.isEmpty()){
            Logger.error("Config.xml not found");
            return;
        }
        ref = XMLfab.withRoot(doc.get(),"dcafs");
        lvls.put(0, addChildren(false,true));

        // Colors
        baseWhiteColoring();

        // Begin
        wr.writeLine(getStartMessage(true));
    }
    private void baseWhiteColoring(){
        DEF_COLOR = TelnetCodes.TEXT_WHITE;

        HINT_COLOR = TelnetCodes.TEXT_LIGHT_GRAY;
        INFO_COLOR = TelnetCodes.TEXT_LIGHT_GRAY;
        FB_COLOR   = TelnetCodes.TEXT_GRAY + ">> ";

        Q_COLOR    = TelnetCodes.TEXT_GREEN;
        REQ_COLOR  = TelnetCodes.TEXT_ORANGE;
        ERROR_COLOR = TelnetCodes.TEXT_RED;
        INTRO_COLOR = ERROR_COLOR;
    }
    private void baseBlackColoring(){
        DEF_COLOR = TelnetCodes.TEXT_BLACK;

        HINT_COLOR = TelnetCodes.TEXT_GRAY;
        INFO_COLOR = TelnetCodes.TEXT_GRAY;
        FB_COLOR   = TelnetCodes.TEXT_LIGHT_GRAY + ">> ";

        Q_COLOR    = TelnetCodes.TEXT_GREEN;
        REQ_COLOR  = TelnetCodes.TEXT_ORANGE;
        ERROR_COLOR = TelnetCodes.TEXT_RED;
        INTRO_COLOR = ERROR_COLOR;
    }
    private void baseYellowColoring(){
        DEF_COLOR = TelnetCodes.TEXT_BRIGHT_YELLOW;

        HINT_COLOR = TelnetCodes.TEXT_YELLOW;
        INFO_COLOR = TelnetCodes.TEXT_YELLOW;
        FB_COLOR   = TelnetCodes.TEXT_GRAY  + ">> ";

        Q_COLOR    = TelnetCodes.TEXT_GREEN;
        REQ_COLOR  = TelnetCodes.TEXT_ORANGE;
        ERROR_COLOR = TelnetCodes.TEXT_RED;
        INTRO_COLOR = ERROR_COLOR;
    }
    /**
     * Get the introduction and first selection of the parent node
     * @param intro Add the intro or not
     * @return The first message to the user
     */
    private String getStartMessage(boolean intro){
        var join = new StringJoiner("\r\n");
        if( intro ) {
            join.add(INTRO_COLOR + "Welcome to dcafs QA!"+DEF_COLOR);
            join.add("Info: ")
                    .add("- Typing just enough to pick the right one (fe. streams -> st) is enough")
                    .add("- If there's a default value, just pressing enter (so sending empty response) will fill that in")
                    .add("- Colors: "+Q_COLOR+"Not required will use default, "+REQ_COLOR+"must be filled in, "+INFO_COLOR+"info"+DEF_COLOR);
            join.add("");
        }

        join.add(Q_COLOR+"Add a instance to? "+DEF_COLOR);
        lvls.get(0).forEach(x -> join.add(" -> "+x[0] +"\t"+ HINT_COLOR+x[1]+DEF_COLOR )); // Get all the tag names

        return join.toString();
    }
    private ArrayList<String[]> addChildren( boolean withContent, boolean sort){
        ArrayList<String[]> list = new ArrayList<>();
        ref.getChildren("*").forEach(
                ele -> {
                    if( list.stream().filter(l -> l[0].equalsIgnoreCase(ele.getTagName())).findFirst().isEmpty())
                       list.add( new String[]{ele.getTagName(),withContent?ele.getTextContent():ele.getAttribute("hint")});
                }
        );
        if( sort ) {
            list.sort( Comparator.comparing(a -> a[0]) );
        }
        return list;
    }
    public String reply(String input){
        String match;
        switch( state ){
            case MODULES: // Global nodes like streams,filters etc
                match = findMatch(lvls.get(0), input );
                if( !match.isEmpty()){
                    if( match.equalsIgnoreCase("bad")){
                        return ERROR_COLOR+ "No valid option selected!\r\n"+getStartMessage(false)
                                +DEF_COLOR;
                    }else{
                        ref.selectChildAsParent(match);
                        lvls.put(steps,addChildren(true,false));
                        target.selectOrAddChildAsParent(match);
                        state=STATE.INSTANCES;

                        return FB_COLOR+"Stepped into "+match+"\r\n"+REQ_COLOR+"Instance?" +INFO_COLOR+" Options:"
                                + formatNodeOptions(lvls.get(1))+DEF_COLOR;
                    }
                }else{
                    return INTRO_COLOR+"bye"+DEF_COLOR;
                }
            case INSTANCES: // Figure out with child nodes are possible and merge attributes
                steps=1;
                match = findMatchContent(lvls.get(steps), input );
                if( !match.isEmpty()){
                    target.addChild(match,"").down();
                    wr.writeLine(FB_COLOR+"Building "+match+DEF_COLOR);
                    if(ref.getChildren(match).size()>=1){
                        ref.getChildren(match).forEach(
                                child -> {
                                    for( var pair : XMLfab.getAttributes(child) ){
                                        boolean found =false;
                                        for (String[] strings : attr) {
                                            if (strings[0].equalsIgnoreCase(pair[0])) {
                                                if (!strings[1].equalsIgnoreCase(pair[1]))
                                                    strings[1] += "," + pair[1];
                                                found = true;
                                                break;
                                            }
                                        }
                                        if (!found) {
                                            attr.add(pair);
                                        }
                                    }
                                }
                        );
                    }else{
                        ref.selectChildAsParent(match);
                        return "dunno how i got here";
                    }
                    state=STATE.FILL_IN;
                    return formatAttrQuestion();
                }else{
                    state=STATE.MODULES;
                    return getStartMessage(false);
                }
            case FILL_IN: // Fill in the child node (stream,filter) attributes/content
                // If there are options given, one must be chosen
                if( input.isEmpty() ){
                    if( attr.isEmpty()){ // finish the node
                        // Set the content of the node
                        input = lvls.get(steps).get(0)[1];
                        // Go to next child
                    }else {
                        if (attr.get(0)[1].equalsIgnoreCase("!") || attr.get(0)[1].contains(","))
                            return ERROR_COLOR + "Required field, try again..." + DEF_COLOR;
                        input = attr.get(0)[1];
                    }
                }
                if( !input.isEmpty()){
                    if( attr.isEmpty()){
                        String tag =  lvls.get(steps).get(0)[0];

                        if( !regexMatches(tag,input) )
                            return "";

                        target.alterChild(tag, input);
                        wr.writeLine(FB_COLOR+"Set "+tag+" content to "+input+DEF_COLOR);
                        lvls.get(steps).remove(0);// Finished the node, go to next
                    }else {
                        if ( attr.get(0)[1].matches("!\\|?") || attr.get(0)[1].contains(input)) {
                            if (attr.get(0)[1].contains(input)) {
                                // if the content contains the input
                                for (String x : attr.get(0)[1].split(",")) {
                                    if (x.matches(input + ".*")) {
                                        input = x;
                                        break;
                                    }
                                }
                                ref.selectChildAsParent(target.getName(), attr.get(0)[0], ".*" + input + ".*");
                            }
                            target.attr(attr.get(0)[0], input);
                            wr.writeLine(FB_COLOR+"Set "+attr.get(0)[0]+" attribute to "+input+DEF_COLOR);
                            attr.remove(0);
                        } else {
                            return ERROR_COLOR + "Invalid input, try again..." + DEF_COLOR;
                        }
                    }
                   // Check if this is the last one
                   if( attr.isEmpty()) {
                       String nam = target.getName();
                       String rn = ref.getName();
                       if( nam.equalsIgnoreCase(rn) && ref.getChildren(target.getName()).size()>1){ // pointing to the same
                           ref.up(); //go back up
                       }else {
                           if (ref.getChildren(target.getName()).size() == 1) {
                               ref.selectChildAsParent(target.getName());
                           }
                           steps++;
                           lvls.put(steps,addChildren(true,false));
                       }
                       // At this point raw is pointing to the parent
                       if( lvls.get(steps).isEmpty() ){
                           steps--;
                           if( lvls.get(steps).get(0)[1].isEmpty() ) { // empty node, go to next
                               lvls.get(steps).remove(0);
                           }
                       }
                       if(!lvls.get(steps).isEmpty()) {
                           // First check attributes!
                           attr=fillAttributes(lvls.get(steps).get(0)[0]);
                           if( attr.isEmpty() ){
                               // Ask about text content
                               ref.up().selectChildAsParent(lvls.get(steps).get(0)[0]);
                               return formatNodeQuestion();
                           }else{
                               // Ask about first attribute
                               String cf = getCf(lvls.get(steps).get(0)[0]);
                               if( cf.contains("opt")){
                                   state=STATE.YES_NO_OPT;
                                   return TelnetCodes.TEXT_ORANGE+
                                           "Want to make a "+ lvls.get(steps).get(0)[0]+" node? y/n"
                                           +DEF_COLOR;
                               }
                               target.down().addChild(lvls.get(steps).get(0)[0],"");
                               return formatAttrQuestion();
                           }
                       }else {
                           return formatNodeQuestion();
                       }
                   }
                   target.build();
                   return formatAttrQuestion();
               }
               break;
            case YES_NO_OPT: // Handle optional stuff?
                switch( input ){
                    case "y": // Execute the node
                        state=STATE.FILL_IN;
                        target.down().addChild(lvls.get(steps).get(0)[0],"");
                        return formatAttrQuestion();
                    case "n": // Skip the node
                        state=STATE.FILL_IN;
                        lvls.get(steps).remove(0); // remove the optional node
                        return nextNode();
                    default:
                        return "Invalid choice, just y or n...";
                }
        }
        return "dunno";
    }
    private String nextNode(){
        if(!lvls.get(steps).isEmpty()) {
            // First check attributes!
            attr=fillAttributes(lvls.get(steps).get(0)[0]);
            if( attr.isEmpty() ){
                // Ask about text content
                return formatNodeQuestion();
            }else{
                // Ask about first attribute
                String cf = getCf(lvls.get(steps).get(0)[0]);
                if( cf.contains("opt")){
                    state=STATE.YES_NO_OPT;
                    return "Want to make a "+ lvls.get(steps).get(0)[0]+" node? y/n";
                }
                target.down().addChild(lvls.get(steps).get(0)[0],"");
                return formatAttrQuestion();
            }
        }else {
            steps--;
            if( steps==0 ){ // Finished the node
                state=STATE.INSTANCES; // Go back to node selection
                target.build(); // Build to file so far
                target.up(); // Step back up the settings.xml
                ref.up(); // Step back up the config

                return TelnetCodes.TEXT_ORANGE+"Returned to "+ target.getName()+", options: "
                        + formatNodeOptions(lvls.get(0))+DEF_COLOR;
            }
            return formatNodeQuestion();
        }
    }

    /**
     * Find a match for the given input in the list
     * @param list
     * @param input
     * @return
     */
    private String findMatch( ArrayList<String[]> list,String input){
        if( input.isEmpty())
            return input;
        String res = list.stream().filter( l -> l[0].matches(input)).map(l -> l[0]).findFirst().orElse("");
        if( !res.isEmpty() )
            return res;
        return list.stream().filter( l -> l[0].startsWith(input)).map(l -> l[0]).findFirst().orElse("bad");
    }
    private String findMatchContent( ArrayList<String[]> list, String input){
        if( input.isEmpty())
            return "";
        return list.stream().filter( l -> l[0].startsWith(input)).findFirst().map(l->l[0]).orElse("");
    }
    private ArrayList<String[]> fillAttributes( String from ){
        ArrayList<String[]> at=new ArrayList<>();
        ref.getChildren(from).forEach(
                child -> {
                    for( var pair : XMLfab.getAttributes(child) ){
                        boolean found =false;
                        for (String[] strings : at) {
                            if (strings[0].equalsIgnoreCase(pair[0])) {
                                if (!strings[1].equalsIgnoreCase(pair[1]))
                                    strings[1] += "," + pair[1];
                                found = true;
                                break;
                            }
                        }
                        if (!found && !pair[0].equalsIgnoreCase("hint")
                                && !pair[0].equalsIgnoreCase("regex")
                                && !pair[0].equalsIgnoreCase("cf")) {
                            at.add(pair);
                        }
                    }
                }
        );
        return at;
    }
    /**
     * Formats the given attribute options
     * @return The formatted content
     */
    private String formatAttrQuestion( ){
        String ori = target.getName();
        String[] from = attr.get(0);

        var list = Tools.extractMatches(from[1],"\\{.*\\}");
        // Replace the {..} with values
        list.forEach( m ->
        {
            var at = target.getAttribute(m.substring(1,m.length()-1));
            if( !at.isEmpty())
                from[1]=from[1].replace(m,at);
        });

        String q;
        if( from[1].startsWith("!")|| from[1].contains(",") ){
            q = REQ_COLOR+ori+"->"+from[0]+"? "+Q_COLOR;
            if( from[1].length()==1)
                return q+DEF_COLOR;
        }else{
            q = Q_COLOR+ori+"->"+from[0]+"? ";
        }
        return q + INFO_COLOR +(from[1].contains(",")?" Options: ":" Default: ") + from[1] + DEF_COLOR;
    }

    /**
     * Build the question for the node content
     * @return
     */
    private String formatNodeQuestion( ){
        String[] from = lvls.get(steps).get(0);

        String q;
        if( from[1].equalsIgnoreCase("!") ) {
            q = REQ_COLOR + from[0] + "? " + Q_COLOR;
        }else{
            q = Q_COLOR + from[0] + "? "+ INFO_COLOR +" Default: "+from[1];
        }
        return q +" " + getHint(from[0]) + DEF_COLOR;
    }

    /**
     * Retrieve the hint from the active node
     * @param from The name of the node
     * @return The hint if found or empty if none
     */
    private String getHint( String from ){
        var hint = getAttribute(from,"hint");
        return hint.isEmpty()?"":INFO_COLOR+" ("+hint+")";
    }
    /**
     * Retrieve the regex req from the active node
     * @param from The name of the node
     * @return The result of the regex check
     */
    private boolean regexMatches(String from, String toCheck ){
        var regex =  getAttribute(from,"regex");
        if( !regex.isEmpty() && !toCheck.matches(regex) ) {
            wr.writeLine(ERROR_COLOR + "No valid input given, try again... (regex: " + regex + ")" + DEF_COLOR);
            return false;
        }
        return true;
    }
    public String getCf( String from ){
        return getAttribute(from,"cf");
    }
    private String getAttribute( String from, String att ){
        var child = ref.getChild(from);
        if( child.isEmpty())
            return ref.getAttribute(att);
        return child.map( ch ->  ch.hasAttribute(att)?ch.getAttribute(att):"").orElse("");
    }

    private String formatNodeOptions(ArrayList<String[]> list ){
        var join = new StringJoiner(",");
        list.forEach( l -> join.add(l[0]));
        return join.toString();
    }
}