package util.data;

import org.tinylog.Logger;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

public class FlagVal extends AbstractVal implements NumericVal{

    boolean state=false;
    boolean defState=false;

    /* Triggering */
    private enum TRIGGERTYPE {RAISED,LOWERED};
    ArrayList<String>[] cmdLists = new ArrayList[TRIGGERTYPE.values().length];

    /* Options */
    ArrayList<Boolean> history;

    private FlagVal(){}

    /**
     * Construct a new FlagVal
     * @param combined The concatenation of the group + underscore + name or just the name if no group
     * @return The constructed FlagVal
     */
    public static FlagVal newVal(String combined){
        String[] spl = combined.split("_");

        if( spl.length==2) {
            return new FlagVal().group(spl[0]).name(spl[1]);
        }else if( spl.length>2){
            String name = spl[1]+"_"+spl[2];
            for( int a=3;a<spl.length;a++)
                name+="_"+spl[a];
            return new FlagVal().group(spl[0]).name(name);
        }
        return new FlagVal().name(spl[0]);
    }
    /* **************************** C O N S T R U C T I N G ******************************************************* */

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
        if( cmdLists != null && val!=state){ // If there are cmds stored and the value has changed
            ArrayList<String> list=null;
            if( val ){ // If the flag goes from FALSE to TRUE => raised
                list = cmdLists[TRIGGERTYPE.RAISED.ordinal()];
            }else{ // If the flag goes from TRUE to FALSE => lowered
                list = cmdLists[TRIGGERTYPE.LOWERED.ordinal()];
            }
            if( list != null) // If cmd's were found, issue them
                list.stream().forEach( c -> dQueue.add(Datagram.system(c.replace("$",""+val))));
        }
        state=val;// update the state
        return this;
    }

    /**
     * Alter the default state (default is false)
     * @param defState The new default state
     * @return This object with altered default state
     */
    public FlagVal defState( boolean defState){
        this.defState = defState;
        return this;
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
     * Convert the flag state to a bigdecimal value
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
    public String toString(){
        return ""+state;
    }

    /**
     * Reset this flagval to default state, disables options and clears cmd's
     */
    @Override
    public void reset(){
        state = defState;
        for( int a=0;a<cmdLists.length;a++){
            cmdLists[a]=null;
        }
        super.reset(); // This resets common options like keeptime
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
        int index=-1;
        switch( trigger ){
            case "raised": case "up": case "set": // State goes from false to true
                index = TRIGGERTYPE.RAISED.ordinal(); break;
            case "lowered": case "down": case "clear": // state goes from true to false
                index = TRIGGERTYPE.LOWERED.ordinal(); break;
            default: // No other triggers for now or typo's
                return false;
        }
        if( cmdLists[index]==null) // If the target position is null
            cmdLists[index] = new ArrayList<>(); // Create a new arraylist
        cmdLists[index].add(cmd);// add the cmd to the list on the position determined by the trigger type
        return true;
    }
}
