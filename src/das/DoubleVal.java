package das;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import worker.Datagram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

public class DoubleVal {

    String group="";
    String name="";
    double value;
    String unit="";

    /* Keep Time */
    long timestamp;
    boolean keepTime=false;

    /* History */
    ArrayList<Double> history;
    boolean keepHistory=false;

    /* Triggering */
    ArrayList<TriggeredCmd> triggered;
    BlockingQueue<Datagram> dQueue;

    public DoubleVal(){}

    public DoubleVal(double val){
        setValue(val);
    }

    public void setValue( double val){
        this.value=val;
        /* Keep history of passed values */
        if( keepHistory ) {
            history.add(val);
            if( history.size()>100)
                history.remove(0);
        }
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now().toEpochMilli();

        /* Respond to thriggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            // Execute all the triggers, only if it's the first time
            triggered.stream()
                    .filter(tc -> tc.comp.apply(val)&&!tc.triggered)
                    .forEach(tc -> dQueue.add(Datagram.system(tc.getCmd())));
            // Reset the triggers that are no longer valid
            triggered.stream().filter(tc -> !tc.comp.apply(val)&&tc.triggered).forEach(tc -> tc.resetTrigger());
        }
    }

    public double getValue(){
        return getValue();
    }
    public static DoubleVal newVal(String group, String name){
        return new DoubleVal().group(group).name(name);
    }
    public DoubleVal name(String name){
        this.name=name;
        return this;
    }
    public String getName(){
        return name;
    }
    public DoubleVal group(String group){
        this.group=group;
        return this;
    }
    public String getGroup(){
        return group;
    }
    public DoubleVal unit(String unit){
        this.unit=unit;
        return this;
    }
    public DoubleVal enableHistory(){
        keepHistory=true;
        history=new ArrayList<>();
        return this;
    }
    public DoubleVal enableTimekeeping(){
        keepTime=true;
        return this;
    }
    public DoubleVal enableTriggeredCmds(BlockingQueue<Datagram> dQueue){
        this.dQueue=dQueue;
        return this;
    }
    public DoubleVal addTriggeredCmd(String cmd, String trigger){
        if( dQueue==null)
            Logger.error("Tried to add cmd "+cmd+" but dQueue still null");
        if( triggered==null)
            triggered = new ArrayList<>();

        triggered.add(new TriggeredCmd(cmd,trigger));
        return this;
    }
    public String toString(){
        return value+unit;
    }
    private class TriggeredCmd{
        String cmd="";
        String ori="";
        Function<Double,Boolean> comp;
        boolean triggered=false;

        public TriggeredCmd( String cmd, String trigger){
            this.cmd=cmd;
            this.ori=trigger;
            var split = MathUtils.splitCompare("i0"+trigger);
            this.comp=MathUtils.getSingleCompareFunction(NumberUtils.toDouble(split[2]),split[1]);
        }
        public String getCmd(){
            Logger.info("Triggered for "+(group.isEmpty()?"":group+"_")+name+ori+" => "+cmd);
            triggered=true;
            return cmd;
        }
        private void resetTrigger(){
            Logger.info("Trigger reset for "+(group.isEmpty()?"":group+"_")+name+ori+" => "+cmd);
            triggered=false;
        }
    }
}
