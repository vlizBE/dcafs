package io.stream.tcp;

import io.Writable;
import io.netty.channel.ChannelHandlerContext;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.Tools;
import worker.Datagram;

import java.time.Instant;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class ModbusTCP extends TcpHandler{
    int index=0;
    long timestamp;
    byte[] rec = new byte[128];
    byte[] header=new byte[]{0,1,0,0,0,0,1};

    public ModbusTCP(String id, String label, BlockingQueue<Datagram> dQueue) {
        super(id, label, dQueue);
    }
    public ModbusTCP( String id,String label, BlockingQueue<Datagram> dQueue, Writable writable ){
        this(id,label,dQueue);
        this.writable=writable;
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {

        if( idle ){
            idle=false;
            listeners.forEach( l-> l.notifyActive(id));
        }

        long p = Instant.now().toEpochMilli() - timestamp;	// Calculate the time between 'now' and when the previous message was received
        if( p >= 0 ){	// If this time is valid
            passed = p; // Store it
        }
        if( passed > 10 ){  // Maximum allowed time is 3.5 characters which is 5ms at 9600
            if( debug )
                Logger.info("delay passed: "+passed+" rec:"+data.length);
            index=0;
        }
        timestamp = Instant.now().toEpochMilli();    		    // Store the timestamp of the received message

        for( byte b : data ){
            rec[index] = b;
            index++;
        }

        if( index < 8) // can't do anything with it yet anyway
            return;

        switch( rec[7] ){
            case 0x03: // Register read
                if( index == 6+rec[5] ) // Received all the data
                    processRegisters( Arrays.copyOfRange(rec,0,index) );
                break;
            case 0x06: // reply?
                if( index == 8 )
                    processRegisters( Arrays.copyOfRange(rec,0,index) );
                break;
            case 0x10:

                break;
            default: Logger.warn(id+"(mb) -> Received unknown type");
                Logger.info(Tools.fromBytesToHexString(rec,0,index));
                break;
        }
    }

    private void processRegisters( byte[] data ){
        // Log anything and everything (except empty strings)
        if( log )		// If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn( priority + "\t" + label + "\t[hex] " + Tools.fromBytesToHexString(rec,0,index) );

        int reg = data[8];

        StringJoiner join = new StringJoiner(",");
        for( int a=9;a<data.length;a+=2){
            int i0= data[a]<0?-1*((data[a]^0xFF) + 1):data[a];
            int i1= data[a+1]<0?-1*((data[a+1]^0xFF) + 1):data[a+1];
            join.add("reg"+reg+":"+(i0*256+i1));
            reg++;
        }
        var dg = Datagram.build( join.toString() )
                .label(label)
                .priority(priority)
                .writable(writable)
                .timestamp();

        if( !dQueue.add(dg) ){
            Logger.error(id +" -> Failed to add data to the queue");
        }

        if(debug)
            Logger.info( dg.getOriginID()+" -> " + Tools.fromBytesToHexString(rec,0,index));

        if( !targets.isEmpty() ){
            targets.forEach( dt -> eventLoopGroup.submit(()->dt.writeLine(join.toString())));
            targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
        }
    }

    /**
     * Writes the given bytes with the default header prepended (00 01 00 00 00 xx 01, x is data length)
     * @param data The data to append to the header
     * @return True if written
     */
    public boolean writeBytes(byte[] data) {
        if( channel==null || !channel.isActive() )
            return false;
        header[5] = (byte) (data.length+1);
        channel.write(header);
        channel.writeAndFlush(data);
        return true;
    }
    public boolean writeLine(String data) {
       return writeBytes(data.getBytes());
    }
    public byte[] getHeader(){
        return header;
    }
}
