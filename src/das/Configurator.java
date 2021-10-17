package das;

import io.Writable;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.Tools;
import util.xml.XMLfab;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

public class Configurator {

    Path settings;
    enum STATE {MODULES,INSTANCES, FILL_IN, YES_NO}
    STATE state = STATE.MODULES;
    XMLfab ref;
    XMLfab target;
    HashMap<Integer,ArrayList<String[]>> lvls = new HashMap<>();
    ArrayList<String[]> attr = new ArrayList<>();
    int steps=1;
    Writable wr;

    public Configurator( Path settings, Writable wr ){

        this.settings=settings;
        this.wr=wr;

        target = XMLfab.withRoot(settings,"dcafs");
        Optional<Path> configOpt = FileTools.getPathToResource(this.getClass(),"config.xml");
        configOpt.ifPresent(path -> ref = XMLfab.withRoot(path, "dcafs"));

        lvls.put(0, addChildren(false,true));
        wr.writeLine(getStartMessage(true));
    }

    /**
     * Get the introduction and first selection of the parent node
     * @param intro Add the intro or not
     * @return The first message to the user
     */
    private String getStartMessage(boolean intro){
        var join = new StringJoiner("\r\n");
        if( intro ) {
            join.add(TelnetCodes.TEXT_CYAN + "Welcome to dcafs QA!");
            join.add("Hints: ")
                    .add("- You don't have to type the full word if there are options given, just enough to pick the right one (fe. streams -> st")
                    .add("- If there's a default value, just pressing enter (so sending empty response) will fill that in");
            join.add("");
        }

        join.add(TelnetCodes.TEXT_ORANGE+"Add a instance to? "+TelnetCodes.TEXT_YELLOW);
        lvls.get(0).forEach(x -> join.add(" -> "+x[0] +"\t"+ TelnetCodes.TEXT_MAGENTA+x[1]+TelnetCodes.TEXT_YELLOW )); // Get all the tag names

        return join.toString();
    }
    private ArrayList<String[]> addChildren( boolean withContent, boolean sort){
        ArrayList<String[]> list = new ArrayList<>();
        ref.getChildren("*").forEach(
                ele -> {
                    if( list.stream().filter(l -> l[0].equalsIgnoreCase(ele.getTagName())).findFirst().isEmpty()) {
                        if( ele.hasAttribute("hint") )
                        list.add( new String[]{ele.getTagName(),withContent?ele.getTextContent():ele.getAttribute("hint")});
                    }
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
                        return TelnetCodes.TEXT_RED+ "No valid option selected!\r\n"+getStartMessage(false)
                                +TelnetCodes.TEXT_YELLOW;
                    }else{
                        ref.selectChildAsParent(match);
                        lvls.put(steps,addChildren(true,false));
                        target.selectOrAddChildAsParent(match);
                        state=STATE.INSTANCES;
                        return TelnetCodes.TEXT_ORANGE+"Stepped into "+match+", new options: "
                                + formatNodeOptions(lvls.get(1))+TelnetCodes.TEXT_YELLOW;
                    }
                }else{
                    return "bye";
                }
            case INSTANCES: // Figure out with child nodes are possible and merge attributes
                steps=1;
                match = findMatchContent(lvls.get(steps), input );
                if( !match.isEmpty()){
                    target.addChild(match,"").down();
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
                    String ori = target.getName();
                    return formatAttrQuestion(ori,attr.get(0));
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
                            return TelnetCodes.TEXT_RED + "Required field, try again..." + TelnetCodes.TEXT_YELLOW;
                        input = attr.get(0)[1];
                    }
                }
                if( !input.isEmpty()){
                    if( attr.isEmpty()){
                        String tag =  lvls.get(steps).get(0)[0];

                        String regex = getRegex(tag);
                        if( !regex.isEmpty() && !input.matches(regex) )
                            return TelnetCodes.TEXT_RED+"No valid input given, try again... (regex: "+regex+")"+TelnetCodes.TEXT_YELLOW;

                        target.alterChild(tag, input);
                        lvls.get(steps).remove(0);// Finished the node, go to next
                    }else {
                        if (attr.get(0)[1].matches("!\\|?") || !attr.get(0)[1].contains(",")) {
                            target.attr(attr.get(0)[0], input);
                            attr.remove(0);
                        } else if (attr.get(0)[1].contains(input)) {
                            for (String x : attr.get(0)[1].split(",")) {
                                if (x.matches(input + ".*")) {
                                    input = x;
                                    break;
                                }
                            }
                            ref.selectChildAsParent(target.getName(), attr.get(0)[0], ".*" + input + ".*");
                            target.attr(attr.get(0)[0], input);
                            attr.remove(0);
                        } else {
                            return TelnetCodes.TEXT_RED + "Invalid input, try again..." + TelnetCodes.TEXT_YELLOW;
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
                               return formatNodeQuestion(lvls.get(steps).get(0));
                           }else{
                               // Ask about first attribute
                               String cf = getCf(lvls.get(steps).get(0)[0]);
                               if( cf.contains("opt")){
                                   state=STATE.YES_NO;
                                   return TelnetCodes.TEXT_ORANGE+
                                           "Want to make a "+ lvls.get(steps).get(0)[0]+" node? y/n"
                                           +TelnetCodes.TEXT_YELLOW;
                               }
                               target.down().addChild(lvls.get(steps).get(0)[0],"");
                               return formatAttrQuestion(target.getName(),attr.get(0));
                           }
                       }else {
                           return formatNodeQuestion(lvls.get(steps).get(0));
                       }
                   }
                   target.build();
                   return formatAttrQuestion(target.getName(),attr.get(0));
               }
               break;
            case YES_NO: // Handle optional stuff?
                switch( input ){
                    case "y": // Execute the node
                        state=STATE.FILL_IN;
                        target.down().addChild(lvls.get(steps).get(0)[0],"");
                        return formatAttrQuestion(target.getName(),attr.get(0));
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
                return formatNodeQuestion(lvls.get(steps).get(0));
            }else{
                // Ask about first attribute
                String cf = getCf(lvls.get(steps).get(0)[0]);
                if( cf.contains("opt")){
                    state=STATE.YES_NO;
                    return "Want to make a "+ lvls.get(steps).get(0)[0]+" node? y/n";
                }
                target.down().addChild(lvls.get(steps).get(0)[0],"");
                return formatAttrQuestion(target.getName(),attr.get(0));
            }
        }else {
            steps--;
            if( steps==0 ){ // Finished the node
                state=STATE.INSTANCES; // Go back to node selection
                target.build(); // Build to file so far
                target.up(); // Step back up the settings.xml
                ref.up(); // Step back up the config

                return TelnetCodes.TEXT_ORANGE+"Returned to "+ target.getName()+", options: "
                        + formatNodeOptions(lvls.get(0))+TelnetCodes.TEXT_YELLOW;
            }
            return formatNodeQuestion(lvls.get(steps).get(0));
        }
    }
    private String findMatch( ArrayList<String[]> list,String input){
        if( input.isEmpty())
            return input;
        String res = list.stream().filter( l -> l[0].matches(input)).map(l -> l[0]).findFirst().orElse("");
        if( !res.isEmpty() )
            return res;
        return list.stream().filter( l -> l[0].startsWith(input)).map(l -> l[0]).findFirst().orElse("bad");
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
     * @param from The content of the attribute
     * @return The formatted content
     */
    private String formatAttrQuestion(String ori, String[] from ){
        String q = TelnetCodes.TEXT_GREEN+ori+"->"+from[0]+"? ";

        var list = Tools.extractMatches(from[1],"\\{.*\\}");
        list.forEach( m ->
        {
            var at = target.getAttribute(m.substring(1,m.length()-1));
            if( !at.isEmpty())
                from[1]=from[1].replace(m,at);
        });
        switch( from[1] ){
            case "!": q += TelnetCodes.TEXT_RED+" REQUIRED"; break;
            case "?": return q;
            default:
                if( from[1].startsWith("!")) {
                    q += TelnetCodes.TEXT_RED + "REQUIRES " + TelnetCodes.TEXT_GREEN + " Options: " + attr.get(0)[1].substring(1);
                }else{
                    if( from[1].contains(",")) {// options are always required
                        q += " Options: " + from[1];
                    }else{ // If a default, that is used if no input is given
                        q += " Default: "+from[1];
                    }
                }
        }
        return q+TelnetCodes.TEXT_YELLOW;
    }
    private String formatNodeQuestion( String[] from ){
        return TelnetCodes.TEXT_GREEN
                + from[0]+"?"
                + formatContent(lvls.get(steps).get(0)[1])+TelnetCodes.TEXT_GREEN
                + getHint(lvls.get(steps).get(0)[0])
                + TelnetCodes.TEXT_YELLOW;
    }
    private String formatContent( String from ){
        switch( from ){
            case "!": return TelnetCodes.TEXT_RED+" REQUIRED";
            case "?": return "";
            default:
                return "( Default: "+from+" )";
        }
    }
    private String getHint( String from ){
        var hint = getAttribute(from,"hint");
        return hint.isEmpty()?"":TelnetCodes.TEXT_MAGENTA+" hint:"+hint;
    }
    private String getRegex( String from ){
        return getAttribute(from,"regex");
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
    private String findMatchContent( ArrayList<String[]> list,String input){
        if( input.isEmpty())
            return "";
        return list.stream().filter( l -> l[0].startsWith(input)).findFirst().map(l->l[0]).orElse("");
    }
    private String formatNodeOptions(ArrayList<String[]> list ){
        var join = new StringJoiner(",");
        list.forEach( l -> join.add(l[0]));
        return join.toString();
    }

}
