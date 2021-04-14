package com.stream.forward;

import com.stream.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

/**
 * Abstract class to create a 'Forward'.
 * The idea is that this class gets data from anything that accepts a writable, do something with it and then forwards
 * it to any submitted writable.
 */
public abstract class AbstractForward implements Writable {

    protected BlockingQueue<Datagram> dQueue;                        // Queue to send commands
    protected ArrayList<Writable> targets = new ArrayList<>();       // To where the data needs to be send
    protected ArrayList<String[]> rulesString = new ArrayList<>();   // Readable info regarding rules

    protected String id="";                                   // The identifier for this object
    protected ArrayList<String> sources = new ArrayList<>();  // The commands that provide the data to filter
    protected boolean valid = false;           // Flag that determines of data should be received or not
    protected boolean debug = false;           // Flag that outputs more in the console for debugging purposes
    protected boolean xmlOk=false;             // There's a valid xml path available
    protected boolean deleteNoTargets=false;   // Get this object to kill itself when targets is empty
    protected Path xml;                        // Path to the xml file containing the info
    protected String label="";                 // With this the forward can use the dataQueue as a target
    protected int badDataCount=0;               // Keep track of the amount of bad data received
    static final protected int MAX_BAD_COUNT=10;

    protected AbstractForward(String id, String source, BlockingQueue<Datagram> dQueue ){
        this.id=id;
        if( !source.isEmpty() )
            sources.add(source);
        this.dQueue=dQueue;
    }
    protected AbstractForward( BlockingQueue<Datagram> dQueue){
        this.dQueue=dQueue;
    }
    public void setDebug( boolean debug ){
        this.debug=debug;
    }
    public void enableDebug(){
        debug=true;
    }
    public void disableDebug(){
        debug=false;
    }
    /**
     * Add a source of data for this FilterWritable can be any command
     * @param source The command that provides the data eg. raw:... calc:... etc
     * @return True if the source is new, false if not
     */
    public boolean addSource( String source ){
        if( !sources.contains(source) && !source.isEmpty()){
            sources.add(source);
            if( valid ){
                dQueue.add( new Datagram(this,source,1,"system") );
            }
            if( xmlOk )
                writeToXML( XMLfab.withRoot(xml, "das"));
            return true;
        }
        return false;
    }
    /**
     * Add a writable that the result of the filter should be written to
     * @param target The writable the result of the filter will be written to
     */
    public void addTarget( Writable target ){
        if( !targets.contains(target)){
            if( !valid ){
                valid=true;
                sources.forEach( source -> dQueue.add( new Datagram(this,source,1,"system") ) );
            }
            targets.add(target);
        }else{
            Logger.info(id+" -> Trying to add duplicate target "+target.getID());
        }
    }
    public boolean removeTarget( Writable target ){
        return targets.remove(target);
    }
    /**
     * The forward will request to be deleted if there are no writables to write to
     */
    public void enableRemoveIfNoTargets(){
        deleteNoTargets=true;
    }
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n" );
        join.add(id+ (sources.isEmpty()?" without sources":" from "+String.join( ";",sources)));
        join.add(getRules());

        StringJoiner ts = new StringJoiner(", ","Targets: ","" );
        ts.setEmptyValue("No targets yet.");
        targets.forEach( x -> ts.add(x.getID()));
        join.add(ts.toString());

        return join.toString();
    }
    /**
     * Get a readable list of the filter rules
     * @return Te list of the rules
     */
    public String getRules(){
        int index=0;
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue(" -> No rules yet.");
        for( String[] x : rulesString ){
            join.add("\t"+(index++) +" : "+x[2]+" -> "+x[1]);
        }

        return join.toString();
    }
    /**
     * Change the label
     * @param label The new label
     */
    public void setLabel(String label){
        this.label=label;
        valid = !label.isEmpty(); // A label counts as a valid target
    }
    /* *********************** Abstract Methods ***********************************/
    /**
     * This is called when data is received through the writable
     * @param data The data received
     * @return True if everything went fine
     */
    protected abstract boolean addData( String data );
    /**
     * Write all the settings for this to the given xml file
     * @param fab The XMLfab pointing to where the parent xml should be
     * @return True if written
     */
    public abstract boolean writeToXML( XMLfab fab );

    /**
     * Read all the settings for this from the given xml element
     * @param fwElement the element containing the info
     * @return True if ok
     */
    public abstract boolean readFromXML( Element fwElement );
    protected abstract String getXmlChildTag();
    /* **********************Writable implementation ****************************/
    @Override
    public boolean writeString(String data) {
        return addData(data);
    }
    @Override
    public boolean writeLine(String data) {
        return addData(data);
    }
    @Override
    public String getID() {
        return getXmlChildTag()+":"+id;
    }
    @Override
    public boolean isConnectionValid() {
        return valid;
    }
    @Override
    public Writable getWritable(){
        return this;
    }
}
