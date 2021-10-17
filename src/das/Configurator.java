package das;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLfab;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

public class Configurator {

    Path settings;
    XMLfab raw;
    XMLfab set;
    ArrayList<String> lvl1 = new ArrayList<>();
    HashMap<Integer,ArrayList<String[]>>lvlx = new HashMap<>();
    int lvl=0;
    ArrayList<String[]> attr = new ArrayList<>();
    int steps=0;

    public Configurator( Path settings){

        this.settings=settings;
        set = XMLfab.withRoot(settings,"dcafs");
        Optional<Path> configOpt = getPathToResource(this.getClass(),"config.xml");
        if( configOpt.isPresent()){
            raw = XMLfab.withRoot(configOpt.get(),"dcafs");
        }
        addChildren(lvl1);
    }
    public String getStartMessage(boolean intro){
        var join = new StringJoiner("\r\n");
        if( intro ) {
            join.add(TelnetCodes.TEXT_CYAN + "Welcome to dcafs QA!");
            join.add("Hints: ")
                    .add("- You don't have to type the full word if there are options given, just enough to pick the right one")
                    .add("- If there's a default value, just pressing enter (so sending empty response) will fill that in");
            join.add("");
        }
        join.add(TelnetCodes.TEXT_ORANGE+"Add a instance to? "+String.join(",",lvl1)+TelnetCodes.TEXT_YELLOW);
        return join.toString();
    }
    private void addChildren( ArrayList<String> list ){
        list.clear();
        raw.getChildren("*").forEach(
                ele -> {
                    if( !list.contains(ele.getTagName())) {
                        list.add(ele.getTagName());
                    }
                }
        );
        Collections.sort(list);
    }
    private ArrayList<String[]> addChildrenWithContent(  ){
        ArrayList<String[]> list = new ArrayList<>();
        raw.getChildren("*").forEach(
                ele -> {
                    if(list.stream().filter(l -> l[0].equalsIgnoreCase(ele.getTagName())).findFirst().isEmpty()) {
                        list.add(new String[]{ele.getTagName(), ele.getTextContent()});
                        Logger.info("Adding " + ele.getTagName());
                    }
                }
        );
        return list;
    }
    public String replyTo( String input){
        String match="";
        switch( lvl ){
            case 0: // Global nodes like streams,filters etc
                match = findMatch(lvl1, input );
                if( !match.isEmpty()){
                    if( match.equalsIgnoreCase("bad")){
                        return TelnetCodes.TEXT_RED+ "No valid option selected!\r\n"+getStartMessage(false)
                                +TelnetCodes.TEXT_YELLOW;
                    }else{
                        raw.selectChildAsParent(match);
                        lvlx.put(0,addChildrenWithContent());
                        set.selectOrAddChildAsParent(match);
                        lvl++;
                        return TelnetCodes.TEXT_ORANGE+"Stepped into "+match+", new options: "
                                + formatNodeOptions(lvlx.get(0))+TelnetCodes.TEXT_YELLOW;
                    }
                }else{
                    return "bye";
                }
            case 1: // Figure out with childnodes are possible and merge attributes
                match = findMatchContent(lvlx.get(0), input );
                if( !match.isEmpty()){
                    set.addChild(match,"").down();
                   if(raw.getChildren(match).size()>=1){
                        raw.getChildren(match).forEach(
                                child -> {
                                    for( var pair : XMLfab.getAttributes(child) ){
                                        boolean found =false;
                                        for( int a=0;a<attr.size();a++ ){
                                            if( attr.get(a)[0].equalsIgnoreCase(pair[0])){
                                                if( !attr.get(a)[1].equalsIgnoreCase(pair[1]))
                                                    attr.get(a)[1]+=","+pair[1];
                                                found=true;
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
                        raw.selectChildAsParent(match);
                        return "dunno how i got here";
                    }
                    lvl=2;
                    String ori = set.getName();
                    return formatAttrQuestion(ori,attr.get(0));
                }else{
                    lvl=0;
                    return getStartMessage(false);
                }
            case 2: // Fill in the child node (stream,filter) attributes
                // If there are options given, one must be chosen
                if( input.isEmpty() ){
                    if( attr.isEmpty()){ // finish the node
                        // Set the content of the node
                        input = lvlx.get(steps).get(0)[1];
                        // Go to next child
                    }else {
                        if (attr.get(0)[1].equalsIgnoreCase("!") || attr.get(0)[1].contains(","))
                            return TelnetCodes.TEXT_RED + "Required field, try again..." + TelnetCodes.TEXT_YELLOW;
                        input = attr.get(0)[1];
                    }
                }
                if( !input.isEmpty()){
                    if( attr.isEmpty()){
                        String tag =  lvlx.get(steps).get(0)[0];
                        set.alterChild(tag,input);
                        lvlx.get(steps).remove(0);// Finished the node, go to next
                    }else {
                        if (attr.get(0)[1].matches("!\\|?") || !attr.get(0)[1].contains(",")) {
                            set.attr(attr.get(0)[0], input);
                            attr.remove(0);
                        } else if (attr.get(0)[1].contains(input)) {
                            for (String x : attr.get(0)[1].split(",")) {
                                if (x.matches(".*" + input + ".*")) {
                                    input = x;
                                    break;
                                }
                            }
                            boolean ok = raw.selectChildAsParent(set.getName(), attr.get(0)[0], ".*" + input + ".*").isPresent();
                            set.attr(attr.get(0)[0], input);
                            attr.remove(0);
                        } else {
                            return TelnetCodes.TEXT_RED + "Invalid input, try again..." + TelnetCodes.TEXT_YELLOW;
                        }
                    }
                   // Check if this is the last one
                   if( attr.isEmpty()) {
                       String nam = set.getName();
                       String rn = raw.getName();
                       if( nam.equalsIgnoreCase(rn)){ // pointing to the same
                           raw.up(); //go back up
                       }else {
                           if (raw.getChildren(set.getName()).size() == 1) {
                               raw.selectChildAsParent(set.getName());
                           } else if (raw.getChildren(set.getName()).size() == 0) {
                               // No children!
                               String name = raw.getName();
                               String con = raw.getContent();
                               Logger.info("Got cotent:" + con);
                           }
                           steps++;
                           lvlx.put(steps,addChildrenWithContent());
                       }
                       // At this point raw is pointing to the parent
                       if( lvlx.get(steps).isEmpty() ){
                           steps--;
                           if( lvlx.get(steps).get(0)[1].isEmpty() ) { // empty node, go to next
                               lvlx.get(steps).remove(0);
                           }
                       }
                       if(!lvlx.get(steps).isEmpty()) {
                           // First check attributes!
                           attr=fillAttributes(lvlx.get(steps).get(0)[0]);
                           if( attr.isEmpty() ){
                               // Ask about text content
                               raw.up().selectChildAsParent(lvlx.get(steps).get(0)[0]);
                               return formatNodeQuestion(lvlx.get(steps).get(0));
                           }else{
                               // Ask about first attribute
                               String cf = getCf(lvlx.get(steps).get(0)[0]);
                               if( cf.contains("opt")){
                                   lvl=3;
                                   return TelnetCodes.TEXT_ORANGE+
                                           "Want to make a "+lvlx.get(steps).get(0)[0]+" node? y/n"
                                           +TelnetCodes.TEXT_YELLOW;
                               }
                               set.down().addChild(lvlx.get(steps).get(0)[0],"");
                               return formatAttrQuestion(set.getName(),attr.get(0));
                           }
                       }else {
                           return formatNodeQuestion(lvlx.get(steps).get(0));
                       }
                   }
                   set.build();
                   return formatAttrQuestion(set.getName(),attr.get(0));
               }
               break;
            case 3: // Handle optional stuff?
                switch( input ){
                    case "y": // Execute the node
                        lvl=2;
                        set.down().addChild(lvlx.get(steps).get(0)[0],"");
                        return formatAttrQuestion(set.getName(),attr.get(0));
                    case "n": // Skip the node
                        lvl=2;
                        lvlx.get(steps).remove(0); // remove the optional node
                        return nextNode();
                    default:
                        return "Invalid choice, just y or n...";
                }
        }
        return "dunno";
    }
    private String nextNode(){
        if(!lvlx.get(steps).isEmpty()) {
            // First check attributes!
            attr=fillAttributes(lvlx.get(steps).get(0)[0]);
            if( attr.isEmpty() ){
                // Ask about text content
                return formatNodeQuestion(lvlx.get(steps).get(0));
            }else{
                // Ask about first attribute
                String cf = getCf(lvlx.get(steps).get(0)[0]);
                if( cf.contains("opt")){
                    lvl=3;
                    return "Want to make a "+lvlx.get(steps).get(0)[0]+" node? y/n";
                }
                set.down().addChild(lvlx.get(steps).get(0)[0],"");
                return formatAttrQuestion(set.getName(),attr.get(0));
            }
        }else {
            steps--;
            if( steps==0 ){ // Finished the node
                lvl=1; // Go back to node selection
                set.build(); // Build to file so far
                set.up(); // Step back up the settings.xml
                raw.up(); // Step back up the config

                return TelnetCodes.TEXT_ORANGE+"Returned to "+set.getName()+", options: "
                        + formatNodeOptions(lvlx.get(0))+TelnetCodes.TEXT_YELLOW;
            }
            return formatNodeQuestion(lvlx.get(steps).get(0));
        }
    }
    private String doLevel3(String input){

        var lx = lvlx.get(steps);

        if( input.isEmpty() && !lx.get(0)[1].isEmpty()) { // So using default?
            input = lx.get(0)[1];
        }
        if( !input.isEmpty() || lx.get(0)[1].isEmpty()){
            String reg = getRegex(lx.get(0)[0]);
            if( !reg.isEmpty() ){
                if( !input.matches(reg))
                    return TelnetCodes.TEXT_RED+"No valid input given, try again... (regex: "+reg+")"+TelnetCodes.TEXT_YELLOW;
            }
            // Don't add node if the default is used
            if( !input.isEmpty() && !input.equalsIgnoreCase(lx.get(0)[1]))
                set.alterChild(lx.get(0)[0],input);

            lx.remove(0); // Finished with the childnode, move to the next one
            attr.clear(); // New node, new attributes
            if( lx.isEmpty() ){ //No more childnodes to check
                lvl=1; // Go back to node selection
                set.build(); // Build to file so far
                set.up(); // Step back up the settings.xml
                raw.up(); // Step back up the config
                lvlx.put(steps,addChildrenWithContent()); // Find the options
                return TelnetCodes.TEXT_ORANGE+"Done with the node, another?\r\nOptions: "+ formatNodeOptions(lx)+TelnetCodes.TEXT_YELLOW;
            }

            raw.getChildren(lx.get(0)[0]).forEach(
                    child -> {
                        for( var pair : XMLfab.getAttributes(child) ){
                            boolean found =false;
                            for( int a=0;a<attr.size();a++ ){
                                if( attr.get(a)[0].equalsIgnoreCase(pair[0])){
                                    if( !attr.get(a)[1].equalsIgnoreCase(pair[1]))
                                        attr.get(a)[1]+=","+pair[1];
                                    found=true;
                                    break;
                                }
                            }
                            if (!found && !pair[0].equalsIgnoreCase("hint")
                                    && !pair[0].equalsIgnoreCase("regex")) {
                                attr.add(pair);
                            }
                        }
                    }
            );

            if( attr.isEmpty() ){
                return formatNodeQuestion(lx.get(0));
            }else {
                lvl = 4;
                return formatAttrQuestion("",attr.get(0));
            }
        }else{
            lvl=2;
            set.up();
            raw.up();
            return "Back up a level";
        }
    }
    private String findMatch( ArrayList<String> list,String input){
        if( input.isEmpty()||list.contains(input))
            return input;
        return list.stream().filter( l -> l.startsWith(input)).findFirst().orElse("bad");
    }
    private ArrayList<String[]> fillAttributes( String from ){
        ArrayList<String[]> at=new ArrayList<>();
        raw.getChildren(from).forEach(
                child -> {
                    for( var pair : XMLfab.getAttributes(child) ){
                        boolean found =false;
                        for( int a=0;a<at.size();a++ ){
                            if( at.get(a)[0].equalsIgnoreCase(pair[0])){
                                if( !at.get(a)[1].equalsIgnoreCase(pair[1]))
                                    at.get(a)[1]+=","+pair[1];
                                found=true;
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
    private String formatContent( String from ){
        switch( from ){
            case "!": return TelnetCodes.TEXT_RED+" REQUIRED";
            case "?": return "";
            default:
                return "( Default: "+from+" )";
        }
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
            var at = set.getAttribute(m.substring(1,m.length()-1));
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
                + formatContent(lvlx.get(steps).get(0)[1])+TelnetCodes.TEXT_GREEN
                + getHint(lvlx.get(steps).get(0)[0])
                + TelnetCodes.TEXT_YELLOW;
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
        var child = raw.getChild(from);
        if( child.isEmpty())
            return "";
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
    public static Optional<Path> getPathToResource( Class origin, String res ){
        ClassLoader classLoader = origin.getClassLoader();
        URL resource = classLoader.getResource(res);
        if (resource == null) {
            throw new IllegalArgumentException("file not found! " + res);
        } else {
            try {
                return Optional.ofNullable(Path.of(resource.toURI()));
            } catch (URISyntaxException e) {
                Logger.error(e);
            }
        }
        return Optional.empty();
    }
}
