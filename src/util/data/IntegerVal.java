package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class IntegerVal extends AbstractVal implements NumericVal{
    private int value;

    private int defVal=0;
    private boolean abs=false;

    /* Min max*/
    private int min=Integer.MAX_VALUE;
    private int max=Integer.MIN_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Integer> history;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;

    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static IntegerVal newVal(String group, String name){
        return new IntegerVal().group(group).name(name);
    }

    public String getID(){
        if( group.isEmpty())
            return name;
        return group+"_"+name;
    }
    /* ********************************* Constructing ************************************************************ */
    /**
     * Create a new IntegerVal based on a rtval real node
     * @param rtval The node
     * @param group The group the node is found in
     * @param defVal The global default real
     * @return The created node, still needs dQueue set
     */
    public static IntegerVal build(Element rtval, String group, int defVal){
        String name = XMLtools.getStringAttribute(rtval,"name","");
        name = XMLtools.getStringAttribute(rtval,"id",name);
        if( name.isEmpty())
            name = rtval.getTextContent();

        return IntegerVal.newVal(group,name).alter(rtval,defVal);
    }

    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     * @param defInt The global default
     */
    public IntegerVal alter( Element rtval,int defInt ){
        reset();
        unit(XMLtools.getStringAttribute(rtval, "unit", "")).
                defValue( XMLtools.getIntAttribute(rtval, "default", defInt) ).
                defValue( XMLtools.getIntAttribute(rtval, "default", defVal) );
        String options = XMLtools.getStringAttribute(rtval, "options", "");
        value=defVal;

        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "minmax" -> keepMinMax();
                case "time" -> keepTime();
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
    public IntegerVal name(String name){
        this.name=name;
        return this;
    }
    /**
     * Set the group, multiple DoubleVals can share a group id
     * @param group The new group
     * @return This object with altered group
     */
    public IntegerVal group(String group){
        this.group=group;
        return this;
    }

    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     * @return This object with updated unit
     */
    public IntegerVal unit(String unit){
        this.unit=unit;
        return this;
    }

    /**
     * Update the value, this will -depending on the options set- also update related variables
     * @param val The new value
     * @return This object after updating the value etc
     */
    public IntegerVal value( int val ){

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
        if( abs )
            val=Math.abs(val);

        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            double v = val;
            // Execute all the triggers, only if it's the first time
            triggered.stream().forEach(tc -> tc.apply(v));
        }
        value=val;
        if( targets!=null ){
            int v = val;
            targets.forEach( wr -> wr.writeLine(id()+":"+v));
        }
        return this;
    }
    @Override
    public void updateValue( double val ) {
        value(((Double)val).intValue());
    }
    public void parseValue( String val ){
        var res = NumberUtils.toInt(val,Integer.MAX_VALUE);
        if( res != Integer.MAX_VALUE){
            value(res);
        }else{
            Logger.error(getID() + " -> Failed to parse "+val);
        }
    }
    /**
     * Set the default value, this will be used as initial value and after a reset
     *
     * @param defVal The default value
     */
    public IntegerVal defValue(int defVal){
        this.defVal = defVal;
        return this;
    }
    /**
     * Enable keeping track of the max and min values received since last reset
     * @return This object but with min max enabled
     */
    public IntegerVal keepMinMax(){
        keepMinMax=true;
        return this;
    }
    public void enableAbs(){
        abs=true;
    }
    @Override
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    /**
     * Reset this RealVal to its default value
     */
    @Override
    public void reset(){
        keepMinMax=false;
        if( triggered!=null)
            triggered.clear();
        super.reset();
    }
    /**
     * Tries to add a cmd with given trigger, will warn if no valid queue is present to actually execute them
     * @param cmd The cmd to trigger, $ will be replaced with the current value
     * @param trigger The trigger which is either a comparison between the value and another fixed value fe. above 10 or
     *                'always' to trigger on every update or 'changed' to trigger only on a changed value
     */
    public boolean addTriggeredCmd(String trigger,String cmd ){

        if( triggered==null)
            triggered = new ArrayList<>();

        var td = new TriggeredCmd(cmd,trigger);
        if( td.isInvalid()) {
            Logger.error(id()+" (iv)-> Failed to convert trigger: "+trigger);
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
    /* ***************************************** U S I N G ********************************************************** */
    public boolean storeInXml(XMLdigger digger){
        if( digger.isValid() ){ // meaning there's a rtvals node
            digger.goDown("group","id",group); // Try to go into the group node
            if( digger.isValid() ){ // Group exists
                digger.goDown("int","name",name); // Check for the int node
                if( digger.isInvalid() && digger.goDown("integer","name",name).isInvalid()){
                    XMLfab.alterDigger(digger).ifPresent( x-> x.addChild("int")
                            .attr("name",name)
                            .attr("unit",unit).build());
                }
            }else{ // No such group
                var digFabOpt = XMLfab.alterDigger(digger);
                if(digFabOpt.isPresent() ){
                    var digFab = digFabOpt.get();
                    digFab.selectOrAddChildAsParent("group","id",group);
                    digFab.addChild("int").attr("name",name).attr("unit","");
                    digFab.build();
                }
            }
        }
        return true;
    }
    /**
     * Store the setup of this val in the settings.xml
     * @param fab The fab to work with, with the rtvals node as parent
     * @return True when
     */
    public boolean storeInXml( XMLfab fab ){

        fab.alterChild("group","id",group)
                    .down(); // Go down in the group

        fab.hasChild("integer","id",name).ifPresent( x -> {
            x.removeAttr("id");
            x.attr("name",name);
        });
        fab.hasChild("int","id",name).ifPresent( x -> {
            x.removeAttr("id");
            x.attr("name",name);
        });

        if( fab.hasChild("integer","name",name).isEmpty()
                && fab.hasChild("int","name",name).isEmpty()) { // If this one isn't present
            fab.addChild("integer").attr("name", name);
        }else {
            fab.alterChild("integer", "name", name);
        }

        fab.attr("unit",unit);

        var opts = getOptions();
        if( !opts.isEmpty())
            fab.attr("options",opts);
        storeTriggeredCmds(fab.down());
        fab.build();
        return true;
    }
    /**
     * Get a ',' delimited string with all the used options
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
        return join.toString();
    }
    @Override
    public BigDecimal toBigDecimal() {
        try {
            return BigDecimal.valueOf(value);
        }catch(NumberFormatException e){
            Logger.warn(id()+" hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }
    public int max(){
        return max;
    }
    public int min(){
        return min;
    }
    @Override
    public double value() {
        return value;
    }
    @Override
    public int intValue() {
        return value;
    }
    public int intValue( String type) {
        return switch( type ){
            case "min" -> min();
            case "max" -> max();
            default -> intValue();
        };
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
            Logger.warn(id() + "(iv)-> Asked for the average of "+(group.isEmpty()?"":group+"_")+name+" but no history kept");
            return value;
        }
        return Tools.roundDouble(total/history.size(),3);
    }
    /**
     * Get the current Standard Deviation based on the history rounded to digits + 2 or 5 digits if no scale was set
     * @return The calculated standard deviation or NaN if either no history is kept or the history hasn't reached
     * the full size yet.
     */
    public double getStdev(){
        if( history==null) {
            Logger.error(id()+" (iv)-> Can't calculate standard deviation without history");
            return Double.NaN;
        }else if( history.size() != keepHistory){
            return Double.NaN;
        }
        ArrayList<Double> decs = new ArrayList<>();
        history.forEach( x -> decs.add((double)x));
        return MathUtils.calcStandardDeviation( decs,3);
    }
    public String asValueString(){
        return value+unit;
    }
    public String toString(){
        String line = value+unit;
        if( keepMinMax && max!=Integer.MIN_VALUE )
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
        RealVal.TRIGGERTYPE type;
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
            type= RealVal.TRIGGERTYPE.COMP;
            switch (trigger) {
                case "", "always" -> type = RealVal.TRIGGERTYPE.ALWAYS;
                case "changed" -> type = RealVal.TRIGGERTYPE.CHANGED;
                default -> {
                    if (trigger.contains("stdev")) {
                        type = RealVal.TRIGGERTYPE.STDEV;
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
                Logger.error(id()+" (iv)-> Tried to check for a trigger without a dQueue");
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
                    Logger.error(id() + " (iv)-> Somehow an invalid trigger sneaked in... ");
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
