package util.data;

import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class DoubleVal implements NumericVal{

    private String group="";
    private String name="";

    private double value;

    private double defVal=Double.NaN;

    private String unit="";
    private int digits=-1;

    /* Keep Time */
    private Instant timestamp;
    private boolean keepTime=false;

    /* Min max*/
    private double min=Double.MAX_VALUE;
    private double max=Double.MIN_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Double> history;
    private int keepHistory=0;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;
    private BlockingQueue<Datagram> dQueue;

    public DoubleVal(){}

    public DoubleVal(double val){
        setValue(val);
    }

    public static DoubleVal newVal(String group, String name){
        return new DoubleVal().group(group).name(name);
    }
    public static DoubleVal newVal(String combined){
        String[] spl = combined.split("_");
        if( spl.length==2) {
            return new DoubleVal().group(spl[0]).name(spl[1]);
        }else if( spl.length>2){
            String name = spl[1]+"_"+spl[2];
            for( int a=3;a<spl.length;a++)
                name+="_"+spl[a];
            return new DoubleVal().group(spl[0]).name(name);
        }
        return new DoubleVal().name(spl[0]);
    }
    /* ********************************* Constructing ************************************************************ */
    public DoubleVal name(String name){
        this.name=name;
        return this;
    }
    public DoubleVal group(String group){
        this.group=group;
        return this;
    }

    public DoubleVal unit(String unit){
        this.unit=unit;
        return this;
    }
    public DoubleVal value( double val){

        /* Keep history of passed values */
        if( keepHistory!=0 ) {
            history.add(val);
            if( history.size()>keepHistory)
                history.remove(0);
        }
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();

        /* Keep min max */
        if( keepMinMax ){
            min = Math.min(min,val);
            max = Math.max(max,val);
        }
        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            // Execute all the triggers, only if it's the first time
            triggered.stream().forEach(tc -> tc.apply(val));
        }
        if( digits != -1) {
            value = Tools.roundDouble(val, digits);
        }else{
            value=val;
        }
        return this;
    }
    public DoubleVal defValue( double defVal){
        if( !Double.isNaN(defVal) ) {
            this.defVal = defVal;
            value=defVal;
        }
        return this;
    }

    public DoubleVal fractionDigits(int fd){
        this.digits=fd;
        return this;
    }
    public DoubleVal enableHistory(int count){
        if(count==-1)
           return this;
        keepHistory=count;
        history=new ArrayList<>();
        return this;
    }
    public DoubleVal keepTime(){
        keepTime=true;
        return this;
    }
    public DoubleVal keepMinMax(){
        keepMinMax=true;
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

        triggered.add( new TriggeredCmd(cmd,trigger) );
        return this;
    }

    /* ***************************************** U S I N G ********************************************************** */
    public String unit(){ return unit; }
    public int scale(){ return digits; }
    public String getGroup(){
        return group;
    }
    public String getName(){
        return name;
    }
    public String getID(){
        return group.isEmpty()?name:(group+"_"+name);
    }
    public void setValue( double val){
        value(val);
    }
    public double getValue(){
        return value;
    }
    public BigDecimal toBigDecimal(){
        return BigDecimal.valueOf(value);
    }
    public double getAvg(){
        double total=0;
        if(history!=null){
            for( var h : history){
                total+=h;
            }
        }else{
            Logger.warn("Asked for the average of "+name+" but no history kept");
            return value;
        }
        return Tools.roundDouble(total/history.size(),digits==-1?3:digits);
    }
    public boolean equals( DoubleVal dv){
        return Double.compare(value,dv.getValue())==0;
    }
    public boolean equals( double d){
        return Double.compare(value,d)==0;
    }
    public String toString(){
        String line = value+unit;
        if( keepMinMax )
            line += " (Min:"+min+unit+", Max: "+max+unit+")";
        if( keepHistory>0)
            line = (line.endsWith(")")?line.substring(0,line.length()-1)+", ":line+" (")+"Avg:"+getAvg()+unit+")";
        if( keepTime )
            line += " Age: "+ TimeTools.convertPeriodtoString(Duration.between(timestamp,Instant.now()).getSeconds(), TimeUnit.SECONDS);
        return line;
    }

    private class TriggeredCmd{
        String cmd="";
        String ori="";
        Function<Double,Boolean> comp;
        boolean triggered=false;

        public TriggeredCmd( String cmd, String trigger){
            this.cmd=cmd;
            this.ori=trigger;
            if( !trigger.isEmpty() && !trigger.equalsIgnoreCase("always") ){
                comp=MathUtils.parseSingleCompareFunction(trigger);
            }
        }
        public String getCmd(){
            Logger.info("Triggered for "+(group.isEmpty()?"":group+"_")+name+" "+ori+" => "+cmd);
            triggered=true;
            return cmd;
        }
        private void resetTrigger(){
            Logger.info("Trigger reset for "+(group.isEmpty()?"":group+"_")+name+" "+ori+" => "+cmd);
            triggered=false;
        }
        public boolean reset( double val ){
            if( triggered ){
                triggered = comp.apply(val);
            }
            return true;
        }
        public void apply( double val ){
            if( ori.isEmpty() || ori.equalsIgnoreCase("always") ) { // always run this cmd
                dQueue.add(Datagram.system(cmd.replace("$",""+val)));
            }else if( ori.equalsIgnoreCase("changed")) {// run this cmd if the value changed
                if( val != value )
                    dQueue.add(Datagram.system(cmd.replace("$",""+val)));
            }else{
                boolean ok = comp.apply(val);
                if( !triggered && ok ){
                    dQueue.add(Datagram.system(cmd.replace("$",""+val)));
                }else if( triggered && !ok){
                    triggered=false;
                }
            }
        }
    }
}
