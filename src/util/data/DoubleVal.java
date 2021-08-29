package util.data;

import org.tinylog.Logger;
import util.math.MathUtils;
import util.task.Task;
import util.tools.TimeTools;
import util.tools.Tools;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class DoubleVal extends AbstractVal implements NumericVal{

    private double value;

    private double defVal=Double.NaN;

    private String unit="";
    private int digits=-1;

    /* Min max*/
    private double min=Double.MAX_VALUE;
    private double max=Double.MIN_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Double> history;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;
    enum TRIGGERTYPE {ALWAYS,CHANGED,STDEV,COMP};

    private DoubleVal(){}

    /**
     * Constructs a new DoubleVal with the given group and name
     *
     * @param group The group this DoubleVal belongs to
     * @param name The name for the DoubleVal
     * @return The constructed DoubleVal
     */
    public static DoubleVal newVal(String group, String name){
        return new DoubleVal().group(group).name(name);
    }

    /**
     * Construct a new DoubleVal with the given id which is the combination of the group and name separated with an
     * underscore
     * @param combined group + underscore + name = id
     * @return the constructed DoubleVal
     */
    public static DoubleVal newVal(String combined){
        int us = combined.indexOf("_");

        if( us != -1) { // If this contains an underscore, split it
            return new DoubleVal().group(combined.substring(0,us)).name(combined.substring(us+1));
        }
        return new DoubleVal().name(combined);// If no underscore, this means no group id given
    }
    /* ********************************* Constructing ************************************************************ */

    /**
     * Set the name, this needs to be unique within the group
     * @param name The new name
     * @return This object with altered name
     */
    public DoubleVal name(String name){
        this.name=name;
        return this;
    }
    /**
     * Set the group, multiple DoubleVals can share a group id
     * @param group The new group
     * @return This object with altered group
     */
    public DoubleVal group(String group){
        this.group=group;
        return this;
    }

    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     * @return This object with updated unit
     */
    public DoubleVal unit(String unit){
        this.unit=unit;
        return this;
    }

    /**
     * Update the value, this will -depending on the options set- also update related variables
     * @param val The new value
     * @return This object after updating the value etc
     */
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

    /**
     * Set the default value, this will be used as initial value and after a reset
     * @param defVal The default value
     * @return This object after altering the defValue if not NaN
     */
    public DoubleVal defValue( double defVal){
        if( !Double.isNaN(defVal) ) { // If the given value isn't NaN
            this.defVal = defVal;
            value=defVal;
        }
        return this;
    }

    /**
     * Reset this DoubleVal to its default value
     */
    @Override
    public void reset(){
        value=defVal;
        keepMinMax=false;
        digits=-1;
        if( triggered!=null)
            triggered.clear();
        super.reset();
    }
    /**
     * Set the amount of digits to scale to using half up rounding
     * @param fd The amount of digits
     * @return This object after setting the digits
     */
    public DoubleVal fractionDigits(int fd){
        this.digits=fd;
        return this;
    }

    /**
     * Enable keeping track of the max and min values received since last reset
     * @return This object but with min max enabled
     */
    public DoubleVal keepMinMax(){
        keepMinMax=true;
        return this;
    }

    @Override
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
    }

    /**
     * Tries to add a cmd with given trigger, will warn if no valid queue is present to actually execute them
     * @param cmd The cmd to trigger, $ will be replaced with the current value
     * @param trigger The trigger which is either a comparison between the value and another fixed value fe. above 10 or
     *                'always' to trigger on every update or 'changed' to trigger only on a changed value
     */
    public boolean addTriggeredCmd(String trigger,String cmd ){
        if( dQueue==null)
            Logger.error(id() + "(dv)-> Tried to add cmd "+cmd+" but dQueue still null");
        if( triggered==null)
            triggered = new ArrayList<>();

        var td = new TriggeredCmd(cmd,trigger);
        if( td.isInvalid()) {
            Logger.error(id()+" (dv)-> Failed to convert trigger: "+trigger);
            return false;
        }
        triggered.add( new TriggeredCmd(cmd,trigger) );
        return true;
    }
    public boolean hasTriggeredCmds(){
        return triggered!=null&& !triggered.isEmpty();
    }
    /* ***************************************** U S I N G ********************************************************** */
    public String unit(){ return unit; }

    /**
     *
     * @return The amount of digits to scale to using rounding half up
     */
    public int scale(){ return digits; }

    /**
     * @return Get the current value as a double
     */
    public double value(){ return value; }

    /**
     * Update the value
     * @param val The new value
     */
    public void updateValue(double val){
        value(val);
    }

    /**
     * Get the value but as a BigDecimal instead of double
     * @return The BigDecimal value of this object
     */
    public BigDecimal toBigDecimal(){
        return BigDecimal.valueOf(value);
    }

    /**
     * Calculate the average of all the values stored in the history
     * @return The average of the stored values
     */
    public double getAvg(){
        double total=0;
        if(history!=null){
            for( var h : history){
                total+=h;
            }
        }else{
            Logger.warn(id() + "(dv)-> Asked for the average of "+(group.isEmpty()?"":group+"_")+name+" but no history kept");
            return value;
        }
        return Tools.roundDouble(total/history.size(),digits==-1?3:digits);
    }

    /**
     * Get the current Standard Deviation based on the history rounded to digits + 2 or 5 digits if no scale was set
     * @return The calculated standard deviation or NaN if either no history is kept or the history hasn't reached
     * the full size yet.
     */
    public double getStdev(){
        if( history==null) {
            Logger.error(id()+" (dv)-> Can't calculate standard deviation without history");
            return Double.NaN;
        }else if( history.size() != keepHistory){
            return Double.NaN;
        }
        return MathUtils.calcStandardDeviation(history,digits==-1?5:digits+2);
    }
    /**
     * Compare two DoubleVal's based on their values
     * @param dv The DoubleVal to compare to
     * @return True if they have the same value
     */
    public boolean equals( DoubleVal dv){
        return Double.compare(value,dv.value())==0;
    }

    /**
     * Compare with a double
     * @param d The double to compare to
     * @return True if they are equal
     */
    public boolean equals( double d){
        return Double.compare(value,d)==0;
    }
    public String toString(){
        String line = value+unit;
        if( keepMinMax && max!=Double.MIN_VALUE )
            line += " (Min:"+min+unit+", Max: "+max+unit+")";
        if( keepHistory>0 && !history.isEmpty()) {
            line = (line.endsWith(")") ? line.substring(0, line.length() - 1) + ", " : line + " (") + "Avg:" + getAvg() + unit + ")";
            if( history.size()==keepHistory){
                line = line.substring(0,line.length()-1) +" StDev: "+getStdev()+unit+")";
            }
        }
        if( keepTime ) {
            if (timestamp != null) {
                line += " Age: " + TimeTools.convertPeriodtoString(Duration.between(timestamp, Instant.now()).getSeconds(), TimeUnit.SECONDS);
            } else {
                line += " Age: No updates yet.";
            }
        }
        return line;
    }

    /**
     * TriggeredCmd is a way to run cmd's if the new value succeeds in either the compare or meets the other options
     * Cmd: if it contains a '$' this will be replaced with the current value
     * Current triggers:
     * - Double comparison fe. above 5 and below 10
     * - always, means always issue the cmd independent of the value
     * - changed, only issue the command if the value has changed
     */
    private class TriggeredCmd{
        String cmd; // The cmd to issue
        String ori; // The compare before it was converted to a function (for toString purposes)
        TRIGGERTYPE type;
        Function<Double,Boolean> comp; // The compare after it was converted to a function
        boolean triggered=false; // The last result of the comparison

        /**
         * Create a new TriggeredCmd with the given cmd and trigger, doesn't set the cmd of it failed to convert the trigger
         * @param cmd The cmd to execute when triggered
         * @param trigger Either 'always' if the cmd should be done on each update, or 'changed' if the value changed
         *                or a single or double compare fe 'above 10' or 'below 5 or above 50' etc
         */
        public TriggeredCmd( String cmd, String trigger){
            this.cmd=cmd;
            this.ori=trigger;
            type=TRIGGERTYPE.COMP;
            switch( trigger ){
                case "": case "always": type=TRIGGERTYPE.ALWAYS; break;
                case "changed": type=TRIGGERTYPE.CHANGED; break;
                default:
                    if( trigger.contains("stdev")) {
                        type = TRIGGERTYPE.STDEV;
                        trigger=trigger.replace("stdev","");
                    }
                    comp=MathUtils.parseSingleCompareFunction(trigger);
                    if( comp==null){
                        this.cmd="";
                    }
                    break;
            }
        }
        public boolean isInvalid(){
            return cmd.isEmpty();
        }
        public void apply( double val ){
            if( dQueue==null) {
                Logger.error(id()+" (dv)-> Tried to check for a trigger without a dQueue");
                return;
            }
            boolean ok;
            switch( type ){
                case ALWAYS:
                    dQueue.add(Datagram.system(cmd.replace("$",""+val)));
                    return;
                case CHANGED:
                    if( val != value )
                        dQueue.add(Datagram.system(cmd.replace("$",""+val)));
                    return;
                case COMP:
                    ok = comp.apply(val);
                    break;
                case STDEV:
                    double sd = getStdev();
                    if( Double.isNaN(sd))
                        return;
                    ok = comp.apply(getStdev()); // Compare with the Standard Deviation instead of value
                    break;
                default:
                    Logger.error(id()+" (dv)-> Somehow an invalid trigger sneaked in... ");
                    return;
            }
            if( !triggered && ok ){
                dQueue.add(Datagram.system(cmd.replace("$",""+val)));
            }else if( triggered && !ok){
                triggered=false;
            }
        }
    }
}
