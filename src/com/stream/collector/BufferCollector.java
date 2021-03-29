package com.stream.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.tinylog.Logger;

/**
 * The purpose of this class is to provide an endpoint to collect the data from a stream.
 * Once the set amount of messages were received or the time out occurred
 *
 *  It's considered a collector because data arriving at it doesn't go any further.
 */
public class BufferCollector extends AbstractCollector {
    
    ArrayList<String> buffer = new ArrayList<>();       // a buffer to store the received data
    int bufferSize=-1;                                     // the maximum size of the buffer

    public BufferCollector(String id, int buffersize ){
        super(id);
        this.bufferSize=buffersize;                     // store the size to later compare to know when full
        buffer.ensureCapacity(buffersize);              // allocate the buffer so it doesn't need to grow
    }
    public BufferCollector(String id, String timeoutPeriod, ScheduledExecutorService scheduler ){
        super(id,timeoutPeriod,scheduler);
    }

    /* Static constructors */
    /**
     * Constructor for usage without timeout
     * @param id    Unique id for this object
     * @param buffersize Max amount of entries in the buffer
     * @return The object
     */
    public static BufferCollector sizeLimited(String id, int buffersize){
        return new BufferCollector( id, buffersize);
    }

    /**
     * Constructor for usage with time out
     * @param id Unique id for this object
     * @param timeoutPeriod Maximum amount of time to wait for a full buffer eg 10s, 5m3s, 1h etc
     * @param scheduler Scheduler to submit the timeout to
     * @return The object
     */
    public static BufferCollector timeLimited(String id, String timeoutPeriod, ScheduledExecutorService scheduler){
        return new BufferCollector( id, timeoutPeriod, scheduler);
    }

    /* Class specific stuff */

    /**
     * Retrieve the buffer
     * @return The list with the received data or empty list if none
     */
    public List<String> getBuffer(){ return buffer;  }

    /* Implementation of the abstract methods */
    protected boolean addData( String data ){
        buffer.add(data);
        Logger.info("Adding : "+data);
        if( buffer.size() > bufferSize && bufferSize !=-1){
            timeoutFuture.cancel(false); // stop the timeout timer
            timedOut();
        }
        return valid;
    }

    /**
     * If a timeout occurs (or buffer is full)
     */
    protected void timedOut(){
        valid=false; // no longer needed, so no longer valid
        // if no data was collected, this can be considered bad
        listeners.forEach( l -> l.collectorFinished("buffer:"+id,"buffer",  !buffer.isEmpty()));
        Logger.info("BufferWritable finished with "+buffer.size()+" records.");
    }

}
