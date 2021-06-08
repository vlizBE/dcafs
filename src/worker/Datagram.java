package worker;

import com.stream.Writable;

import java.time.Instant;
import com.stream.Readable;

/**
 * Simple storage class that holds the raw data before processing
 */
public class Datagram {
	
    String message;     // The received data
    byte[] raw;              // Raw received data
    double messValue = 0;    // Alternative data
    int priority = 1;        // The priority of the data source
    String label;            // The label of the data source
    int timeOffset = 0;      // The offset, only used for debug work (simulate arrival delays)
    String originID ="";     // ID of the origin of the message
    long timestamp;          // Timestamp when the data was received
    Writable writable;  //
    Readable readable;
    boolean silent = false;     

    public Datagram(String message){
        this.message=message;
        raw = message.getBytes();
    }
    public Datagram(){

    }

    public Writable getWritable(){
        return writable;
    }
    public String getOriginID(){ return originID;}

    public String getMessage(){
        return message==null?"":message;
    }
    public void setMessage( String msg ){
        this.message=msg;
        raw = msg.getBytes();
    }
    public int getPriority(){ return priority; }
    public void setTimestamp( long timestamp ){
        this.timestamp = timestamp;
    }
    public String getTitle(){
        return originID;
    }
    public byte[] getRawMessage(){
        return raw;
    }
    public Readable getReadable(){
        return readable;
    }

    /* ***************************** Fluid API ******************************************* */
    public static Datagram build(String message){
        return new Datagram(message);
    }
    public static Datagram build(byte[] message){
        var d = new Datagram( new String(message));
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
    public Datagram writable(Writable writable){
        this.writable=writable;
        this.originID= writable.getID();
        return this;
    }
    public Datagram readable( Readable readable ){
        this.readable=readable;
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
    public Datagram timestamp( long timestamp){
        this.timestamp=timestamp;
        return this;
    }
    public Datagram timestamp(){
        this.timestamp=Instant.now().toEpochMilli();
        return this;
    }
    public Datagram raw( byte[] raw){
        this.raw=raw;
        return this;
    }
    public Datagram toggleSilent(){
        silent = !silent;
        return this;
    }
}
