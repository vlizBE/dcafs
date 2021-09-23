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
    private ArrayList<CustomSrc> customs=new ArrayList<>();

    DataProviding dataProviding;
    BlockingQueue<Datagram> dQueue;
    EventLoopGroup nettyGroup;

    String id;
    ArrayList<AbstractForward> stepsForward;
    enum SRCTYPE {REG,PLAIN,RTVALS,CMD,FILE}
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
    public void readFromXML( Element pathEle ){

        var oldTargets = new ArrayList<Writable>();

        oldTargets.addAll(targets);
        targets.clear();

        if( customs!=null) { // if any future is active, stop it
            customs.forEach(CustomSrc::stop);
        }

        if( stepsForward!=null) {// If this is a reload, reset the steps
            dQueue.add(Datagram.system("nothing").writable(stepsForward.get(0))); // stop asking for data
            lastStep().ifPresent(ls -> oldTargets.addAll(ls.getTargets()));
            stepsForward.clear();
        }

        id = XMLtools.getStringAttribute(pathEle,"id","");
        String imp = XMLtools.getStringAttribute(pathEle,"import","");
        String delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");;
        this.src = XMLtools.getStringAttribute(pathEle,"src","");

        if( !imp.isEmpty() ) {
            var p = XMLfab.getRootChildren(Path.of(imp),"dcafs","paths","path").findFirst();
            if(p.isPresent()) {
                pathEle = p.get();
                delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");
                Logger.info("Valid path script found at "+imp);
            }else{
                Logger.error("No valid path script found: "+imp);
                return;
            }
        }

        var steps = XMLtools.getChildElements(pathEle);

        if( steps.size() > 0) {
            stepsForward = new ArrayList<>();
        }else{
            return;
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
            switch( step.getTagName() ){
                case "filter":
                    FilterForward ff = new FilterForward( step, dQueue );
                    if( !src.isEmpty()){
                        addAsTarget(ff,src);
                    }else if( lastff != null && (!(lastStep().get() instanceof FilterForward) || lastGenMap)) {
                        lastff.addReverseTarget(ff);
                    }else{
                        addAsTarget(ff,src);
                    }
                    lastff=ff;
                    stepsForward.add(ff);
                    break;
                case "math":
                    MathForward mf = new MathForward( step,dQueue,dataProviding );
                    mf.removeSources();
                    addAsTarget(mf,src);
                    stepsForward.add(mf);
                    break;
                case "editor":
                    var ef = new EditorForward( step,dQueue,dataProviding );
                    ef.removeSources();
                    addAsTarget(ef,src);
                    stepsForward.add(ef);
                    break;
            }

        }
        if( !oldTargets.isEmpty()&&!stepsForward.isEmpty()){ // Restore old requests
            oldTargets.forEach( wr->addTarget(wr) );
        }
        if( !lastStep().map(AbstractForward::noTargets).orElse(false) || hasLabel) {
            if (customs == null || customs.isEmpty()) { // If no custom sources
                dQueue.add(Datagram.system(this.src).writable(stepsForward.get(0)));
            } else {// If custom sources
                if( !stepsForward.isEmpty()) // and there are steps
                    targets.add(stepsForward.get(0));
                customs.forEach(CustomSrc::start);
            }
        }
        customs.trimToSize();
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
            lastStep().get().addTarget(f);
        }
    }
    public boolean debugStep( int step, Writable wr ){
        if( step >= stepsForward.size() || wr==null )
            return false;
        for( var ab : stepsForward )
            ab.removeTarget(wr);
        if( !customs.isEmpty() ){
            if( step == -1){
                targets.add(wr);
                customs.forEach(CustomSrc::start);
            }else if( step < stepsForward.size()){
                stepsForward.get(step).addTarget(wr);
            }else{
                return false;
            }
        }else if(step !=-1 && step <stepsForward.size() ){
            stepsForward.get(step).addTarget(wr);
        }else{
            return false;
        }

        return true;
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
        if( customs.isEmpty() ){
            if( stepsForward.isEmpty())
                return "Nothing in the path yet";
            return " gives the data from "+stepsForward.get(stepsForward.size()-1).getID();
        }
        var join = new StringJoiner("\r\n");
        customs.forEach(c->join.add(c.toString()));

        for( int a=0;a<stepsForward.size();a++){
            join.add("   -> "+stepsForward.get(a).toString());
        }

        return join.toString();
    }
    public void addTarget(Writable wr){

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

            if( lastStep().map(ls->ls.noTargets()).orElse(true) ){ // if the final step has no more targets, stop the first step
                customs.forEach(CustomSrc::stop);
            }
        }
    }

    public void writeData(){

        targets.removeIf( x -> !x.isConnectionValid());
        customs.forEach( CustomSrc::write );

        if( targets.isEmpty() ){
            customs.forEach(CustomSrc::stop);
        }
    }
    private class CustomSrc{
        String data;
        SRCTYPE srcType;
        long intervalMillis;
        ScheduledFuture future;
        ArrayList<String> buffer;
        ArrayList<Path> files;
        int lineCount=1;
        int multiLine=1;

        public CustomSrc( String data, String type, long intervalMillis){
            this.data =data;
            this.intervalMillis=intervalMillis;
            var spl = type.split(":");
            switch(spl[0]){
                case "rtvals": srcType=SRCTYPE.RTVALS; break;
                case "cmd": srcType=SRCTYPE.CMD; break;
                case "plain": srcType=SRCTYPE.PLAIN; break;
                case "file":
                    srcType=SRCTYPE.FILE;
                    files=new ArrayList<>();
                    if( !Path.of(data).isAbsolute()) {
                        this.data = workPath.resolve(data).toString();
                    }
                    if( Files.isDirectory( Path.of(this.data))){
                        try{
                            Files.list(Path.of(this.data)).forEach(files::add);
                        } catch (IOException e) {
                            Logger.error(e);
                        }
                    }else{
                        files.add(Path.of(this.data));
                    }
                    buffer=new ArrayList<>();
                    if(spl.length==2)
                        multiLine = NumberUtils.toInt(spl[1]);
                    break;
                default:
                    Logger.error(id+ "(pf) -> no valid srctype '"+type+"'");
            }
        }
        public void start(){
            if( future==null || future.isDone())
                future = nettyGroup.scheduleAtFixedRate(()-> write(),intervalMillis,intervalMillis, TimeUnit.MILLISECONDS);
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
                case CMD:; targets.forEach( t->dQueue.add( Datagram.build(data).label("telnet").writable(t))); break;
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
            return "Writes '"+data+"' every "+TimeTools.convertPeriodtoString(intervalMillis,TimeUnit.MILLISECONDS);
        }
    }
}
