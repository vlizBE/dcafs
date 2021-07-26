package io.forward;

import das.DataProviding;
import io.Writable;
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
    HashMap<String, String> paths = new HashMap<>();

    BlockingQueue<Datagram> dQueue;
    Path settingsPath;
    DataProviding dataProviding;

    public ForwardPool(BlockingQueue<Datagram> dQueue, Path settingsPath, DataProviding dataProviding){
        this.dQueue=dQueue;
        this.settingsPath=settingsPath;
        this.dataProviding=dataProviding;
        readSettingsFromXML();
    }

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
                child -> {
                    String src = XMLtools.getStringAttribute(child,"src","");
                    String id = XMLtools.getStringAttribute(child,"id","");
                    String imp = XMLtools.getStringAttribute(child,"import","");
                    String delimiter = "";

                    if( !imp.isEmpty() ) {
                        var p = XMLfab.getRootChildren(Path.of(imp),"dcafs","path").findFirst();
                        if(p.isPresent()) {
                            child = p.get();
                            delimiter = XMLtools.getStringAttribute(child,"delimiter","");
                            Logger.info("Valid path script found at "+imp);
                        }else{
                            Logger.error("No valid path script found: "+imp);
                            return;
                        }
                    }

                    int ffId=1;
                    int mfId=1;
                    int efId=1;
                    String lastFilter="";
                    var steps = XMLtools.getChildElements(child);
                    for( int a=0;a<steps.size();a++  ){
                        Element step = steps.get(a);

                        // Check if the next step is a generic, if so change the label attribute of the current step
                        if( a<steps.size()-1 ){
                            var next = steps.get(a+1);
                            if(next.getTagName().equalsIgnoreCase("generic")){
                                if( !step.hasAttribute("label"))
                                    step.setAttribute("label","generic:"+next.getAttribute("id"));
                            }
                            if(next.getTagName().equalsIgnoreCase("valmap")){
                                if( !step.hasAttribute("label"))
                                    step.setAttribute("label","valmap:"+next.getAttribute("id"));
                            }
                        }
                        // If this step doesn't have a src, alter it
                        if( !step.hasAttribute("src")) {
                            // If it's a filter, use the discarded stuff of the previous one
                            if( !lastFilter.isEmpty() && step.getTagName().equals("filter")){
                                step.setAttribute("src", lastFilter);
                            }else{
                                step.setAttribute("src", src);
                            }
                        }
                        // If this step doesn't have a delimiter, alter it
                        if( !step.hasAttribute("delimiter")&& !delimiter.isEmpty())
                            step.setAttribute("delimiter",delimiter);

                        switch( step.getTagName() ){
                            case "filter":
                                if( !step.hasAttribute("id")) {
                                    step.setAttribute("id", id + "_f" + ffId);
                                    ffId++;
                                }
                                FilterForward ff = new FilterForward( step, dQueue );
                                src=ff.getID();
                                lastFilter=src.replace(":",":!");
                                filters.put(ff.getID().replace("filter:", ""), ff);
                                break;
                            case "math":
                                if( !step.hasAttribute("id")) {
                                    step.setAttribute("id", id + "_m" + mfId);
                                    mfId++;
                                }
                                MathForward mf = new MathForward( step,dQueue,dataProviding );
                                src = mf.getID();
                                maths.put(mf.getID().replace("math:", ""), mf);
                                break;
                            case "editor":
                                if( !step.hasAttribute("id")) {
                                    step.setAttribute("id", id + "_e" + efId);
                                    efId++;
                                }
                                var tf = new EditorForward( step,dQueue,dataProviding );
                                src = tf.getID();
                                editors.put(tf.getID().replace("editor:", ""), tf);
                                break;
                        }
                    }
                    paths.put(id,src);
                }
        );
    }

    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        boolean ok=false;
        if( request[0].equals("path")){
            var src = paths.get(request[1]);
            if( src != null ) {
                var spl = src.split(":");
                request[0] = spl[0];
                request[1] = spl[1];
            }
        }
        switch(request[0]){
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
                    mm.addComplex(cmds[cmds.length-1]);
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

                if( getMathForward(cmds[1]).map(f -> f.addComplex(cmds[2]) ).orElse(false) ){
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
                join.add( "  ef:addblank,id<,source> -> Add a blank filter with an optional source, gets stored in xml.");
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
                        //then remove then safely
                        editors.entrySet().removeIf( ee -> !ee.getValue().isConnectionValid());
                    }
                }
                return "Editor reloaded.";
            case "list":
                join.setEmptyValue("No editors yet");
                editors.values().forEach( f -> join.add(f.toString()).add("") );
                return join.toString();
            case "rules": return EditorForward.getHelp(html?"<br>":"\r\n");
        }
        return "Unknown command: "+cmds[0];
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
                    return "Bad amount of arguments, should be filters:addrule,id,type:value";
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
                    return "Bad amount of arguments, should be filters:delrule,id,index";
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
}
