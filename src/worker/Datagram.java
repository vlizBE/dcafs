package worker;

import java.time.Instant;
import com.stream.Writable;

/**
 * Simple storage class that holds the raw data before processing
 */
public class Datagram {
	
    String message = "";     // The received data
    byte[] raw;              // Raw received data
    double messValue = 0;    // Alternative data
    int priority = 1;        // The priority of the data source
    String label;            // The label of the data source
    int timeOffset = 0;      // The offset, only used for debug work (simulate arrival delays)
    String originID ="";     // ID of the origin of the message
    long timestamp;          // Timestamp when the data was received
    Writable writable;  //
    boolean silent = false;     

    public Datagram(String message, int priority, String label ){
        this.message=message;
        this.priority=priority;
        this.label=label;
    }
    public Datagram(byte[] raw, int priority, String label ){
        this( new String(raw),priority,label);
        this.raw=raw;
        this.timestamp = Instant.now().toEpochMilli();
    }
    public Datagram( Writable dataTrans, String label, String message ){
        this.writable=dataTrans;
        this.label=label;
        this.message=message;
    }
    public Datagram(Writable dt, byte[] raw, String message, int priority, String label ){
    	this( dt,message,priority,label);
    	this.raw=raw;
    }
    public Datagram(Writable dt, String message, int priority, String label ){
    	this(message,priority,label);
    	this.writable=dt;
    }
    public Datagram(String message, double val, int priority, String label ){
    	this(message,priority,label);
        this.messValue = val;
    }
    public Datagram(String message, int priority, String label, int offset){
    	this(message, priority, label );
    	this.timeOffset=offset;
    }
    public void setWritable( Writable wr ){
        this.writable = wr;
    }
    public Writable getWritable(){
        return writable;
    }
    public void setOriginID(String id ){
        this.originID =id;
    }
    public String getOriginID(){ return originID;}
    /**
     * Set the offset this message was received at, only used during debug mode. 
     * So the worker nows how much time passed between this datagram and the previous one.
     * @param offset The offset in millis
     */
    public void setTimeOffset(int offset){
    	this.timeOffset = offset ;
    }
    public String getMessage(){
        return message==null?"":message;
    }
    public void setMessage( String msg ){
        this.message=msg;
        raw = msg.getBytes();
    }
    public int getPriority(){ return priority; }
    public void setLabel( String label ){
        this.label=label;
    }
    public void setTimestamp( long timestamp ){
        this.timestamp = timestamp;
    }
    public String getTitle(){
        return originID;
    }
    public byte[] getRawMessage(){
        return raw;
    }
    public void toggleSilent(){
        silent = !silent;
    }
}
