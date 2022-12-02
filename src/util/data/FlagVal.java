package util.data;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.StringJoiner;

public class FlagVal extends AbstractVal implements NumericVal{

    boolean state=false;
    boolean defState=false;

    /* Triggering */
    ArrayList<String> raisedList = new ArrayList<>();
    ArrayList<String> loweredList = new ArrayList<>();

    /* Options */
    ArrayList<Boolean> history;

    private FlagVal(){}

    /**
     * Construct a new FlagVal
     * @param group The group the flag belongs to
     * @param name The name of the flag
     * @return The constructed FlagVal
     */
    public static FlagVal newVal(String group, String name){
        return new FlagVal().group(group).name(name);
    }

    /* **************************** C O N S T R U C T I N G ******************************************************* */
    public static FlagVal build(Element rtval, String group, boolean def){
        String name = XMLtools.getStringAttribute(rtval,"name","");
        name = XMLtools.getStringAttribute(rtval,"id",name);
        if( name.isEmpty())
            return null;

        return FlagVal.newVal(group,name).alter(rtval,def);
    }
    public FlagVal alter( Element rtval, boolean defFlag){
        reset(); // reset is needed if this is called because of reload
        name(name)
                .group(XMLtools.getChildValueByTag(rtval, "group", group()))
                .defState(XMLtools.getBooleanAttribute(rtval, "default", defFlag));
        if (XMLtools.getBooleanAttribute(rtval, "keeptime", false))
            keepTime();
        if (!XMLtools.getChildElements(rtval, "cmd").isEmpty())
            enableTriggeredCmds(dQueue);
        for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        }
        return this;
    }
    /**
     * Set the name
     * @param name The name
     * @return This object with altered name
     */
    public FlagVal name(String name){
        this.name=name;
        return this;
    }

    /**
     * Set the group this belongs to
     * @param group The group of which this is part
     * @return This object with altered group
     */
    public FlagVal group(String group){
        this.group=group;
        return this;
    }
    public FlagVal setState( String state ){
        if( state.equalsIgnoreCase("true")
                || state.equalsIgnoreCase("1")
                || state.equalsIgnoreCase("on")) {
            setState(true);
        }else if( state.equalsIgnoreCase("false")|| state.equalsIgnoreCase("0")
                || state.equalsIgnoreCase("off")) {
            setState(false);
        }
        return this;
    }
    /**
     * Set the state, apply the options and check for triggered cmd's
     * @param val The new state
     * @return This object with altered state
     */
    public FlagVal setState( boolean val){
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();
        /* Check if valid and issue commands if trigger is met */
        if( val!=state){ // If the value has changed
            if( val ){ // If the flag goes from FALSE to TRUE => raised
                raisedList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","true"))));
            }else{ // If the flag goes from TRUE to FALSE => lowered
                loweredList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","false"))));
            }
        }
        state=val;// update the state
        return this;
    }

    /**
     * Toggle the state of the flag and return the new state
     * @return The new state
     */
    public boolean toggleState(){
        if( keepTime )
            timestamp= Instant.now();
        state=!state;// toggle the state
        /* Check if valid and issue commands if trigger is met */
        if( state ){ // If the flag goes from FALSE to TRUE => raised
            raisedList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","true"))));
        }else{ // If the flag goes from TRUE to FALSE => lowered
            loweredList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","false"))));
        }
        return state;
    }
    /**
     * Alter the default state (default is false)
     * @param defState The new default state
     */
    public void defState( boolean defState){
        this.defState = defState;
    }

    /**
     * Enable the storage of old values up till the count
     * @param count The amount of old values to store
     * @return True if this was enabled
     */
    @Override
    public boolean enableHistory(int count){
        if( count > 0) // only valid if above 0, so no need to init if not
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    /* *************************************** U S I N G *********************************************************** */

    /**
     * Alter the state based on the double value, false if 0 or true if not
     * @param val The value to check that determines the state
     */
    @Override
    public void updateValue(double val) {
        setState( Double.compare(val,0.0)!=0 );
    }

    /**
     * Convert the flag state to a big decimal value
     * @return BigDecimal.ONE if the state is true or ZERO if not
     */
    public BigDecimal toBigDecimal(){
        return state?BigDecimal.ONE:BigDecimal.ZERO;
    }

    /**
     * Check if the flag state matches that of another flag
     * @param fv The flag to compare to
     * @return True if the flags have the same state
     */
    public boolean equals(FlagVal fv){
        return state == fv.isUp();
    }

    /**
     * Check if the flag is up
     * @return True if up
     */
    public boolean isUp(){
        return state;
    }
    /**
     * Check if the flag is down
      * @return True if down
     */
    public boolean isDown(){
        return !state;
    }

    /**
     * Retrieve the state of the flag but as a double
     * @return 1.0 if true and 0.0 if false
     */
    public double value(){
        return state?1:0;
    }

    @Override
    public int intValue() {
        return state?1:0;
    }
    public String asValueString(){
        return toString();
    }
    public String toString(){
        return ""+state;
    }

    /**
     * Reset this flagVal to default state, disables options and clears cmd's
     */
    @Override
    public void reset(){
        state = defState;
        raisedList.clear();
        loweredList.clear();
        super.reset(); // This resets common options like keep time
    }
    /* ******************************** T R I G G E R E D ********************************************************** */
    /**
     * Tries to add a cmd with given trigger, will warn if no valid queue is present to actually execute them
     *
     * @param trigger The trigger which is either a comparison between the value and another fixed value fe. above 10 or
     *                'always' to trigger on every update or 'changed' to trigger only on a changed value
     * @param cmd The cmd to trigger, $ will be replaced with the current value
     */
    public boolean addTriggeredCmd(String trigger, String cmd){
        if( dQueue==null)
            Logger.error("Tried to add cmd "+cmd+" but dQueue still null");
        switch( trigger ){
            case "raised": case "up": case "set": // State goes from false to true
                raisedList.add(cmd);
                break;
            case "lowered": case "down": case "clear": // state goes from true to false
                loweredList.add(cmd);
                break;
            default: // No other triggers for now or typo's
                return false;
        }
        return true;
    }
    public boolean hasTriggeredCmds(){
        return !loweredList.isEmpty()||!raisedList.isEmpty();
    }

    @Override
    public void parseValue(String value) {
        setState(value);
    }

    @Override
    public boolean storeInXml(XMLfab fab) {
        if( group.isEmpty()) {
            fab.alterChild("flag", "id",id()).build();
        }else{
            fab.alterChild("group","id",group )
                    .down().addChild("flag").attr("name",name);
        }
        var opts = getOptions();
        if( !opts.isEmpty())
            fab.attr("options",opts);
        storeTriggeredCmds(fab.down());
        fab.up();
        if( !group.isEmpty())
            fab.up(); // Go back up to rtvals
        return true;
    }
    private void storeTriggeredCmds(XMLfab fab){
        for( var cmd : raisedList )
            fab.addChild("cmd",cmd).attr("when","up");
        for( var cmd : loweredList )
                fab.addChild("cmd",cmd).attr("when","down");
    }
    private String getOptions(){
        var join = new StringJoiner(",");
        if( keepTime )
            join.add("time");
        if( keepHistory>0)
            join.add("history:"+keepHistory);
        if( order !=-1 )
            join.add("order:"+order);
        return join.toString();
    }
}
