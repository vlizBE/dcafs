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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PathForward {

    String type="";
    String src = "";
    long millis = 0;

    protected final ArrayList<Writable> targets = new ArrayList<>();

    ScheduledFuture future;
    DataProviding dataProviding;
    BlockingQueue<Datagram> dQueue;
    EventLoopGroup nettyGroup;

    String id;
    ArrayList<AbstractForward> stepsForward;

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

        if( future!=null) { // if any future is active, stop it
            if (!future.isCancelled())
                future.cancel(true);
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

        for( int a=0;a<steps.size();a++  ){
            Element step = steps.get(a);

            if(step.getTagName().equalsIgnoreCase("customsrc")){
                useCustomSrc( step.getTextContent(),
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
            }

            // If this step doesn't have a delimiter, alter it
            if( !step.hasAttribute("delimiter")&& !delimiter.isEmpty())
                step.setAttribute("delimiter",delimiter);

            if( !step.hasAttribute("id"))
                step.setAttribute("id",id+"_"+a);

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
                    if( !stepsForward.isEmpty() )
                        lastStep().addTarget(mf);
                    stepsForward.add(mf);
                    break;
                case "editor":
                    var ef = new EditorForward( step,dQueue,dataProviding );
                    if( !stepsForward.isEmpty() )
                        lastStep().addTarget(ef);
                    stepsForward.add(ef);
                    break;
            }
        }
        if( !oldTargets.isEmpty()&&!stepsForward.isEmpty()){ // Restore old requests
            oldTargets.forEach( wr->addTarget(wr) );
        }
        if( !stepsForward.isEmpty() && !type.isEmpty()){
            targets.add(stepsForward.get(0));
        }else{

        }
    }
    public boolean debugStep( int step, Writable wr ){
        if( step >= stepsForward.size() || step == -1 || wr==null )
            return false;
        for( var ab : stepsForward )
            ab.removeTarget(wr);
        stepsForward.get(step).addTarget(wr);
        return true;
    }
    private AbstractForward lastStep(){
        return stepsForward.get(stepsForward.size()-1);
    }
    public void useCustomSrc( String src, String interval, String type){
        this.src =src;
        this.millis = TimeTools.parsePeriodStringToMillis(interval);
        this.type=type;
    }
    public void useRegularSrc( String src){
        this.src =src;
        type="";
    }
    public String toString(){
        if( type.isEmpty() ){
            return " gives the data from "+stepsForward.get(stepsForward.size()-1).getID();
        }
        return "'"+ src +"' send to "+targets.size()+" targets every "+TimeTools.convertPeriodtoString(millis, TimeUnit.MILLISECONDS);
    }
    public void addTarget(Writable wr){
        if( stepsForward.isEmpty() ) {
            if (!targets.contains(wr))
                targets.add(wr);
        }else{
            // Add a target to the last step
            stepsForward.get(stepsForward.size()-1).addTarget(wr);
        }
        // Now enable the source
        if( !type.isEmpty()) {
            start();
        }else{
            dQueue.add( Datagram.system(src).writable(stepsForward.get(0)));
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
               if( !type.isEmpty() ) {
                   if(future!=null)
                       future.cancel(true);
               }
            }
        }
        // Stop asking data if no more target
    }
    public void start(){
        if( future==null || future.isDone())
            future = nettyGroup.scheduleAtFixedRate(()-> writeData(),millis,millis, TimeUnit.MILLISECONDS);
    }
    public void writeData(){

        targets.removeIf( x -> !x.isConnectionValid());
        switch( type){
            case "cmd":; targets.forEach( t->dQueue.add( Datagram.build(src).label("telnet").writable(t))); break;
            case "rtvals":
                var data = dataProviding.parseRTline(src,"-999");
                targets.forEach( x -> x.writeLine(data));
                break;
            default:
            case "plain": targets.forEach( x -> x.writeLine(src)); break;
        }

        if( targets.isEmpty() ){
            future.cancel(true);
        }
    }
}
