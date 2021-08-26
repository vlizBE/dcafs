package io.forward;

import util.data.DataProviding;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PathForward {

    private String src = "";

    private final ArrayList<Writable> targets = new ArrayList<>();
    private ArrayList<CustomSrc> customs;

    DataProviding dataProviding;
    BlockingQueue<Datagram> dQueue;
    EventLoopGroup nettyGroup;

    String id;
    ArrayList<AbstractForward> stepsForward;
    enum SRCTYPE {REG,PLAIN,RTVALS,CMD}

    public PathForward(DataProviding dataProviding, BlockingQueue<Datagram> dQueue, EventLoopGroup nettyGroup ){
        this.dataProviding = dataProviding;
        this.dQueue=dQueue;
        this.nettyGroup=nettyGroup;
    }
    public String getID(){
        return id;
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
            oldTargets.addAll( lastStep().getTargets() );
            stepsForward.clear();
        }

        id = XMLtools.getStringAttribute(pathEle,"id","");
        String imp = XMLtools.getStringAttribute(pathEle,"import","");
        String delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");;
        String src = XMLtools.getStringAttribute(pathEle,"src","");

        if( XMLtools.getFirstChildByTag(pathEle,"customsrc")==null) {
            useRegularSrc(src);
        }

        if( !imp.isEmpty() ) {
            var p = XMLfab.getRootChildren(Path.of(imp),"dcafs","path").findFirst();
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
                    if( !step.hasAttribute("label"))
                        step.setAttribute("label","generic:"+next.getAttribute("id"));
                }
                if(next.getTagName().equalsIgnoreCase("valmap")){
                    if( !step.hasAttribute("label"))
                        step.setAttribute("label","valmap:"+next.getAttribute("id"));
                }
            }
            boolean lastGenMap = false;
            if( !stepsForward.isEmpty() ) {
                var prev = steps.get(a - 1);
                lastGenMap = prev.getTagName().equalsIgnoreCase("generic")
                                ||prev.getTagName().equalsIgnoreCase("valmap");
                hasLabel=true;
            }

            // If this step doesn't have a delimiter, alter it
            if( !step.hasAttribute("delimiter")&& !delimiter.isEmpty())
                step.setAttribute("delimiter",delimiter);

            if( !step.hasAttribute("id"))
                step.setAttribute("id","step_"+stepsForward.size());

            src = XMLtools.getStringAttribute(step,"src","");
            if( stepsForward.isEmpty() && !src.isEmpty())
                step.setAttribute("src","");
            switch( step.getTagName() ){
                case "filter":
                    FilterForward ff = new FilterForward( step, dQueue );
                    if( lastff != null && (!(lastStep() instanceof FilterForward) || lastGenMap)) {
                        lastff.addReverseTarget(ff);
                    }else if( !stepsForward.isEmpty() ){
                        lastStep().addTarget(ff);
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
        if( hasLabel ) {
            if ( customs==null||customs.isEmpty()) {
                dQueue.add(Datagram.system(this.src).writable(stepsForward.get(0)));
            } else {
                customs.forEach(CustomSrc::start);
            }
        }
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
            lastStep().addTarget(f);
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
    private AbstractForward lastStep(){
        return stepsForward.get(stepsForward.size()-1);
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
        if( customs==null)
            customs = new ArrayList<>();
        customs.add( new CustomSrc(data,type,TimeTools.parsePeriodStringToMillis(interval)) );
    }
    public void useRegularSrc( String src){
        this.src =src;
    }
    public String toString(){
        if( customs==null||customs.isEmpty() ){
            if( stepsForward.isEmpty())
                return "Nothing in the path yet";
            return " gives the data from "+stepsForward.get(stepsForward.size()-1).getID();
        }
        var join = new StringJoiner("\r\n");
        if( customs!=null)
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

            if( lastStep().noTargets() ){ // if the final step has no more targets, stop the first step
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

        public CustomSrc( String data, String type, long intervalMillis){
            this.data =data;
            this.intervalMillis=intervalMillis;
            switch(type){
                case "rtvals": srcType=SRCTYPE.RTVALS; break;
                case "src": srcType=SRCTYPE.CMD; break;
                case "plain": srcType=SRCTYPE.PLAIN; break;
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
            }
        }
        public String toString(){
            return "Writes '"+data+"' every "+TimeTools.convertPeriodtoString(intervalMillis,TimeUnit.MILLISECONDS);
        }
    }
}
