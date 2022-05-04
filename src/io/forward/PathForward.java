package io.forward;

import org.apache.commons.lang3.math.NumberUtils;
import util.data.DataProviding;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PathForward {

    private String src = "";

    private final ArrayList<Writable> targets = new ArrayList<>();
    private final ArrayList<CustomSrc> customs=new ArrayList<>();

    DataProviding dataProviding;
    BlockingQueue<Datagram> dQueue;
    EventLoopGroup nettyGroup;

    String id;
    ArrayList<AbstractForward> stepsForward;
    enum SRCTYPE {REG,PLAIN,RTVALS,CMD,FILE,INVALID}
    Path workPath;
    static int READ_BUFFER_SIZE=500;

    public PathForward(DataProviding dataProviding, BlockingQueue<Datagram> dQueue, EventLoopGroup nettyGroup ){
        this.dataProviding = dataProviding;
        this.dQueue=dQueue;
        this.nettyGroup=nettyGroup;
    }
    public void setWorkPath( Path wp){
        workPath=wp;
    }
    public String getID(){
        return id;
    }
    public PathForward src( String src){
        this.src=src;
        return this;
    }
    public void setSrc( String src ){
        this.src=src;
    }
    public boolean readFromXML( Element pathEle, String workpath ){

        var oldTargets = new ArrayList<>(targets);
        targets.clear();

        // if any future is active, stop it
        customs.forEach(CustomSrc::stop);

        if( stepsForward!=null) {// If this is a reload, reset the steps
            dQueue.add(Datagram.system("nothing").writable(stepsForward.get(0))); // stop asking for data
            lastStep().ifPresent(ls -> oldTargets.addAll(ls.getTargets()));
            stepsForward.clear();
        }

        id = XMLtools.getStringAttribute(pathEle,"id","");
        String delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");
        this.src = XMLtools.getStringAttribute(pathEle,"src","");

        var importPath = XMLtools.getPathAttribute(pathEle,"import",Path.of(workpath));
        if( importPath.isPresent() ) {
            var p = XMLfab.getRootChildren(importPath.get(),"dcafs","path").findFirst();
            if(p.isPresent()) {
                pathEle = p.get();
                delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");
                Logger.info("Valid path script found at "+importPath.get());
            }else{
                Logger.error("No valid path script found: "+importPath.get());
                return false;
            }
        }

        var steps = XMLtools.getChildElements(pathEle);

        if( steps.size() > 0) {
            stepsForward = new ArrayList<>();
        }else{
            return false;
        }

        FilterForward lastff=null;
        boolean hasLabel=false;
        int gens=1;
        int vals=1;
        for( int a=0;a<steps.size();a++  ){
            Element step = steps.get(a);

            if(step.getTagName().equalsIgnoreCase("customsrc")){
                addCustomSrc( step.getTextContent(),
                              XMLtools.getStringAttribute(step,"interval","1s"),
                              XMLtools.getStringAttribute(step,"type","plain"));
                continue;
            }

            // Check if the next step is a generic, if so change the label attribute of the current step
            if( a<steps.size()-1 ){
                var next = steps.get(a+1);
                if(next.getTagName().equalsIgnoreCase("generic")){
                    if( !step.hasAttribute("label")) {
                        var genid = next.getAttribute("id");
                        genid = genid.isEmpty()?id+"_gen"+gens:genid;
                        gens++;
                        step.setAttribute("label", "generic:" + genid);
                    }
                }
                if(next.getTagName().equalsIgnoreCase("valmap")){
                    if( !step.hasAttribute("label")){
                        var valid = next.getAttribute("id");
                        valid = valid.isEmpty()?id+"_vm"+vals:valid;
                        vals++;
                        step.setAttribute("label","valmap:"+valid);
                    }
                }
            }
            if( step.hasAttribute("label"))
                hasLabel=true;

            boolean lastGenMap = false;
            if( !stepsForward.isEmpty() ) {
                var prev = steps.get(a - 1);
                lastGenMap = prev.getTagName().equalsIgnoreCase("generic")
                                ||prev.getTagName().equalsIgnoreCase("valmap");
            }

            // If this step doesn't have a delimiter, alter it
            if( !step.hasAttribute("delimiter")&& !delimiter.isEmpty())
                step.setAttribute("delimiter",delimiter);

            if( !step.hasAttribute("id"))
                step.setAttribute("id",id+"_"+stepsForward.size());

            var src = XMLtools.getStringAttribute(step,"src","");
            if( stepsForward.isEmpty() && !src.isEmpty())
                step.setAttribute("src","");

            switch (step.getTagName()) {
                case "filter" -> {
                    FilterForward ff = new FilterForward(step, dQueue);
                    if (!src.isEmpty()) {
                        addAsTarget(ff, src);
                    } else if (lastff != null && (!(lastStep().isPresent()&&lastStep().get() instanceof FilterForward) || lastGenMap)) {
                        lastff.addReverseTarget(ff);
                    } else {
                        addAsTarget(ff, src);
                    }
                    lastff = ff;
                    stepsForward.add(ff);
                }
                case "math" -> {
                    MathForward mf = new MathForward(step, dQueue, dataProviding);
                    mf.removeSources();
                    addAsTarget(mf, src);
                    stepsForward.add(mf);
                }
                case "editor" -> {
                    var ef = new EditorForward(step, dQueue, dataProviding);
                    if( !ef.readOk)
                        return false;
                    ef.removeSources();
                    addAsTarget(ef, src);
                    stepsForward.add(ef);
                }
            }

        }
        if( !oldTargets.isEmpty()&&!stepsForward.isEmpty()){ // Restore old requests
            oldTargets.forEach(this::addTarget);
        }
        if( !lastStep().map(AbstractForward::noTargets).orElse(false) || hasLabel) {
            if (customs.isEmpty() ) { // If no custom sources
                dQueue.add(Datagram.system(this.src).writable(stepsForward.get(0)));
            } else {// If custom sources
                if( !stepsForward.isEmpty()) // and there are steps
                    targets.add(stepsForward.get(0));
                customs.forEach(CustomSrc::start);
            }
        }
        customs.trimToSize();
        return true;
    }
    private void addAsTarget( AbstractForward f, String src ){
        if( !src.isEmpty() ){
            var s = getStep(src);
            if( s!= null ) {
                if( s instanceof FilterForward && src.startsWith("!")){
                    ((FilterForward) s).addReverseTarget(f);
                }else {
                    s.addTarget(f);
                }
            }
        }else if( !stepsForward.isEmpty() ) {
            lastStep().ifPresent( ls -> ls.addTarget(f));
        }
    }
    public String debugStep( String step, Writable wr ){
        for( var sf : stepsForward ) {
            sf.removeTarget(wr);
            if (sf.id.equalsIgnoreCase(step)) {
                sf.addTarget(wr);
                return "Request for " + sf.getXmlChildTag() + ":" + sf.id + " received";
            }
        }
        return "No such step";
    }
    public String debugStep( int step, Writable wr ){
        if( wr==null )
            return "No proper writable received";
        if( step >= stepsForward.size() )
            return "Wanted step "+step+" but only "+stepsForward.size()+" available";

        for( var ab : stepsForward )
            ab.removeTarget(wr);
        if( !customs.isEmpty() ){
            if( step == -1){
                targets.add(wr);
                customs.forEach(CustomSrc::start);
            }else if( step < stepsForward.size()){
                stepsForward.get(step).addTarget(wr);
            }
        }else if(step !=-1 && step < stepsForward.size() ){
            stepsForward.get(step).addTarget(wr);
        }else{
            return "Failed to request data, bad index given";
        }
        var s = stepsForward.get(step);

        return "Request for "+s.getXmlChildTag()+":"+s.id+" received";
    }
    private Optional<AbstractForward> lastStep(){
        if( stepsForward.isEmpty())
            return Optional.empty();
        return Optional.ofNullable(stepsForward.get(stepsForward.size()-1));
    }
    private AbstractForward getStep(String id){
        for( var step : stepsForward){
            if( id.endsWith(step.id)) // so that the ! (for reversed) is ignored
                return step;
        }
        return null;
    }
    public void addCustomSrc( String data, String interval, String type){
        if( data.contains("{") && data.contains("}")) {
            type ="rtvals";
        }else if(type.equalsIgnoreCase("rtvals")){
            type="plain";
        }
        customs.add( new CustomSrc(data,type,TimeTools.parsePeriodStringToMillis(interval)) );
    }

    public String toString(){
        var join = new StringJoiner("\r\n");
        if( customs.isEmpty() ){
            if( stepsForward==null||stepsForward.isEmpty())
                return "Nothing in the path yet";
        }

        customs.forEach(c->join.add(c.toString()));
        if(stepsForward!=null) {
            for (AbstractForward abstractForward : stepsForward) {
                join.add("   -> " + abstractForward.toString());
            }
            if( !stepsForward.isEmpty() )
                join.add( " gives the data from "+stepsForward.get(stepsForward.size()-1).getID() );
        }
        return join.toString();
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }
    public void addTarget(Writable wr){
        if( stepsForward == null )
            return;

        if( stepsForward.isEmpty() ){ // If no steps are present
            if (!targets.contains(wr))
                targets.add(wr);
        }else{
            if (!targets.contains(stepsForward.get(0)))
                targets.add(stepsForward.get(0));
            stepsForward.get(stepsForward.size()-1).addTarget(wr);
        }

        if( targets.size()==1 ){
            if( customs.isEmpty()){
                dQueue.add( Datagram.system(src).writable(stepsForward.get(0)));
            }else{
                customs.forEach(CustomSrc::start);
            }
        }
    }
    public void removeTarget( Writable wr){
        if(stepsForward==null)
            return;
        if( stepsForward.isEmpty() ) {
            targets.remove(wr);// Stop giving data
        }else{
            for( var step : stepsForward )
                step.removeTarget(wr);

            if( lastStep().isEmpty() )
                return;
            if( lastStep().map(AbstractForward::noTargets).orElse(true) ){ // if the final step has no more targets, stop the first step
                customs.forEach(CustomSrc::stop);
            }
        }
    }

    private class CustomSrc{
        String data;
        SRCTYPE srcType;
        long intervalMillis;
        ScheduledFuture<?> future;
        ArrayList<String> buffer;
        ArrayList<Path> files;
        int lineCount=1;
        int multiLine=1;

        public CustomSrc( String data, String type, long intervalMillis){
            this.data =data;
            this.intervalMillis=intervalMillis;
            var spl = type.split(":");
            srcType = switch (spl[0]) {
                case "rtvals" -> SRCTYPE.RTVALS;
                case "cmd" -> SRCTYPE.CMD;
                case "plain" -> SRCTYPE.PLAIN;
                case "file" ->  SRCTYPE.FILE;
                default -> SRCTYPE.INVALID;
            };
            if( srcType==SRCTYPE.FILE){
                files = new ArrayList<>();
                if (!Path.of(data).isAbsolute()) {
                    this.data = workPath.resolve(data).toString();
                }
                if (Files.isDirectory(Path.of(this.data))) {
                    try {
                        try( var str = Files.list(Path.of(this.data)) ){
                            str.forEach(files::add);
                        }
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                } else {
                    files.add(Path.of(this.data));
                }
                buffer = new ArrayList<>();
                if (spl.length == 2)
                    multiLine = NumberUtils.toInt(spl[1]);
            }else if( srcType == SRCTYPE.INVALID ){
                Logger.error(id + "(pf) -> no valid srctype '" + type + "'");
            }
        }
        public void start(){
            if( future==null || future.isDone())
                future = nettyGroup.scheduleAtFixedRate(this::write,intervalMillis,intervalMillis, TimeUnit.MILLISECONDS);
        }
        public void stop(){
            if( future!=null && !future.isCancelled())
                future.cancel(true);
        }
        public void write(){
            targets.removeIf( x -> !x.isConnectionValid());
            if( targets.isEmpty() )
                stop();

            switch( srcType){
                case CMD:
                    targets.forEach(t->dQueue.add( Datagram.build(data).label("telnet").writable(t))); break;
                case RTVALS:
                    var write = dataProviding.parseRTline(data,"-999");
                    targets.forEach( x -> x.writeLine(write));
                    break;
                default:
                case PLAIN: targets.forEach( x -> x.writeLine(data)); break;
                case FILE:
                    try {
                        for( int a=0;a<multiLine;a++){
                            if (buffer.isEmpty()) {
                                if( files.isEmpty()){
                                    future.cancel(true);
                                    dQueue.add( Datagram.system("telnet:broadcast,info,"+id+" finished."));
                                    return;
                                }
                                buffer.addAll(FileTools.readLines(files.get(0), lineCount, READ_BUFFER_SIZE));
                                lineCount += buffer.size();
                                if( buffer.size() < READ_BUFFER_SIZE ){
                                    dQueue.add( Datagram.system("telnet:broadcast,info,"+id+" processed "+files.get(0)));
                                    files.remove(0);
                                    lineCount = 1;
                                    if( buffer.isEmpty()) {
                                        if (!files.isEmpty()) {
                                            buffer.addAll(FileTools.readLines(files.get(0), lineCount, READ_BUFFER_SIZE));
                                            lineCount += buffer.size();
                                        }else{
                                            future.cancel(true);
                                            dQueue.add( Datagram.system("telnet:broadcast,info,"+id+" finished."));
                                            return;
                                        }
                                    }
                                }
                            }
                            String line = buffer.remove(0);
                            targets.forEach( wr-> wr.writeLine(line));
                        }
                    }catch(Exception e){
                        Logger.error(e);
                    }
                    break;
            }
        }
        public String toString(){
            return "Reads '"+data+"' every "+TimeTools.convertPeriodtoString(intervalMillis,TimeUnit.MILLISECONDS);
        }
    }
}
