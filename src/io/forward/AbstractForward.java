package io.forward;

import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.xml.XMLfab;
import util.xml.XMLtools;
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

    protected final BlockingQueue<Datagram> dQueue;                        // Queue to send commands
    protected final ArrayList<String> cmds = new ArrayList<>();            // Commands to execute after processing
    protected final ArrayList<Writable> targets = new ArrayList<>();       // To where the data needs to be send
    protected final ArrayList<String[]> rulesString = new ArrayList<>();   // Readable info regarding rules

    protected String id="";                                   // The identifier for this object
    protected final ArrayList<String> sources = new ArrayList<>();  // The commands that provide the data to filter
    protected boolean valid = false;           // Flag that determines of data should be received or not
    protected boolean debug = false;           // Flag that outputs more in the console for debugging purposes
    protected boolean xmlOk=false;             // There's a valid xml path available
    protected boolean deleteNoTargets=false;   // Get this object to kill itself when targets is empty
    protected Path xml;                        // Path to the xml file containing the info
    protected String label="";                 // With this the forward can use the dataQueue as a target
    protected int badDataCount=0;               // Keep track of the amount of bad data received
    protected boolean log = false;
    protected final RealtimeValues rtvals;
    protected boolean readOk=false;

    protected AbstractForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        this.id=id;
        this.rtvals=rtvals;
        if( !source.isEmpty() )
            sources.add(source);
        this.dQueue=dQueue;
    }
    protected AbstractForward( BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        this.dQueue=dQueue;
        this.rtvals=rtvals;
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
    public void addAfterCmd( String cmd){
        cmds.add(cmd);
    }
    /**
     * Add a source of data for this FilterWritable can be any command
     * @param source The command that provides the data eg. raw:... calc:... etc
     * @return True if the source is new, false if not
     */
    public boolean addSource( String source ){
        if( !sources.contains(source) && !source.isEmpty()){
            sources.add(source);
            Logger.info(getID() +" -> Adding source "+source);
            if( valid ){
                Logger.info(getID() +" -> Requesting data from "+source);
                dQueue.add( Datagram.system( source ).writable(this) );
            }
            if( xmlOk )
                writeToXML( XMLfab.withRoot(xml, "dcafs"));
            return true;
        }
        return false;
    }
    public void removeSources(){ sources.clear();}
    public void removeSource( String source ){
        sources.remove(source);
    }
    /**
     * Add a writable that the result of the filter should be written to
     * @param target The writable the result of the filter will be written to
     */
    public synchronized void addTarget( Writable target ){
        if( target.getID().isEmpty()) {
            Logger.error(id+" -> Tried to add target with empty id");
            return;
        }
        if( !targets.contains(target)){
            if( !valid ){
                valid=true;
                sources.forEach( source -> {
                    if( source.contains(":")) {
                        dQueue.add(Datagram.build(source).label("system").writable(this));
                    }else{
                        dQueue.add(Datagram.build("path:"+source).label("system").writable(this));
                    }
                } );
            }
            targets.add(target);
        }else{
            Logger.info(id+" -> Trying to add duplicate target "+target.getID());
        }
    }
    public boolean hasSrc(){
        return !sources.isEmpty();
    }
    public String getSrc(){
        return sources.isEmpty()?"":sources.get(0);
    }
    public String src(){
        return sources.isEmpty()?"":sources.get(0);
    }
    public boolean removeTarget( Writable target ){
        return targets.remove(target);
    }
    public void removeTargets(){
        targets.clear();
    }
    public boolean noTargets(){
        return targets.isEmpty() && label.isEmpty() && !log;
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }
    /**
     * The forward will request to be deleted if there are no writables to write to
     */
    public String toString(){
        String type = this instanceof MathForward?"math":"editor";
        StringJoiner join = new StringJoiner("\r\n" );
        join.add(type+":"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
        join.add(getRules());

        if(!targets.isEmpty()) {
            StringJoiner ts = new StringJoiner(", ", "    Targets: ", "");
            targets.forEach(x -> ts.add(x.getID()));
            join.add(ts.toString());
        }

        if( !label.isEmpty()) {
            if( label.startsWith("generic")){
                join.add("    Given to generic/store " + label.substring(8));
            }else {
                join.add("    To label " + label);
            }
        }
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
            join.add("\t"+(index++) +" : "+x[1]+" -> "+x[2]);
        }

        return join.toString();
    }
    /**
     * Change the label
     * @param label The new label
     */
    public void setLabel(String label){
        this.label=label;
        if( !valid && !label.isEmpty()) {
            sources.forEach(source -> dQueue.add(Datagram.build(source).label("system").writable(this)));
            valid=true;
        }
    }
    protected boolean readBasicsFromXml( Element fw ){

        if( fw==null) // Can't work if this is null
            return false;

        /* Attributes */
        id = XMLtools.getStringAttribute( fw, "id", "");
        if( id.isEmpty() ) // Cant work without id
            return false;

        label = XMLtools.getStringAttribute( fw, "label", "");
        log = XMLtools.getBooleanAttribute(fw,"log",false);

        if( !label.isEmpty() || log ){ // this counts as a target, so enable it
            valid=true;
        }

        /* Sources */
        sources.clear();
        addSource(XMLtools.getStringAttribute( fw, "src", ""));
        XMLtools.getChildElements(fw, "src").forEach( ele ->sources.add(ele.getTextContent()) );

        rulesString.clear();
        Logger.info(id+" -> Reading from xml");
        return true;
    }

    /**
     * Write the basics that are the same for each forward
     * @param fab An XMLfab with the forward as the current parent
     */
    protected void writeBasicsToXML( XMLfab fab){
        if( !label.isEmpty() )
            fab.attr("label",label);

        // Sources
        if( sources.size()==1 ){
            fab.attr("src",sources.get(0));
        }else{
            fab.content("");
            fab.removeAttr("src"); // making sure there aren't any leftovers
            fab.comment("Sources go here");
            sources.forEach( src -> fab.addChild("src", src) );
        }
    }
    public void setInvalid(){valid=false;}
    /* *********************** Abstract Methods ***********************************/
    /**
     * This is called when data is received through the writable
     * @param data The data received
     * @return True if everything went fine
     */
    protected abstract boolean addData( String data );
    /**
     * Write all the settings for this to the given xml file
     *
     * @param fab The XMLfab pointing to where the parent xml should be
     */
    public abstract void writeToXML(XMLfab fab );

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
    public boolean writeBytes(byte[] data) {
        return addData(new String(data));
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
