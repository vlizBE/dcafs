package io.forward;

import util.data.DataProviding;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import das.Commandable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class ForwardPool implements Commandable {
    
    HashMap<String, FilterForward> filters = new HashMap<>();
    HashMap<String, EditorForward> editors = new HashMap<>();
    HashMap<String, MathForward> maths = new HashMap<>();

    HashMap<String, ForwardPath> paths = new HashMap<>();

    BlockingQueue<Datagram> dQueue;
    Path settingsPath;
    DataProviding dataProviding;
    EventLoopGroup nettyGroup;

    public ForwardPool(BlockingQueue<Datagram> dQueue, Path settingsPath, DataProviding dataProviding,EventLoopGroup group){
        this.dQueue=dQueue;
        this.settingsPath=settingsPath;
        this.dataProviding=dataProviding;
        nettyGroup=group;
        readSettingsFromXML();
    }
    /* **************************************** G E N E R A L ************************************************** */
    /**
     * Read the forwards stored in the settings.xml
     */
    public void readSettingsFromXML() {

        var xml = XMLtools.readXML(settingsPath);
        Element filtersEle = XMLtools.getFirstElementByTag(xml, "filters");
        if (filtersEle != null)
            readFiltersFromXML(XMLtools.getChildElements(filtersEle, "filter"));

        Element mathsEle = XMLtools.getFirstElementByTag(xml, "maths");
        if (mathsEle != null)
            readMathsFromXML(XMLtools.getChildElements(mathsEle, "math"));

        Element editorsEle = XMLtools.getFirstElementByTag(xml, "editors");
        if (editorsEle != null)
            readEditorsFromXML(XMLtools.getChildElements(editorsEle, "editor"));

        /* Figure out the datapath? */
        XMLfab.getRootChildren(settingsPath,"dcafs","datapaths","path").forEach(
                pathEle -> {
                    ForwardPath path = new ForwardPath(dataProviding,dQueue,nettyGroup);
                    path.readFromXML(pathEle);
                    paths.put(path.getID(),path);
                }
        );
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        boolean ok=false;

        // Regular ones
        switch(request[0]){
            // Path
            case "path": return replyToPathCmd(request[1],wr,html);
            // Filter
            case "ff": return replyToFilterCmd(request[1],wr,html);
            case "filter":
                if( request[1].startsWith("!")){
                    ok = getFilterForward(request[1].substring(1)).map(ff -> {ff.addReverseTarget(wr);return true;} ).orElse(false);
                }else{
                    ok = getFilterForward(request[1]).map(ff -> {ff.addTarget(wr);return true;} ).orElse(false);
                }
                break;

            // Editor
            case "ef": return replyToEditorCmd(request[1],wr,html);
            case "editor": ok = getEditorForward(request[1]).map(tf -> { tf.addTarget(wr); return true;} ).orElse(false); break;

            // Math
            case "mf": return replyToMathCmd(request[1],wr,html);
            case "math": ok = getMathForward(request[1]).map(mf -> { mf.addTarget(wr); return true;} ).orElse(false); break;
            default:
                return "unknown command: "+request[0]+":"+request[1];
        }
        if( ok )
            return "Request for "+request[0]+":"+request[1]+" ok.";
        return "Request for "+request[0]+":"+request[1]+" failed.";
    }

    @Override
    public boolean removeWritable( Writable wr) {
        filters.values().forEach( ff->ff.removeTarget(wr));
        editors.values().forEach( ef->ef.removeTarget(wr));
        maths.values().forEach( mf->mf.removeTarget(wr));
        paths.values().forEach( p -> p.removeTarget(wr));
        return false;
    }

    /*    ------------------------ Math ---------------------------------    */
    public MathForward addMath(String id, String source ){
        var mf = new MathForward( id, source, dQueue,dataProviding);
        maths.put( id, mf);
        return mf;
    }
    public Optional<MathForward> getMathForward(String id ){
        return Optional.ofNullable( maths.get(id));
    }
    public void readMathsFromXML( List<Element> mathsEles ){
        for( Element ele : mathsEles ){
            MathForward mf = new MathForward( ele,dQueue,dataProviding );
            String id = mf.getID();
            maths.put(id.replace("math:", ""), mf);
        }
    }
    private String replyToMathCmd( String cmd, Writable wr, boolean html ){
        if( cmd.isEmpty() )
            cmd = "list";

        String[] cmds = cmd.split(",");

        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        boolean complex=false;
        switch( cmds[0] ) {
            case "?":
                join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
                        .add("  MathForwards can be used to alter data received from any source using mathematics.")
                        .add("  eg. receive the raw data from a sensor and convert it to engineering values")
                        .add("  Furthermore, the altered data is considered a source and can thus be used in further steps.")
                        .add("  eg. pass it to a generic that stores it in a database").add("");
                join.add(TelnetCodes.TEXT_GREEN+"Create a MathForward"+TelnetCodes.TEXT_YELLOW)
                        .add("  mf:addblank,id,source -> Add a blank mathf to the xml with the given id and optional source")
                        .add("  mf:addsource,id,source -> Add the source to the given mathf")
                        .add("  mf:addop,id,op -> Add the operation fe. i1=i2+50 to the mathf with the id")
                        .add("  mf:alter,id,param:value -> Change a setting, currently delim(eter),label");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
                        .add("  mf:debug,on/off -> Turn debug on/off")
                        .add("  mf:list -> Get a listing of all the present mathforwards")
                        .add("  mf:scratchpad,id,value -> Add the given value to the scratchpad of mathf id (or * for all)")
                        .add("  mf:reload,id -> reloads the given id")
                        .add("  mf:test,id,variables -> Test the given id with the variables (with the correct delimiter)")
                        .add("  math:id -> Receive the data in the telnet window, also the source reference");
                return join.toString();
            case "debug":
                if (cmds[1].equalsIgnoreCase("on")) {
                    maths.values().forEach(MathForward::enableDebug);
                    return "Debug enabled";
                } else {
                    maths.values().forEach(MathForward::disableDebug);
                    return "Debug disabled";
                }
            case "addcomplex":
                complex=true;
                if( cmds.length<4 && !cmds[cmds.length-1].startsWith("i"))
                    return "Incorrect amount of arguments, expected mf:addcomplex,id,source,op";
            case "addblank":
                if( cmds.length<3)
                    return "Incorrect amount of arguments, expected mf:addblank,id,source";
                if( getMathForward(cmds[1]).isPresent() )
                    return "Already math with that id";
                cmds[1]=cmds[1].toLowerCase();
                StringJoiner src = new StringJoiner(",");
                int limit = cmds.length-(complex?1:0);
                for( int a=2;a<limit;a++){
                    src.add(cmds[a]);
                }
                var mm = addMath(cmds[1],src.toString());
                if( cmds.length==4){
                    mm.addOperation(-1, MathForward.OP_TYPE.COMPLEX,"",cmds[cmds.length-1]);
                }
                mm.writeToXML(XMLfab.withRoot(settingsPath, "dcafs"));
                return "Math with id "+cmds[1]+ " created.";
            case "alter":
                var mf = maths.get(cmds[1]);
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf:alter,id,param:value";
                if( mf == null )
                    return "No such mathforward: "+cmds[1];

                if( !cmds[2].contains(":"))
                    return "No proper param:value pair";

                String param = cmds[2].substring(0,cmds[2].indexOf(":"));

                String value = cmd.substring(cmd.indexOf(param+":")+param.length()+1);

                XMLfab fab = XMLfab.withRoot(settingsPath,"dcafs","maths"); // get a fab pointing to the maths node

                if( fab.selectParent("math","id",cmds[1]).isEmpty() )
                    return "No such math node '"+cmds[1]+"'";

                switch( param ){
                    case "delim": case "delimiter": case "split":
                        mf.setDelimiter(value);
                        fab.attr("delimiter",value);
                        return fab.build()!=null?"Delimiter changed":"Delimiter change failed";
                    case "label":
                        mf.setLabel(value);
                        fab.attr("label",value);
                        return fab.build()!=null?"Label changed":"Label change failed";
                    default:return "No valid alter target: "+param;
                }

            case "reload":
                if( cmds.length==2) {
                    if(getMathForward(cmds[1]).isEmpty())
                        return "No such math";

                    getMathForward(cmds[1]).ifPresent(m ->
                            m.readFromXML(
                                    XMLfab.withRoot(settingsPath, "dcafs", "maths").getChild("math", "id", cmds[1]).get()
                            ));
                    return "Math reloaded: "+cmds[1];
                }else{ //reload all
                    var mEle = XMLfab.withRoot(settingsPath, "dcafs", "maths").getChildren("math");
                    ArrayList<String> altered=new ArrayList<>();
                    mEle.forEach(
                            ee ->{
                                var id = ee.getAttribute("id");
                                var mOp = getMathForward(id);
                                if( mOp.isPresent()){ // If already exists
                                    mOp.get().readFromXML(ee);
                                    altered.add(id);
                                }else{ //if doesn't exist yet
                                    maths.put(id,MathForward.readXML(ee,dQueue,dataProviding));
                                }
                            }
                    );
                    // Remove the ones that no longer exist
                    if( mEle.size() != maths.size() ){ // Meaning filters has more
                        // First mark them as invalid, so references also get deleted
                        maths.entrySet().stream().filter(e -> !altered.contains(e.getKey()) ).forEach( e->e.getValue().setInvalid());
                        //then remove then safely
                        maths.entrySet().removeIf( ee -> !ee.getValue().isConnectionValid());
                    }
                }
            case "list":
                join.setEmptyValue("No maths yet");
                maths.values().forEach(m -> join.add(m.toString()).add(""));
                return join.toString();
            case "scratchpad":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf:scratchpad,id,value";
                if( cmds[1].equalsIgnoreCase("*")) {
                    maths.forEach((id, m) -> m.setScratchpad(NumberUtils.createDouble(cmds[2])));
                }else{
                    getMathForward(cmds[1]).ifPresent(m -> m.setScratchpad(NumberUtils.createDouble(cmds[2])));
                }
                return "Scratchpad value ("+cmds[2]+") given to "+cmds[1];
            case "addsource": case "addsrc":
                String source = cmds[2].startsWith("i2c:")?cmds[2]+","+cmds[3]:cmds[2];
                if( getMathForward(cmds[1]).map(m -> m.addSource(source) ).orElse(false) )
                    return "Source added";
                return "Failed to add source, no such math.";
            case "addop":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf:addop,id,inputIndex(fe. i1)=formula";

                cmds[1]=cmds[1].toLowerCase();

                Logger.info("Math "+cmds[1]+" exists?"+ getMathForward(cmds[1]).isPresent());

                String[] split = cmds[2].split("=");
                if( split.length!=2){
                    return "Op not in correct format, needs to be ix=formula (x is the index)";
                }
                if( getMathForward(cmds[1]).isEmpty())
                    return "No such math yet ("+cmds[1]+")";

                int index = Tools.parseInt(split[0].substring(1),-1);
                if( index == -1 ){
                    return "No valid index given: "+split[0];
                }

                if( getMathForward(cmds[1]).map(f -> f.addComplex("",cmds[2]).isPresent() ).orElse(false) ){
                    getMathForward(cmds[1]).get().writeToXML(XMLfab.withRoot(settingsPath, "dcafs"));
                    return "Operation added and written to xml";
                }
                return "Failed to add operation";
            case "test":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf:test,id,variables";
                if( getMathForward(cmds[1].toLowerCase()).isEmpty() )
                    return "No such math yet ("+cmds[1]+")";
                getMathForward(cmds[1].toLowerCase()).ifPresent(MathForward::enableDebug);
                String[] var = ArrayUtils.subarray(cmds,2,cmds.length);
                return getMathForward(cmds[1].toLowerCase()).map(m -> m.solveFor(String.join(",",var))).orElse("Failed");
            default: return "unknown command "+cmds[0];
        }
    }
    /*    ------------------------ Editor ---------------------------------    */
    public EditorForward addEditor(String id, String source ){
        var tf = new EditorForward( id, source, dQueue,dataProviding);
        editors.put( id, tf);
        return tf;
    }
    public Optional<EditorForward> getEditorForward(String id ){
        return Optional.ofNullable( editors.get(id));
    }
    public void readEditorsFromXML( List<Element> editorsEle ){
        Logger.info("Reading TextForwards from xml");
        for( Element ele : editorsEle ){
            var tf = new EditorForward( ele,dQueue,dataProviding );
            editors.put(tf.getID().replace("editor:", ""), tf);
        }
    }
    public String replyToEditorCmd( String cmd, Writable wr, boolean html ){
        if( cmd.isEmpty() )
            cmd = "list";

        String[] cmds = cmd.split(",");

        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        EditorForward ef;

        switch( cmds[0] ) {
            case "?":
                join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
                        .add("  If a next step in the processing needs something altered to the format or layout of the data")
                        .add("  an editorforward can do this.");
                join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
                        .add("  - Forward doesn't do anything if it doesn't have a target (label counts as target)")
                        .add("  - ...");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Create a EditorForward"+TelnetCodes.TEXT_YELLOW);
                join.add( "  ef:addblank,id<,source> -> Add a blank filter with an optional source, gets stored in xml.")
                    .add( "  ef:reload<,id> -> Reload the editor with the given id or all if no id was given.");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Other commands"+TelnetCodes.TEXT_YELLOW)
                        .add("  ef:list -> Get a list of all editors")
                        .add("  ef:edits -> Get a list of all possible edit operations")
                        .add("  ef:addedit,id,type:value -> Add an edit of the given value, use type:? for format");
                return join.toString();
            case "addblank":
                if( cmds.length<2)
                    return "Not enough arguments, needs to be ef:addblank,id<,src,>";
                if( getEditorForward(cmds[1]).isPresent() )
                    return "Already editor with that id";

                StringJoiner src = new StringJoiner(",");
                for( int a=2;a<cmds.length;a++){
                    src.add(cmds[a]);
                }

                ef = addEditor(cmds[1].toLowerCase(),src.toString());
                if( ef == null)
                    return "Something wrong with the command, filter not created";
                ef.writeToXML( XMLfab.withRoot(settingsPath, "dcafs") );
                return "Blank editor with id "+cmds[1]+ " created"+(cmds.length>2?", with source "+cmds[2]:"")+".";
            case "reload":
                if( cmds.length == 2) {
                    Optional<Element> x = XMLfab.withRoot(settingsPath, "dcafs", "editors").getChild("editor", "id", cmds[1]);
                    if (x.isPresent()) {
                        getEditorForward(cmds[1]).ifPresent(e -> e.readFromXML(x.get()));
                    } else {
                        return "No such editor, " + cmds[1];
                    }
                }else{ //reload all
                    var eEle = XMLfab.withRoot(settingsPath, "dcafs", "editors").getChildren("editor");
                    ArrayList<String> altered=new ArrayList<>();
                    eEle.forEach(
                            ee ->{
                                var id = ee.getAttribute("id");
                                var fOp = getEditorForward(id);
                                if( fOp.isPresent()){ // If already exists
                                    fOp.get().readFromXML(ee);
                                    altered.add(id);
                                }else{ //if doesn't exist yet
                                    editors.put(id,EditorForward.readXML(ee,dQueue,dataProviding));
                                }
                            }
                    );
                    // Remove the ones that no longer exist
                    if( eEle.size() != editors.size() ){ // Meaning filters has more
                        // First mark them as invalid, so references also get deleted
                        editors.entrySet().stream().filter(e -> !altered.contains(e.getKey()) ).forEach( e->e.getValue().setInvalid());
                        //then remove them safely
                        editors.entrySet().removeIf( ee -> !ee.getValue().isConnectionValid());
                    }
                }
                return "Editor(s) reloaded.";
            case "list":
                join.setEmptyValue("No editors yet");
                editors.values().forEach( f -> join.add(f.toString()).add("") );
                return join.toString();
            case "edits": return EditorForward.getHelp(html?"<br>":"\r\n");
            case "addedit":
                if( cmds.length < 3) // might be larger if the value contains  a ,
                    return "Bad amount of arguments, should be ef:addedit,id,type:value";
                var eOpt = getEditorForward(cmds[1]);
                if( eOpt.isEmpty())
                    return "No such editor";

                var e = eOpt.get();
                int x = cmd.indexOf(":");
                if( x==-1)
                    return "Incorrect format, needs to be ef:addedit,id,type:value";
                // The value might contain , so make sure to split properly
                String type = cmds[2].substring(0,cmds[2].indexOf(":"));
                String value = cmd.substring(x+1);
                String deli = e.delimiter;

                var p = value.split(",");
                var fab = XMLfab.withRoot(settingsPath,"dcafs","editors");
                fab.selectOrCreateParent("editor","id",cmds[1]);

                switch(type){
                    /* Splitting */
                    case "rexsplit":
                        if( value.equals("?"))
                            return "ef:addedit,rexsplit:regextosplitwith (delimiter will be , )";
                        e.addRexsplit(deli,value);
                        addEditNode(fab,type,value,true);
                        return "Rexsplit to "+value+" added with delimiter "+deli;
                    case "resplit":
                        if( value.equals("?"))
                            return "ef:addedit,resplit:tosplitwith (delimiter will be , )";
                        e.addResplit(deli,value,"",true);
                        addEditNode(fab,type,value,false);
                        fab.attr("leftover","append").build();
                        return "Resplit to "+value+" added with default delimiter + and leftover";
                    case "charsplit":
                        if( value.equals("?"))
                            return "ef:addedit,rexplit:regextosplitwith";
                        e.addCharSplit( ",",value );
                        addEditNode(fab,type,value,true);
                        return "Charsplit added with default delimiter";
                    /* Timestamp stuff */
                    case "redate":
                        if( value.equals("?"))
                            return "ef:addedit,redate:index,from,to";
                        e.addRedate(p[1],p[2],NumberUtils.toInt(p[0]),deli);
                        addEditNode(fab,"redate",p[2],false);
                        fab.attr("index",p[0]).build();
                        return "After splitting on "+deli+" the date on index "+p[0]+" is reformatted from "+p[1]+" to "+p[2];
                    case "retime":
                        if( value.equals("?"))
                            return "ef:addedit,retime:index,from,to";
                        e.addRetime(p[0],p[2],NumberUtils.toInt(p[1]),",");
                        addEditNode(fab,"retime",p[2],false);
                        fab.attr("index",p[1]).build();
                        return "After splitting on , the time on index "+p[1]+" is reformatted from "+p[0]+" to "+p[2];
                    /* Replacing */
                    case "replace":
                        if( value.equals("?"))
                            return "ef:addedit,replace:what,with";
                        e.addReplacement(p[0],p[1]);
                        fab.addChild("edit",p[1]).attr( "type",type).attr("find",p[0]).build();
                        return "Replacing "+p[0]+" with "+p[1];
                    case "rexreplace":
                        if( value.equals("?"))
                            return "ef:addedit,rexreplace:regexwhat,with";
                        e.addRegexReplacement(p[0],p[1]);
                        fab.addChild("edit",p[1]).attr( "type",type).attr("find",p[0]).build();
                        return "Replacing "+p[0]+" with "+p[1];
                    /* Remove stuff */
                    case "remove":
                        if( value.equals("?"))
                            return "ef:addedit,remove:find";
                        e.addReplacement(value,"");
                        fab.addChild("edit",value).attr( "type",type).build();
                        return "Removing "+value+" from the data";
                    case "rexremove":
                        if( value.equals("?"))
                            return "ef:addedit,rexremove:regexfind";
                        e.addRegexReplacement(value,"");
                        fab.addChild("edit",value).attr( "type",type).build();
                        return "Removing matches of "+value+" from the data";
                    case "trim":
                        if( value.equals("?"))
                            return "ef:addedit,trim";
                        e.addTrim();
                        fab.addChild("edit").attr( "type",type).build();
                        return "Trimming spaces from data";
                    case "cutstart":
                        if( value.equals("?"))
                            return "ef:addedit,custart:charcount";
                        e.addCutStart(NumberUtils.toInt(value));
                        fab.addChild("edit",value).attr( "type",type).build();
                        return "Cutting "+value+" char(s) from the start";
                    case "cutend":
                        if( value.equals("?"))
                            return "ef:addedit,custend:charcount";
                        e.addCutEnd(NumberUtils.toInt(value));
                        return "Cutting "+value+" char(s) from the end";
                    /* Adding stuff */
                    case "prepend": case "prefix":
                        if( value.equals("?"))
                            return "ef:addedit,prepend:toprepend or ef:addedit,prefix:toprepend";
                        e.addPrepend(value);
                        fab.addChild("edit",value).attr( "type",type).build();
                        return "Prepending "+value+" to the data";
                    case "insert":
                        if( value.equals("?"))
                            return "ef:addedit,insert:position,toinsert";
                        e.addInsert(NumberUtils.toInt(p[0]),p[1]);
                        fab.addChild("edit",p[1]).attr("position",p[0]).attr( "type",type).build();
                        return "Inserting "+value+" at char "+p[0]+" in the data";
                    case "append": case "suffix":
                        if( value.equals("?"))
                            return "ef:addedit,append:toappend or ef:addedit,suffix:toappend";
                        e.addAppend(value);
                        return "Appending "+value+" to the data";
                }
                return "Edit added";
        }
        return "Unknown command: "+cmds[0];
    }
    private void addEditNode( XMLfab fab, String type, String value, boolean build){
        fab.addChild("edit",value).attr( "type",type)
                .attr("delimiter",",");
        if( build )
            fab.build();
    }
    /*    ------------------------ Filter ---------------------------------    */
    public FilterForward addFilter(String id, String source, String rule ){
        var ff = new FilterForward( id, source, dQueue);
        if( ff.addRule(rule) < 0 )
            return null;
        filters.put( id, ff);
        return ff;
    }
    public Optional<FilterForward> getFilterForward(String id ){
        return Optional.ofNullable( filters.get(id));
    }
    public void readFiltersFromXML( List<Element> filterEles ){
        Logger.info("Reading filterforwards from xml");
        filters.clear();
        for( Element ele : filterEles ){
            FilterForward ff = new FilterForward( ele,dQueue );
            filters.put(ff.getID().replace("filter:", ""), ff);
        }
    }
    public String replyToFilterCmd( String cmd, Writable wr, boolean html ){

        if( cmd.isEmpty() )
            cmd = "list";

        String[] cmds = cmd.split(",");

        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        FilterForward ff;
        switch( cmds[0] ){
            case "?":
                join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
                        .add("  If a next step in the processing doesn't want to receive some of the data, a filterforward can")
                        .add("  be used to remove this data from the source.");
                join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
                        .add("  - Filter works based on exclusion, meaning no rules = all data goes through")
                        .add("  - Filter doesn't do anything if it doesn't have a target (label counts as target)")
                        .add("  - ...");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Create a FilterForward"+TelnetCodes.TEXT_YELLOW);
                join.add( "  ff:addblank,id<,source> -> Add a blank filter with an optional source, is stored in xml.");
                join.add( "  ff:rules -> Get a list of all the possible rules with a short explanation");
                join.add( "  ff:addshort,id,src,rule:value -> Adds a filter with the given source and rule (type:value)");
                join.add( "  ff:addtemp,id<,source> -> Add a temp filter with an optional source with the issuer as target. Not stored in xml.");
                join.add( "  ff:addsource,id,source -> Add a source to the given filter");
                join.add( "  ff:addrule,id,rule:value -> Add a rule to the given filter");

                join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
                join.add( "  ff:alter,id,param:value -> Alter a parameter, for now only altering the label is possible");
                join.add( "  ff:reload,id -> Reload the filter with the given id");
                join.add( "  ff:reload -> Clear the list and reload all the filters");
                join.add( "  ff:remove,id -> Remove the filter with the given id");
                join.add( "  ff:test,id,data -> Test if the data would pass the filter");
                join.add( "  ff:list or ff -> Get a list of all the currently existing filters.");
                join.add( "  ff:delrule,id,index -> Remove a rule from the filter based on the index given in ff:list");
                join.add( "  ff:swaprawsrc,id,ori,new -> Swap the raw ori source of the given filter with the new raw one, mimick redundancy");
                join.add( "  filter:id -> Receive the data in the telnet window, also the source reference");

                return join.toString();
            case "debug":
                if( cmds[1].equalsIgnoreCase("on")){
                    filters.values().forEach( FilterForward::enableDebug );
                    return "Debug enabled";
                }else{
                    filters.values().forEach( FilterForward::disableDebug );
                    return "Debug disabled";
                }
            case "list":
                join.setEmptyValue("No filters yet");
                filters.values().forEach( f -> join.add(f.toString()).add("") );
                return join.toString();
            case "rules":
                return FilterForward.getHelp(html?"<br>":"\r\n");
            case "remove":
                if( cmds.length < 2 )
                    return "Not enough arguments: ff:remove,id";
                if( filters.remove(cmds[1]) != null )
                    return "Filter removed";
                return "No such filter";
            case "addrule":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be ff:addrule,id,type:value";
                String step = cmds.length==4?cmds[2]+","+cmds[3]:cmds[2]; // step might contain a ,
                var fOpt = getFilterForward(cmds[1].toLowerCase());
                Logger.info("Filter exists?"+fOpt.isPresent());
                switch( fOpt.map( f -> f.addRule(step) ).orElse(0) ){
                    case 1:
                        fOpt.get().writeToXML(XMLfab.withRoot(settingsPath, "dcafs"));
                        return "Rule added to "+cmds[1];
                    case 0:  return "Failed to add rule, no such filter called "+cmds[1];
                    case -1: return "Unknown type in "+step+", try ff:types for a list";
                    case -2: return "Bad rule syntax, should be type:value";
                    default: return "Wrong response from getFilter";
                }
            case "delrule":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be ff:delrule,id,index";
                int index = Tools.parseInt( cmds[2], -1);
                if( getFilterForward(cmds[1]).map(f -> f.removeRule(index) ).orElse(false) )
                    return "Rule removed";
                return "Failed to remove rule, no such filter or rule.";
            case "addsource": case "addsrc":
                String source = cmds[2].startsWith("i2c:")?cmds[2]+","+cmds[3]:cmds[2];
                if( getFilterForward(cmds[1]).map(f -> f.addSource(source) ).orElse(false) )
                    return "Source added";
                return "Failed to add source, no such filter.";
            case "addblank":
                if( cmds.length<2)
                    return "Not enough arguments, needs to be ff:addblank,id<,src,>";
                if( getFilterForward(cmds[1]).isPresent() )
                    return "Already filter with that id";

                StringJoiner src = new StringJoiner(",");
                for( int a=2;a<cmds.length;a++){
                    src.add(cmds[a]);
                }

                ff = addFilter(cmds[1].toLowerCase(),src.toString(),"");
                if( ff == null)
                    return "Something wrong with the command, filter not created";
                ff.writeToXML( XMLfab.withRoot(settingsPath, "dcafs") );
                return "Blank filter with id "+cmds[1]+ " created"+(cmds.length>2?", with source "+cmds[2]:"")+".";
            case "addshort":
                if( cmds.length<4)
                    return "Not enough arguments, needs to be ff:addshort,id,src,type:value";
                if( getFilterForward(cmds[1]).isPresent() )
                    return "Already filter with that id";

                ff = addFilter(cmds[1].toLowerCase(),cmds[2],cmds[3]);
                if( ff == null )
                    return "Something wrong with the command, filter not created";

                ff.writeToXML( XMLfab.withRoot(settingsPath, "dcafs") );
                return "Filter with id "+cmds[1]+ " created, with source "+cmds[2]+" and rule "+cmds[3];
            case "addtemp":
                if( getFilterForward(cmds[1]).isPresent() ){
                    return "Already filter with that id";
                }
                filters.put(cmds[1], new FilterForward(cmds[1],cmds.length>2?cmds[2]:"",dQueue));
                getFilterForward(cmds[1]).ifPresent(f -> f.addTarget(wr) );
                getFilterForward(cmds[1]).ifPresent(f -> f.addTarget(wr) );
                return "Temp filter with id "+cmds[1]+ " created"+(cmds.length>2?", with source"+cmds[2]:"")+".";
            case "alter":
                ff = filters.get(cmds[1]);
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be ff:alter,id,param:value";
                if( ff == null )
                    return "No such filter: "+cmds[1];

                if( !cmds[2].contains(":"))
                    return "No proper param:value pair";

                String param = cmds[2].substring(0,cmds[2].indexOf(":"));

                String value = cmd.substring(cmd.indexOf(param+":")+param.length()+1);

                XMLfab fab = XMLfab.withRoot(settingsPath,"dcafs","filters"); // get a fab pointing to the maths node

                if( fab.selectParent("filter","id",cmds[1]).isEmpty() )
                    return "No such filter node '"+cmds[1]+"'";

                switch( param ){
                    case "label":
                        ff.setLabel(value);
                        fab.attr("label",value);
                        return fab.build()!=null?"Label changed":"Label change failed";
                    default:return "No valid alter target: "+param;
                }
            case "swaprawsrc":
                if( cmds.length<4)
                    return "Not enough arguments, needs to be ff:swaprawsrc,id,ori,new";
                var fopt = getFilterForward(cmds[1]);
                if( fopt.isEmpty() ) {
                    Logger.error("swaprawsrc - No valid filter id given");
                    return "No valid id given";
                }
                ff=fopt.get();
                dQueue.add( Datagram.system("stop").writable(ff));
                /*var oriopt = getStream(cmds[2]);
                if( oriopt.isEmpty() )
                    return "No valid ori given";
                if( getStream(cmds[3]).isEmpty() )
                    return "No valid new given";
                oriopt.get().removeTarget(ff.getID());*/

                ff.removeSource("raw:"+cmds[2]);
                ff.addSource("raw:"+cmds[3]);
                return "Swapped source of "+cmds[1]+" from "+cmds[2]+" to "+cmds[3];
            case "reload":
                if( cmds.length == 2) {
                    Optional<Element> x = XMLfab.withRoot(settingsPath, "dcafs", "filters").getChild("filter", "id", cmds[1]);
                    if (x.isPresent()) {
                        getFilterForward(cmds[1]).ifPresent(f -> f.readFromXML(x.get()));
                    } else {
                        return "No such filter, " + cmds[1];
                    }
                    return "Filter reloaded.";
                }else{ //reload all
                    var fEle = XMLfab.withRoot(settingsPath, "dcafs", "filters").getChildren("filter");
                    ArrayList<String> altered=new ArrayList<>();
                    fEle.forEach(
                            fe ->{
                                var id = fe.getAttribute("id");
                                var fOp = getFilterForward(id);
                                if( fOp.isPresent()){ // If already exists
                                    fOp.get().readFromXML(fe);
                                    altered.add(id);
                                    Logger.info("Altered filter: "+id);
                                }else{ //if doesn't exist yet
                                    filters.put(id,FilterForward.readXML(fe,dQueue));
                                    Logger.info("Added filter: "+id);
                                }
                            }
                    );
                    // Remove the ones that no longer exist
                    if( fEle.size() != filters.size() ){ // Meaning filters has more
                        // First mark them as invalid, so references also get deleted
                        filters.entrySet().stream().filter(f -> !altered.contains(f.getKey()) ).forEach( f->f.getValue().setInvalid());
                        //then remove then safely
                        filters.entrySet().removeIf( fe -> !fe.getValue().isConnectionValid());
                        Logger.info("Removed filter...");
                    }
                    return "Filters reloaded.";
                }
            case "test":
                if( cmds.length != 2)
                    return "Not enough arguments, ff:test,id,data";
                String data = cmd.substring(8);
                final String d = data.substring(data.indexOf(",")+1);
                fOpt = getFilterForward(cmds[1]);
                if( fOpt.isEmpty())
                    return "No such filter";
                if( fOpt.map( f -> f.doFilter(d)).orElse(false) ){
                    return "Data passed the filter";
                }else{
                    return "Data failed the filter";
                }
            default: return "No such command";
        }
    }
    /* ******************************************** P A T H ******************************************************** */
    public String replyToPathCmd(String cmd, Writable wr, boolean html ){
        var cmds =cmd.split(",");

        switch(cmds[0]){
            case "reload":
                var ele = XMLfab.withRoot(settingsPath,"dcafs","datapaths")
                        .getChild("path","id",cmds[1]);
                if(ele.isEmpty())
                    return "No such path "+cmds[1];
                paths.get(cmds[1]).readFromXML(ele.get());
                return "Path reloaded";
            case "addblank":
                XMLfab.withRoot(settingsPath,"dcafs","datapaths")
                        .addChild("path").attr("id",cmds[1]).attr("src","")
                        .build();
                return "Blank added";
            case "list":
                StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
                join.setEmptyValue("No paths yet");
                paths.entrySet().forEach( x -> join.add("path:"+x.getKey()+" -> "+ x.getValue().toString()));
                return join.toString();
            default:
                var p = paths.get(cmd);
                if( p==null)
                    return "No such path (yet)";

                p.addTarget(wr);
                return "Request received.";
        }
    }
}
