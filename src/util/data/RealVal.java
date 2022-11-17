package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class RealVal extends AbstractVal implements NumericVal{

    private double value;

    private double defVal=Double.NaN;

    private int digits=-1;
    private boolean abs=false;

    /* Min max*/
    private double min=Double.MAX_VALUE;
    private double max=-1*Double.MAX_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Double> history;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;

    private RealVal(){}

    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static RealVal newVal(String group, String name){
        return new RealVal().group(group).name(name);
    }

    /* ********************************* Constructing ************************************************************ */

    /**
     * Create a new Realval based on a rtval real node
     * @param rtval The node
     * @param group The group the node is found in
     * @param defReal The global default real
     * @return The created node, still needs dQueue set
     */
    public static RealVal build( Element rtval, String group, double defReal){
        String name = XMLtools.getStringAttribute(rtval,"name","");
        name = XMLtools.getStringAttribute(rtval,"id",name);

        if( name.isEmpty()){
            Logger.error("Tried to create a RealVal without id/name");
            return null;
        }
        return RealVal.newVal(group,name).alter(rtval,defReal);
    }

    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     * @param defReal The global default
     */
    public RealVal alter( Element rtval,double defReal ){
        reset();
        unit(XMLtools.getStringAttribute(rtval, "unit", ""))
                .scale(XMLtools.getIntAttribute(rtval, "scale", -1))
                .defValue(XMLtools.getDoubleAttribute(rtval, "default", defReal))
                .defValue(XMLtools.getDoubleAttribute(rtval, "def", defVal));
        String options = XMLtools.getStringAttribute(rtval, "options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "minmax" -> keepMinMax();
                case "time" -> keepTime();
                case "scale" -> scale(NumberUtils.toInt(arg[1], -1));
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
                case "abs" -> enableAbs();
            }
        }
        for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        }
        return this;
    }
    /**
     * Set the name, this needs to be unique within the group
     * @param name The new name
     * @return This object with altered name
     */
    public RealVal name(String name){
        this.name=name;
        return this;
    }
    /**
     * Set the group, multiple DoubleVals can share a group id
     * @param group The new group
     * @return This object with altered group
     */
    public RealVal group(String group){
        this.group=group;
        return this;
    }

    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     * @return This object with updated unit
     */
    public RealVal unit(String unit){
        this.unit=unit;
        return this;
    }

    /**
     * Update the value, this will -depending on the options set- also update related variables
     * @param val The new value
     * @return This object after updating the value etc
     */
    public RealVal value(double val ){

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
        if(abs)
            val = Math.abs(val);

        if( digits != -1) {
            value = Tools.roundDouble(val, digits);
        }else{
            value=val;
        }
        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            double v = val;
            // Execute all the triggers, only if it's the first time
            triggered.forEach(tc -> tc.apply(v));
        }

        if( targets!=null ){
            double v = val;
            targets.forEach( wr -> wr.writeLine(id()+":"+v));
        }
        return this;
    }

    /**
     * Set the default value, this will be used as initial value and after a reset
     * @param defVal The default value
     * @return This object after altering the defValue if not NaN
     */
    public RealVal defValue(double defVal){
        if( !Double.isNaN(defVal) ) { // If the given value isn't NaN
            this.defVal = defVal;
            if( Double.isNaN(value))
                value=defVal;
        }
        return this;
    }

    /**
     * Reset this RealVal to its default value
     */
    @Override
    public void reset(){
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
    public RealVal scale(int fd){
        this.digits=fd;
        return this;
    }

    /**
     * Enable keeping track of the max and min values received since last reset
     * @return This object but with min max enabled
     */
    public RealVal keepMinMax(){
        keepMinMax=true;
        return this;
    }

    @Override
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    public void enableAbs(){
        abs=true;
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
    private void storeTriggeredCmds(XMLfab fab){
        if( triggered==null)
            return;
        for( var tc : triggered ){
            switch (tc.type) {
                case ALWAYS -> fab.addChild("cmd", tc.cmd);
                case CHANGED -> fab.addChild("cmd", tc.cmd).attr("when", "changed");
                case STDEV, COMP -> fab.addChild("cmd", tc.cmd).attr("when", tc.ori);
            }
        }
    }
    public String getID(){
        if( group.isEmpty())
            return name;
        return group+"_"+name;
    }
    /* ***************************************** U S I N G ********************************************************** */

    /**
     * Store the setup of this val in the settings.xml
     * @param fab The fab to work with, with the rtvals node as parent
     * @return True when
     */
    public boolean storeInXml( XMLfab fab ){

        fab.alterChild("group","id",group)
                .down(); // Go down in the group

        if( fab.hasChild("real","name",name).isEmpty() ) { // If this one isn't present
            fab.addChild("real").attr("name", name);
        }else {
            fab.alterChild("real", "name", name);
        }
        fab.attr("unit",unit);
        if( digits !=-1)
            fab.attr("scale",digits);
        var opts = getOptions();
        if( !opts.isEmpty())
            fab.attr("options",opts);
        storeTriggeredCmds(fab.down());
        fab.up();
        if( !group.isEmpty())
            fab.up(); // Go back up to rtvals
        fab.build();
        return true;
    }

    /**
     * Get a delimited string with all the used options
     * @return The options in a listing or empty if none are used
     */
    private String getOptions(){
        var join = new StringJoiner(",");
        if( keepTime )
            join.add("time");
        if( keepHistory>0)
            join.add("history:"+keepHistory);
        if( keepMinMax )
            join.add("minmax");
        if( order !=-1 )
            join.add("order:"+order);
        if( abs )
            join.add("abs");
        return join.toString();
    }

    /**
     *
     * @return The amount of digits to scale to using rounding half up
     */
    public int scale(){ return digits; }

    /**
     * @return Get the current value as a double
     */
    public double value(){ return value; }
    public double value( String type ){
        return switch( type ){
            case "stdev", "stdv"-> getStdev();
            case "avg", "average" ->  getAvg();
            case "min" -> min();
            case "max" -> max();
            default -> value();
        };
    }
    public int intValue(){ return ((Double)value).intValue(); }
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
        try {
            return BigDecimal.valueOf(value);
        }catch(NumberFormatException e){
            Logger.warn(id()+" hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }
    public double min(){
        return min;
    }
    public double max(){
        return max;
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
     * Compare two RealVal's based on their values
     * @param dv The RealVal to compare to
     * @return True if they have the same value
     */
    public boolean equals( RealVal dv){
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
    public String asValueString(){
        return value+unit;
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
            switch (trigger) {
                case "", "always" -> type = TRIGGERTYPE.ALWAYS;
                case "changed" -> type = TRIGGERTYPE.CHANGED;
                default -> {
                    if (trigger.contains("stdev")) {
                        type = TRIGGERTYPE.STDEV;
                        trigger = trigger.replace("stdev", "");
                    }
                    comp = MathUtils.parseSingleCompareFunction(trigger);
                    if (comp == null) {
                        this.cmd = "";
                    }
                }
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
            switch (type) {
                case ALWAYS -> {
                    dQueue.add(Datagram.system(cmd.replace("$", "" + val)));
                    return;
                }
                case CHANGED -> {
                    if (val != value)
                        dQueue.add(Datagram.system(cmd.replace("$", "" + val)));
                    return;
                }
                case COMP -> ok = comp.apply(val);
                case STDEV -> {
                    double sd = getStdev();
                    if (Double.isNaN(sd))
                        return;
                    ok = comp.apply(getStdev()); // Compare with the Standard Deviation instead of value
                }
                default -> {
                    Logger.error(id() + " (dv)-> Somehow an invalid trigger sneaked in... ");
                    return;
                }
            }
            if( !triggered && ok ){
                dQueue.add(Datagram.system(cmd.replace("$",""+val)));
            }else if( triggered && !ok){
                triggered=false;
            }
        }
    }
}
