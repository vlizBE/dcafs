package util.data;

import util.xml.XMLfab;
import worker.Datagram;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public abstract class AbstractVal {

    protected String name;
    protected String group="";

    /* Position in lists */
    int order = -1;

    /* Keep Time */
    protected Instant timestamp;
    protected boolean keepTime=false;

    /* History */
    protected int keepHistory=0;

    protected BlockingQueue<Datagram> dQueue;


    /* ************************************* Options ******************************************************** */
    public void reset(){
        keepTime=false;
        keepHistory=0;
        order=-1;
    }
    /**
     * Enable keeping time of the last value update
     * @return This object but with time keeping enabled
     */
    public void keepTime(){
        keepTime=true;
    }
    /**
     * Enable keeping old values up till the given count
     * @param count The amount of old values to store
     * @return True if valid
     */
    public boolean enableHistory(int count){
        if(count<=0)
            return false;
        keepHistory=count;
        return true;
    }
    /**
     * Set the order in which this item should be listed in the group list, the higher the order the higher in the list.
     * If the order is shared, it will be sorted alphabetically
     * @param order The new order for this object
     */
    public void order( int order ){
        this.order=order;
    }

    /**
     * Get the order, which determines its place in the group list
     * @return The order of this object
     */
    public int order(){
        return order;
    }
    /* **************************** Triggered Cmds ***************************************************************** */
    /**
     * Enable allowing triggered commands to be added
     * @param dQueue The queue in which the datagram holding the command needs to be put
     */
    public void enableTriggeredCmds(BlockingQueue<Datagram> dQueue){
        this.dQueue=dQueue;
    }

    /**
     * Add a triggerd cmd to this Val
     * @param cmd The cmd, in which $ will be replaced with the value causing it
     * @param trigger The trigger, the options depend on the type of Val
     * @return True if adding was successful
     */
    abstract boolean addTriggeredCmd(String cmd, String trigger);

    /**
     * Check if this has triggered cmd's
     * @return True if it has at least one cmd
     */
    abstract boolean hasTriggeredCmds();
    /* ************************************** Getters ************************************************************** */
    /**
     * Get the id, which is group + underscore + name
     * @return The concatenation of group, underscore and name
     */
    public String id(){
        return group.isEmpty()?name:(group+"_"+name);
    }
    public String group(){
        return group;
    }
    public String name(){
        return name;
    }

    abstract boolean storeInXml(XMLfab fab);
}
