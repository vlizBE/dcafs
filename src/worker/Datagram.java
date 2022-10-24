package worker;

import io.Writable;
import io.Readable;
import java.time.Instant;

/**
 * Simple storage class that holds the raw data before processing
 */
public class Datagram {
	
    String data;             // The received data
    byte[] raw;              // Raw received data
    int priority = 1;        // The priority of the data source
    String label="";            // The label of the data source
    int timeOffset = 0;      // The offset, only used for debug work (simulate arrival delays)
    String originID ="";     // ID of the origin of the message
    long timestamp;          // Timestamp when the data was received
    Writable writable;  //
    Readable readable;
    boolean silent = true;
    Object payload=null;

    public Datagram(String data){
        this.data = data;
        raw = data.getBytes();
    }
    public Datagram(){
    }

    public Writable getWritable(){
        return writable;
    }
    public String getOriginID(){ return originID;}

    public String getData(){
        return data ==null?"": data;
    }
    public void setData(String msg ){
        this.data =msg;
        raw = msg.getBytes();
    }
    public int getPriority(){ return priority; }
    public void setTimestamp( long timestamp ){
        this.timestamp = timestamp;
    }
    public byte[] getRaw(){
        return raw;
    }
    public Readable getReadable(){
        return readable;
    }
    public String getLabel(){ return label.toLowerCase(); }
    public boolean isSilent(){ return silent;}
    public Object getPayload(){return payload;}

    /* ***************************** Fluid API ******************************************* */
    public static Datagram build(String message){
        return new Datagram(message);
    }
    public static Datagram build(byte[] message){
        var d = new Datagram( new String(message).trim());
        d.raw=message;
        return d;
    }
    public static Datagram build(){
        return new Datagram();
    }
    public static Datagram system(String message){
        return Datagram.build(message).label("system");
    }
    public Datagram label(String label){
        this.label=label;
        return this;
    }
    public Datagram priority(int priority){
        this.priority=priority;
        return this;
    }
    public Datagram payload(Object payload){
        this.payload=payload;
        return this;
    }
    /**
     * Set the writable in this datagram, also overwrites the origin with id from writable
     * @param writable The writable to set
     * @return The datagram with updated writable
     */
    public Datagram writable(Writable writable){
        this.writable=writable;
        if(originID.isEmpty())
            this.originID=writable.getID();
        return this;
    }
    /**
     * Set the readable in this datagram, also overwrites the origin with id from readable
     * @param readable The readable to set
     * @return The datagram with updated writable
     */
    public Datagram readable( Readable readable ){
        this.readable=readable;
        if(originID.isEmpty())
            this.originID=readable.getID();
        return this;
    }
    public Datagram origin( String origin ){
        this.originID=origin;
        return this;
    }
    public Datagram timeOffset( int offset ){
        this.timeOffset = offset ;
        return this;
    }

    /**
     * Set the timestamp associated with this datagram
     * @param timestamp The epoch millis for this datagram
     * @return The datagram with set timestamp
     */
    public Datagram timestamp( long timestamp ){
        this.timestamp=timestamp;
        return this;
    }

    /**
     * Set the timestamp of this datagram to the current epoch millis
     * @return The datagram with current epoch millis as timestamp
     */
    public Datagram timestamp(){
        this.timestamp=Instant.now().toEpochMilli();
        return this;
    }
    public Datagram raw( byte[] raw ){
        this.raw=raw;
        return this;
    }
    public Datagram toggleSilent(){
        silent = !silent;
        return this;
    }
}
