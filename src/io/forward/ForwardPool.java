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
import worker.Generic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class ForwardPool implements Commandable {
    
    private final HashMap<String, FilterForward> filters = new HashMap<>();
    private final HashMap<String, EditorForward> editors = new HashMap<>();
    private final HashMap<String, MathForward> maths = new HashMap<>();

    private final HashMap<String, PathForward> paths = new HashMap<>();

    private final BlockingQueue<Datagram> dQueue;
    private final Path settingsPath;
    private final DataProviding dataProviding;
    private final EventLoopGroup nettyGroup;

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

        /* Figure out the paths? */
        readPathsFromXML();
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        boolean ok;

        // Regular ones
        switch(request[0]){
            // Path
            case "paths": case "pf": return replyToPathCmd(request[1],wr,html);
            case "path":
                var p = paths.get(request[1]);
                if( p==null)
                    return "No such path (yet): "+request[1];
                p.addTarget(wr);
                return "Request received.";
            // Filter
            case "ff": case "filters": return replyToFilterCmd(request[1],wr,html);
            case "filter":
                if( request[1].startsWith("!")){
                    ok = getFilterForward(request[1].substring(1)).map(ff -> {ff.addReverseTarget(wr);return true;} ).orElse(false);
                }else{
                    ok = getFilterForward(request[1]).map(ff -> {ff.addTarget(wr);return true;} ).orElse(false);
                }
                break;

            // Editor
            case "ef": case "editors": return replyToEditorCmd(request[1],html);
            case "editor": ok = getEditorForward(request[1]).map(tf -> { tf.addTarget(wr); return true;} ).orElse(false); break;

            // Math
            case "mf": case "maths": return replyToMathCmd(request[1],html);
            case "math": ok = getMathForward(request[1]).map(mf -> { mf.addTarget(wr); return true;} ).orElse(false); break;
            case "":
                maths.forEach( (k,m) -> m.removeTarget(wr));
                editors.forEach( (k,e) -> e.removeTarget(wr));
                filters.forEach( (k,m) -> m.removeTarget(wr));
                paths.forEach( (k,m) -> m.removeTarget(wr));
                return "Cleared requests";
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
    private String replyToMathCmd( String cmd, boolean html ){
        if( cmd.isEmpty() )
            cmd = "list";

        String[] cmds = cmd.split(",");

        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        switch( cmds[0] ) {
            case "?":
                join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
                        .add("  Maths can be used to alter data received from any source using mathematics.")
                        .add("  eg. receive the raw data from a sensor and convert it to engineering values")
                        .add("  Furthermore, the altered data is considered a source and can thus be used in further steps.")
                        .add("  fe. pass it to a generic that stores it in a database")
                        .add("");
                join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
                        .add("  - Maths don't do anything if it doesn't have a target (label counts as target)")
                        .add("  - Commands can start with mf: instead of maths:")
                        .add("  - ...");
                join.add(TelnetCodes.TEXT_GREEN+"Create a MathForward"+TelnetCodes.TEXT_YELLOW)
                        .add("  maths:addmath/add,id,source<,op> -> Add a blank math to the xml with the given id, source and optional op")
                        .add("  maths:addsource,id,source -> Add the source to the given math")
                        .add("  maths:addop,id,op -> Add the operation fe. i1=i2+50 to the math with the id")
                        .add("  maths:alter,id,param:value -> Change a setting, currently delim(eter),label");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
                        .add("  maths:debug,on/off -> Turn debug on/off")
                        .add("  maths:list -> Get a listing of all the present maths")
                        .add("  maths:scratchpad,id,value -> Add the given value to the scratchpad of mathf id (or * for all)")
                        .add("  maths:reload,id -> reloads the given id")
                        .add("  maths:remove,id -> remove the given id")
                        .add("  maths:clear -> remove all maths")
                        .add("  maths:test,id,variables -> Test the given id with the variables (with the correct delimiter)")
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
            case "addblank":case "addmath": case "add":
                if( cmds.length<3)
                    return "Incorrect amount of arguments, expected mf/maths:"+cmds[0]+",id,source<,op> (op is optional)";
                if( getMathForward(cmds[1]).isPresent() )
                    return "Already math with that id";
                cmds[1]=cmds[1].toLowerCase();
                StringJoiner src = new StringJoiner(",");
                int limit = cmds.length;
                for( int a=2;a<limit;a++){
                    if( !cmds[a].contains("="))
                        src.add(cmds[a]);
                }
                if(!src.toString().contains(":"))
                    return "Invalid source format, needs to be type:id";
                var mm = addMath(cmds[1],src.toString());
                if( cmds[cmds.length-1].contains("=")){
                    mm.addStdOperation(cmds[cmds.length-1], -1,"");
                }
                mm.writeToXML(XMLfab.withRoot(settingsPath, "dcafs"));
                return "Math '"+cmds[1]+ "' added.";
            case "alter":
                var mf = maths.get(cmds[1]);
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf/maths:alter,id,param:value";
                if( mf == null )
                    return "No such mathforward: "+cmds[1];

                if( !cmds[2].contains(":"))
                    return "No proper param:value pair";

                String param = cmds[2].substring(0,cmds[2].indexOf(":"));

                String value = cmd.substring(cmd.indexOf(param+":")+param.length()+1);

                XMLfab fab = XMLfab.withRoot(settingsPath,"dcafs","maths"); // get a fab pointing to the maths node

                if( fab.selectChildAsParent("math","id",cmds[1]).isEmpty() )
                    return "No such math '"+cmds[1]+"'";

                switch( param ){
                    case "delim": case "delimiter": case "split":
                        mf.setDelimiter(value);
                        fab.attr("delimiter",value);
                        return fab.build()?"Delimiter changed":"Delimiter change failed";
                    case "label":
                        mf.setLabel(value);
                        fab.attr("label",value);
                        return fab.build()?"Label changed":"Label change failed";
                    default:return "No valid alter target: "+param;
                }
            case "remove":
                if( cmds.length!=2)
                    return "Missing id";
                if( XMLfab.withRoot(settingsPath, "dcafs", "maths").removeChild("math","id",cmds[1]) ){
                    maths.remove(cmds[1]);
                    return "Math removed";
                }
                return "No such math "+cmds[1];
            case "clear":
                XMLfab.withRoot(settingsPath, "dcafs", "maths").clearChildren().build();
                maths.clear();
                return "Maths cleared";
            case "reload":
                if( cmds.length==2) {
                    if(getMathForward(cmds[1]).isEmpty())
                        return "No such math";

                    getMathForward(cmds[1]).ifPresent(m ->
                                    XMLfab.withRoot(settingsPath, "dcafs", "maths")
                                            .getChild("math", "id", cmds[1])
                                            .ifPresent( m::readFromXML )
                                    );
                    return "Math reloaded: "+cmds[1];
                }else{ //reload all
                    if( !XMLfab.hasRoot(settingsPath, "dcafs", "maths")){
                        maths.values().forEach( m -> m.valid=false);
                        maths.clear();
                    }
                    var mEle = XMLfab.withRoot(settingsPath, "dcafs", "maths").getChildren("math");
                    ArrayList<String> altered=new ArrayList<>();
                    mEle.forEach(
                            ee ->{
                                var id = ee.getAttribute("id");
                                getMathForward(id).ifPresentOrElse(
                                        m ->{
                                            m.readFromXML(ee);
                                            altered.add(id);
                                        }, ()->maths.put(id,MathForward.fromXML(ee,dQueue,dataProviding)));
                            }
                    );
                    // Remove the ones that no longer exist
                    if( mEle.size() != maths.size() ){ // Meaning filters has more
                        // First mark them as invalid, so references also get deleted
                        maths.entrySet().stream().filter(e -> !altered.contains(e.getKey()) ).forEach( e->e.getValue().setInvalid());
                        //then remove them safely
                        maths.entrySet().removeIf( ee -> !ee.getValue().isConnectionValid());
                    }
                }
            case "list":
                join.setEmptyValue("No maths yet");
                maths.values().forEach(m -> join.add(m.toString()).add(""));
                return join.toString();
            case "addsource": case "addsrc":
                String source = cmds[2].startsWith("i2c:")?cmds[2]+","+cmds[3]:cmds[2];
                if( !source.contains(":"))
                    return "Base source given, needs to contain a :";
                if( getMathForward(cmds[1]).map(m -> m.addSource(source) ).orElse(false) )
                    return "Source added";
                return "Failed to add source, no such math.";
            case "addop":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be mf:addop,id,inputIndex(fe. i1)=formula";

                cmds[1]=cmds[1].toLowerCase();

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

                if( getMathForward(cmds[1]).map(f -> f.addStdOperation(cmds[2],-1,"").isPresent() ).orElse(false) ){
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
    public String replyToEditorCmd( String cmd, boolean html ){
        if( cmd.isEmpty() )
            cmd = "list";

        String[] cmds = cmd.split(",");

        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        EditorForward ef;
        XMLfab fab;

        switch( cmds[0] ) {
            case "?":
                join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
                        .add("  If a next step in the processing needs something altered to the format or layout of the data")
                        .add("  an editor can do this.");
                join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
                        .add("  - Editors don't do anything if it doesn't have a target (label counts as target)")
                        .add("  - Commands can start with ef:, instead of filters:")
                        .add("  - ...");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Create a EditorForward"+TelnetCodes.TEXT_YELLOW);
                join.add( "  ef/editors:addeditor/add,id<,source> -> Add a blank filter with an optional source.")
                    .add( "  ef:reload<,id> -> Reload the editor with the given id or all if no id was given.");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Other commands"+TelnetCodes.TEXT_YELLOW)
                        .add("  ef:list -> Get a list of all editors")
                        .add("  ef:remove,id -> Remove the given editor")
                        .add("  ef:clear -> Remove all editors")
                        .add("  ef:edits -> Get a list of all possible edit operations")
                        .add("  ef:addedit,id,type:value -> Add an edit of the given value, use type:? for format");
                return join.toString();
            case "addblank": case "addeditor": case "add":
                if( cmds.length<2)
                    return "Not enough arguments, needs to be ef:addeditor,id<,src,>";
                if( getEditorForward(cmds[1]).isPresent() )
                    return "Already editor with that id";

                StringJoiner src = new StringJoiner(",");
                for( int a=2;a<cmds.length;a++){
                    src.add(cmds[a]);
                }
                if(!src.toString().contains(":"))
                    return "Invalid source format, needs to be type:id";
                ef = addEditor(cmds[1].toLowerCase(),src.toString());
                if( ef == null)
                    return "Something wrong with the command, filter not created";
                ef.writeToXML( XMLfab.withRoot(settingsPath, "dcafs") );
                return "Blank editor with id "+cmds[1]+ " created"+(cmds.length>2?", with source "+cmds[2]:"")+".";
            case "remove":
                if( cmds.length!=2)
                    return "Missing id";
                if( XMLfab.withRoot(settingsPath, "dcafs", "editors").removeChild("editor","id",cmds[1]) ){
                    maths.remove(cmds[1]);
                    return "Editor removed";
                }
                return "No such editor "+cmds[1];
            case "clear":
                XMLfab.withRoot(settingsPath, "dcafs", "editors").clearChildren().build();
                editors.clear();
                return "Maths cleared";
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
            case "alter":
                ef = editors.get(cmds[1]);
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be ef:alter,id,param:value";
                if( ef == null )
                    return "No such editor: "+cmds[1];

                if( !cmds[2].contains(":"))
                    return "No proper param:value pair";

                String param = cmds[2].substring(0,cmds[2].indexOf(":"));

                String val = cmd.substring(cmd.indexOf(param+":")+param.length()+1);

                fab = XMLfab.withRoot(settingsPath,"dcafs","editors"); // get a fab pointing to the maths node

                if( fab.selectChildAsParent("editor","id",cmds[1]).isEmpty() )
                    return "No such editor node '"+cmds[1]+"'";

                if (param.equals("label")) {
                    ef.setLabel(val);
                    fab.attr("label", val);
                    return fab.build() ? "Label changed" : "Label change failed";
                }
                return "No valid alter target: " + param;
            case "edits": return EditorForward.getHelp(html?"<br>":"\r\n");
            case "addedit":
                if( cmds.length < 3) // might be larger if the value contains  a ,
                    return "Bad amount of arguments, should be ef/editors:addedit,id,type:value";
                var eOpt = getEditorForward(cmds[1]);
                if( eOpt.isEmpty())
                    return "No such editor";

                var e = eOpt.get();
                int x = cmd.indexOf(":");
                if( x==-1)
                    return "Incorrect format, needs to be ef/editors:addedit,id,type:value(s)";
                // The value might contain , so make sure to split properly
                String type = cmds[2].substring(0,cmds[2].indexOf(":"));
                String value = cmd.substring(x+1);
                String deli = e.delimiter;

                var p = value.split(",");
                fab = XMLfab.withRoot(settingsPath,"dcafs","editors");
                fab.selectOrAddChildAsParent("editor","id",cmds[1]);

                switch (type) {
                    /* Splitting */
                    case "rexsplit" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",rexsplit:delimiter,regextomatch";
                        if (value.startsWith(",")) {
                            deli = ",";
                            value = value.substring(2);
                        } else {
                            int in = value.indexOf(",");
                            deli = value.substring(0, in);
                            value = value.substring(in + 1);
                        }
                        e.addRexsplit(deli, value);
                        fab.comment("Find matches on " + value + " then concatenate with " + deli);
                        addEditNode(fab, type, value, false)
                                .attr("delimiter", deli)
                                .attr("leftover", "append").build();
                        return "Find matches on " + value + " then concatenate with " + deli;
                    }
                    case "resplit" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",resplit:delimiter,format";
                        if (value.startsWith(",")) {
                            deli = ",";
                            value = value.substring(2);
                        } else {
                            int in = value.indexOf(",");
                            deli = value.substring(0, in);
                            value = value.substring(in + 1);
                        }
                        e.addResplit(deli, value, "", true);
                        fab.comment("Split on " + deli + " then combine according to " + value);
                        addEditNode(fab, type, value, false)
                                .attr("delimiter", deli)
                                .attr("leftover", "append").build();
                        return "Split on '" + deli + "', then combine according to " + value + " and append leftover data";
                    }
                    case "charsplit" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",rexplit:regextosplitwith";
                        e.addCharSplit(",", value);
                        addEditNode(fab, type, value, true);
                        return "Charsplit added with default delimiter";
                    }
                    /* Timestamp stuff */
                    case "redate" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",redate:index,from,to";
                        e.addRedate(p[1], p[2], NumberUtils.toInt(p[0]), deli);
                        addEditNode(fab, "redate", p[2], false);
                        fab.attr("index", p[0]).build();
                        return "After splitting on " + deli + " the date on index " + p[0] + " is reformatted from " + p[1] + " to " + p[2];
                    }
                    case "retime" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",retime:index,from,to";
                        e.addRetime(p[0], p[2], NumberUtils.toInt(p[1]), ",");
                        addEditNode(fab, "retime", p[2], false);
                        fab.attr("index", p[1]).build();
                        return "After splitting on , the time on index " + p[1] + " is reformatted from " + p[0] + " to " + p[2];
                    }
                    /* Replacing */
                    case "replace" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",replace:what,with";
                        e.addReplacement(p[0], p[1]);
                        fab.comment("Replace " + p[0] + " with " + p[1]);
                        fab.addChild("edit", p[1]).attr("type", type).attr("find", p[0]).build();
                        return "Replacing " + p[0] + " with " + p[1];
                    }
                    case "rexreplace" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",rexreplace:regexwhat,with";
                        e.addRegexReplacement(p[0], p[1]);
                        fab.addChild("edit", p[1]).attr("type", type).attr("find", p[0]).build();
                        return "Replacing " + p[0] + " with " + p[1];
                    }
                    /* Remove stuff */
                    case "remove" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",remove:find";
                        e.addReplacement(value, "");
                        fab.addChild("edit", value).attr("type", type).build();
                        return "Removing " + value + " from the data";
                    }
                    case "rexremove" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",rexremove:regexfind";
                        e.addRegexReplacement(value, "");
                        fab.addChild("edit", value).attr("type", type).build();
                        return "Removing matches of " + value + " from the data";
                    }
                    case "trim" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",trim";
                        e.addTrim();
                        fab.addChild("edit").attr("type", type).build();
                        return "Trimming spaces from data";
                    }
                    case "cutstart" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",cutstart:charcount";
                        e.addCutStart(NumberUtils.toInt(value));
                        fab.addChild("edit", value).attr("type", type).build();
                        return "Cutting " + value + " char(s) from the start";
                    }
                    case "cutend" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",cutend:charcount";
                        e.addCutEnd(NumberUtils.toInt(value));
                        return "Cutting " + value + " char(s) from the end";
                    }
                    /* Adding stuff */
                    case "prepend", "prefix" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",prepend:toprepend or ef:addedit,prefix:toprepend";
                        e.addPrepend(value);
                        fab.addChild("edit", value).attr("type", type).build();
                        return "Prepending " + value + " to the data";
                    }
                    case "insert" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",insert:position,toinsert";
                        e.addInsert(NumberUtils.toInt(p[0]), p[1]);
                        fab.addChild("edit", p[1]).attr("position", p[0]).attr("type", type).build();
                        return "Inserting " + value + " at char " + p[0] + " in the data";
                    }
                    case "append", "suffix" -> {
                        if (value.equals("?"))
                            return "ef:addedit," + cmds[1] + ",append:toappend or ef:addedit," + cmds[1] + ",suffix:toappend";
                        e.addAppend(value);
                        return "Appending " + value + " to the data";
                    }
                }
                return "Edit added";
        }
        return "Unknown command: "+cmds[0];
    }
    private XMLfab addEditNode( XMLfab fab, String type, String value, boolean build){
        fab.addChild("edit",value).attr( "type",type)
                .attr("delimiter",",");
        if( build )
            fab.build();
        return fab;
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
                        .add("  - Commands can start with ff: instead of filters:")
                        .add("  - ...");
                join.add("").add(TelnetCodes.TEXT_GREEN+"Create a FilterForward"+TelnetCodes.TEXT_YELLOW);
                join.add( "  filters:addfilter/add,id<,source> -> Add a filter without rules and optional source.");
                join.add( "  filters:rules -> Get a list of all the possible rules with a short explanation");
                join.add( "  filters:addshort,id,src,rule:value -> Adds a filter with the given source and rule (type:value)");
                join.add( "  filters:addsource,id,source -> Add a source to the given filter");
                join.add( "  filters:addrule,id,rule:value -> Add a rule to the given filter");

                join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
                join.add( "  filters:alter,id,param:value -> Alter a parameter, for now only altering the label is possible");
                join.add( "  filters:reload,id -> Reload the filter with the given id");
                join.add( "  filters:reload -> Clear the list and reload all the filters");
                join.add( "  filters:remove,id -> Remove the filter with the given id");
                join.add( "  filters:test,id,data -> Test if the data would pass the filter");
                join.add( "  filters:list or ff -> Get a list of all the currently existing filters.");
                join.add( "  filters:delrule,id,index -> Remove a rule from the filter based on the index given in ff:list");
                join.add( "  filters:swaprawsrc,id,ori,new -> Swap the raw ori source of the given filter with the new raw one, mimick redundancy");
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
            case "addrule":
                if( cmds.length < 3)
                    return "Bad amount of arguments, should be ff:addrule,id,type:value";
                String rule = cmds.length==4?cmds[2]+","+cmds[3]:cmds[2]; // step might contain a ,
                var fOpt = getFilterForward(cmds[1].toLowerCase());
                Logger.info("Filter exists?"+fOpt.isPresent());
                switch( fOpt.map( f -> f.addRule(rule) ).orElse(0) ){
                    case 1:
                        fOpt.get().writeToXML(XMLfab.withRoot(settingsPath, "dcafs"));
                        return "Rule added to "+cmds[1];
                    case 0:  return "Failed to add rule, no such filter called "+cmds[1];
                    case -1: return "Unknown type in "+rule+", try ff:types for a list";
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
                if( !source.contains(":"))
                    return "No valid source, missing : ";
                if( getFilterForward(cmds[1]).map(f -> f.addSource(source) ).orElse(false) )
                    return "Source added";
                return "Failed to add source, no such filter.";
            case "addblank": case "addfilter": case "add":
                if( cmds.length<2)
                    return "Not enough arguments, needs to be ff/filters:addfilter,id<,src,>";
                if( getFilterForward(cmds[1]).isPresent() )
                    return "Already filter with that id";

                StringJoiner src = new StringJoiner(",");
                for( int a=2;a<cmds.length;a++){
                    src.add(cmds[a]);
                }
                if(!src.toString().contains(":"))
                    return "Invalid source format, needs to be type:id";
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

                ff = addFilter(cmds[1].toLowerCase(),cmds[2],cmds.length==4?cmds[3]:cmds[3]+","+cmds[4]);
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

                if( fab.selectChildAsParent("filter","id",cmds[1]).isEmpty() )
                    return "No such filter node '"+cmds[1]+"'";

                switch (param) {
                    case "label" -> {
                        ff.setLabel(value);
                        fab.attr("label", value);
                        return fab.build() ? "Label changed" : "Label change failed";
                    }
                    case "delim", "delimiter" -> {
                        fab.attr("delimiter", value);
                        return fab.build() ? "Delimiter changed" : "Delimiter change failed";
                    }
                }
                return "No valid alter target: " + param;
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

                ff.removeSource("raw:"+cmds[2]);
                ff.addSource("raw:"+cmds[3]);
                return "Swapped source of "+cmds[1]+" from "+cmds[2]+" to "+cmds[3];
            case "remove":
                if( cmds.length!=2)
                    return "Missing id";
                if( XMLfab.withRoot(settingsPath, "dcafs", "filters").removeChild("filter","id",cmds[1]) ){
                    filters.remove(cmds[1]);
                    return "Filter removed";
                }
                return "No such filter "+cmds[1];
            case "clear":
                XMLfab.withRoot(settingsPath, "dcafs", "filters").clearChildren().build();
                filters.clear();
                return "Filters cleared";
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
        boolean blank=false;
        XMLfab fab;
        switch(cmds[0]){
            case "?":
                StringJoiner help = new StringJoiner("\r\n");
                help.add(TelnetCodes.TEXT_RESET+TelnetCodes.TEXT_GREEN+"General info"+TelnetCodes.TEXT_YELLOW)
                .add(" Paths are used to combine multiple different forwards in a single structure, this allows for a")
                .add(" lot of attributes to be either generated by dcafs fe. src,label or omitted (id's) which reduces")
                .add(" the amount of xml the user needs to write.");
                help.add("").add(TelnetCodes.TEXT_GREEN+"Add/build new paths"+TelnetCodes.TEXT_YELLOW)
                .add(" paths:addpath/add,id,src -> Add a path with the given id and src")
                .add(" paths:addfile/add,id,src -> Add a path file with the given id and src")
                .add(" paths:addsteps,id,format -> Add empty steps to the path")
                .add("                             Format options are:")
                .add("                             F -> filter without subs")
                .add("                             f* -> filter with one sub per f fe. ff = two subnodes")
                .add("                             Same applies for M(ath),m(op),E(ditor),e(edit)")
                .add("                             Generic also has paths:addgen but G(eneric) and i(nt), r(eal),t(ext) work")
                .add(" paths:addgen,pathid<:genid>,group,format -> genid is optional, default is same as pathid format is the same ")
                .add("                                             as gens:addblank fe. i2temp -> integer on index 2 named temp")
                .add("                                             multiple with , delimiting is possible");
                help.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
                .add(" paths:reload,id -> reload the path with the given id")
                .add(" paths:readpath,id,path -> Add a path using a path xml")
                .add(" paths:list -> List all the currently loaded paths")
                .add(" paths:debug,id,stepnr/stepid -> Request the data from a single step in the path (nr:0=first; -1=custom src)");
                return help.toString();
            case "reload":
                if( cmds.length==1) {
                    dQueue.add( Datagram.system("gens:reload") );
                    readPathsFromXML();
                    return "All paths reloaded.";
                }
                var ele = XMLfab.withRoot(settingsPath,"dcafs","paths")
                        .getChild("path","id",cmds[1]);
                if(ele.isEmpty())
                    return "No such path "+cmds[1];
                if( paths.get(cmds[1]).readFromXML(ele.get(),settingsPath.getParent().toString()) )
                    return "Path reloaded";
                return "Path failed to reload";
            case "readfile":

                return "File reading added (todo)";
            case "addfile":
                if( cmds.length<2) {
                    return "To few arguments, expected pf:addfile,id,src (src is optional)";
                }

                try {
                    Files.createDirectories( settingsPath.getParent().resolve("paths") );
                } catch (IOException e) {
                    Logger.error(e);
                }

                var p = settingsPath.getParent().resolve("paths").resolve(cmds[1]+".xml");
                XMLfab.withRoot(p,"dcafs")
                        .addParentToRoot("path")
                            .attr("id",cmds[1])
                            .attr("delimiter",",")
                        .build();

                XMLfab.withRoot(settingsPath,"dcafs","paths")
                        .selectOrAddChildAsParent("path","id",cmds[1])
                        .attr("src",cmds.length>2?cmds[2]:"")
                        .attr("delimiter",",")
                        .attr("import","paths"+ File.separator+cmds[1]+".xml")
                        .build();
                dQueue.add( Datagram.system("gens:reload") );
                readPathsFromXML();
                return "New path file added and reloaded";
            case "addblank": case "addpath": case "add":
                blank=true;
                if( cmds.length<2) {
                    return "To few arguments, expected pf:add,id,src";
                }
                XMLfab.withRoot(settingsPath,"dcafs","paths")
                        .selectOrAddChildAsParent("path","id",cmds[1])
                            .attr("src",cmds.length>2?cmds[2]:"")
                            .attr("delimiter",",")
                            .build();

                if( cmds.length==3) {
                    if( !cmds[2].contains(":")){
                        return "No valid source  given, need to contain a :";
                    }
                    PathForward path = new PathForward(dataProviding,dQueue,nettyGroup);
                    path.setWorkPath(settingsPath.getParent());
                    if( cmds[2].startsWith("file:")) {
                        fab = XMLfab.withRoot(settingsPath, "dcafs", "paths")
                                .selectOrAddChildAsParent("path", "id", cmds[1])
                                .addChild("customsrc", cmds[2]).attr("type", "file").attr("interval", "1s");
                        fab.build();
                        path.readFromXML( fab.getCurrentElement(),settingsPath.getParent().toString() );
                    }else{
                        path.setSrc(cmds[2]);
                    }
                    paths.put(cmds[1],path);
                    return "Path added";
                }else if( cmds.length<3){
                    return "Not enough arguments, need paths:addpath,id,src";
                }
            case "addnodes": case "addnode": case "addstep": case "addsteps":
                if( !blank ) {
                    if (paths.get(cmds[1]) == null)
                        return "No such path " + cmds[1];
                }
                fab = XMLfab.withRoot(settingsPath,"dcafs","paths")
                        .selectOrAddChildAsParent("path","id",cmds[1]);

                int index;
                char prev='A';
                for( var c : cmds[2+(blank?1:0)].toCharArray()){
                    int i = fab.getChildren("generic").size()+1; // get the amount of generic parentnodes
                    switch(c) {
                        case 'F': fab.addChild("filter",".").attr("type",""); break;
                        case 'f':
                            if( prev=='f'){
                                fab.selectOrAddLastChildAsParent("filter").addChild("rule",".").attr("type","").up();
                            }else{
                                fab.addChild("filter").down().addChild("rule",".").attr("type","").up();
                            }
                            break;
                        case 'E': fab.addChild("editor",".").attr("type",""); break;
                        case 'e':
                            if( prev=='e'){
                                fab.selectOrAddLastChildAsParent("editor").addChild("edit",".").attr("type","").up();
                            }else{
                                fab.addChild("editor").down().addChild("edit",".").attr("type","").up();
                            }
                            break;
                        case 'M': fab.addChild("math","i0=i0+1"); break;
                        case 'm':
                            if( prev=='m'){
                                fab.selectOrAddLastChildAsParent("math").addChild("op",".").attr("type","").up();
                            }else{
                                fab.addChild("math").down().addChild("op",".").attr("type","").up();
                            }
                            break;
                        case 'G':
                            fab.addChild( "generic").attr("id",cmds[1]+"_"+i);
                            break;
                        case 'i':
                            fab.selectOrAddLastChildAsParent("generic","id",cmds[1]+"_"+i);
                            index = fab.getChildren("*").size()+1;
                            fab.addChild("integer",cmds[1]+"_").attr("index",index).up();
                            break;
                        case 'r':
                            fab.selectOrAddLastChildAsParent("generic","id",cmds[1]+"_"+i);
                            index = fab.getChildren("*").size()+1;
                            fab.addChild("real",cmds[1]+"_").attr("index",index).up();
                            break;
                        case 't':
                            fab.selectOrAddLastChildAsParent("generic","id",cmds[1]+"_"+i);
                            index = fab.getChildren("*").size()+1;
                            fab.addChild("text",cmds[1]+"_").attr("index",index).up();
                            break;
                    }
                    prev = c;
                }
                if( fab.build())
                    return "XML altered";
                return "Failed to alter XML";
            case "addgen":
                if(cmds.length<5)
                    return "Not enough arguments: path:addgen,pathid<:genid>,group,format,delimiter";

                var ids = cmds[1].split(":");

                var fabOpt = XMLfab.withRoot(settingsPath,"dcafs","paths").selectChildAsParent("path","id",ids[0]);
                if(fabOpt.isEmpty())
                    return "No such path: "+ids[0];
                fab = fabOpt.get();

                // Compare the path and generic delimiter if they are the same, no need to add it to the xml
                var deli = fab.getCurrentElement().getAttribute("delimiter"); // Get the path default delimiter
                var delimiter=cmd.endsWith(",")?",":cmds[cmds.length-1]; // get the generic delimiter
                if( deli.equalsIgnoreCase(delimiter))
                    delimiter="";

                if(Generic.addBlankToXML( fab,ids.length==2?ids[1]:ids[0],cmds[2],ArrayUtils.subarray(cmds,3,cmds.length-1),delimiter) ){
                    return "Generic added & reloaded";
                }
                return "Failed to write generic";
            case "remove":
                if( cmds.length!=2)
                    return "Missing id";
                if( XMLfab.withRoot(settingsPath, "dcafs", "paths").removeChild("path","id",cmds[1]) ){
                    filters.remove(cmds[1]);
                    return "Path removed";
                }
                return "No such path "+cmds[1];
            case "clear":
                XMLfab.withRoot(settingsPath, "dcafs", "paths").clearChildren().build();
                filters.clear();
                return "Paths cleared";
            case "stop":
                paths.values().forEach( pf -> pf.removeTarget(wr) );
                return "Stopped sending to "+wr.getID();
            case "list":
                StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
                join.setEmptyValue("No paths yet");
                paths.forEach((key, value) -> join.add(TelnetCodes.TEXT_GREEN+"Path: " + key+TelnetCodes.TEXT_YELLOW).add( value.toString() ).add(""));
                return join.toString();
            case "debug":
                if( cmds.length!=3)
                    return "Incorrect number of arguments, needs to be path:debug,pathid,stepnr/stepid (from 0 or -1 for customsrc)";
                var pp = paths.get(cmds[1]);
                if( pp==null)
                    return "No such path: "+cmds[1];
                int nr = NumberUtils.toInt(cmds[2],-2);

                if( wr==null)
                    return "No valid writable";
                if( nr==-2)
                    return pp.debugStep(cmds[2],wr);
                return pp.debugStep(nr,wr);
            default:
                return "Unknown command: paths:"+cmd;
        }
    }
    public void readPathsFromXML(){
        XMLfab.getRootChildren(settingsPath,"dcafs","paths","path").forEach(
                pathEle -> {
                    PathForward path = new PathForward(dataProviding,dQueue,nettyGroup);
                    var p = paths.get(path.getID());
                    if( p!=null)
                        p.getTargets().forEach( path::addTarget );
                    path.setWorkPath(settingsPath.getParent());
                    path.readFromXML(pathEle,settingsPath.getParent().toString());
                    paths.put(path.getID(),path);
                }
        );
    }
}
