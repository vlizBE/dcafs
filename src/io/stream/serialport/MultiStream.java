package io.stream.serialport;

import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import worker.Datagram;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

public class MultiStream extends SerialStream{

    private final byte[] rec = new byte[512];
    private final byte[] header = new byte[]{'_','(','*','*',')','_'};
    private final ByteBuffer recBuffer;
    private final int payloadPosition=3;
    private final int idPosition=2;
    private static final byte deviceId='1';

    public MultiStream(BlockingQueue<Datagram> dQueue, Element stream) {
	    super(dQueue,stream);
        recBuffer = ByteBuffer.wrap(rec);
        eol = "";
    }
    @Override
    public String getType(){
        return "multiplex";
    }
    @Override
    public String getInfo() {
        return "MULTIPLEX [" + id + "|" + label + "] " + serialPort + " | " + getSerialSettings();
    }
    @Override
    protected void processListenerEvent(byte[] data){

        timestamp = Instant.now().toEpochMilli();  // Store the timestamp of the received message

        // Add the bytes to the buffer
        for( byte b : data ){ //iterate through the received data, one byte at a time
            int pos = recBuffer.position(); //get the current position in the buffer
            // If pos is higher than the length of the header, this means the header was found
            // If this is not the case then check if the current processed byte b is the same as the byte in the header or wildcard
            if( pos>=header.length || (b == header[pos]||header[pos]=='*')  ) {
                recBuffer.put(b); // if the above was true store it the buffer
                Logger.info("Added "+b+" to the buffer in "+recBuffer.position()); //debugging info
                // Next again only if the header was found already but also if the amount of payload receipt matches the expected
                if( recBuffer.position()>=header.length && recBuffer.position() >= rec[payloadPosition]+header.length ){
                    // Full message received, store it in a datagram

                    Datagram d = Datagram.build(Arrays.copyOfRange(rec,header.length,rec[payloadPosition]+header.length))
                                         .label(label)
                                         .origin(id+":"+(char)rec[idPosition]);
                    recBuffer.position(0); // reset position to start of buffer
                    Logger.info("Message found and forwarded: "+d.getData()+" from "+d.getOriginID()); // debug info

                    if( !targets.isEmpty() ){ // If there are targets
                        try {
                            targets.forEach(dt -> dt.writeLine(d.getData())); // send the payload
                            targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
                        }catch(Exception e){
                            Logger.error(id+" -> Something bad in multiplexer");
                            Logger.error(e);
                        }
                    }
                }
            }else if( pos!=0 ){ // If the received byte doesn't match the header
                recBuffer.position(0); // reset position
                if( b == header[0] || header[0]=='x') // it might have been the start of a new header, check this
                    recBuffer.put(b);
            }
        }
    }
    /**
     * Sending data that will be appended by the default newline string.
     *
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeLine(String message) {
        return writeString(message + eol);
    }

    /**
     * Sending data that won't be appended with anything
     *
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeString(String message) {
        if( message.length()>255){
            Logger.error("Message to long for the protocol");
            return false;
        }

        var head = Arrays.copyOf(header,header.length); // Create a copy of the header to fill in
        head[idPosition]=deviceId;  // fill in the id
        head[payloadPosition]= (byte)message.length(); //fill in the payload size

        return write(ArrayUtils.addAll(head,message.getBytes())); //write it
    }
}