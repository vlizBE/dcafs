package io.stream.serialport;

import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.Tools;
import worker.Datagram;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public class SeasunStream extends SerialStream{

    private final int[] rec = new int[3];
    private int good=0;
    int cnt=0;
    public SeasunStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
        eol="";
    }
    @Override
    public String getType(){
        return "seasun";
    }
    @Override
    public String getInfo() {
        return "SEASUN [" + id + "|" + label + "] " + serialPort + " | " + getSerialSettings();
    }
    @Override
    protected void processListenerEvent(byte[] data){

        for( byte b : data ){
            int val = Byte.toUnsignedInt(b);
            //Logger.info( "To unsigned:"+ b +"->"+val );
            switch (good) {
                case 0,1 -> {
                    if (val % 2 == 1) { // H
                        rec[good] = (val - 1);

                        //Logger.info("Good:"+good+":"+val);
                        good++;
                    }else{
                        Logger.error("BAD:"+good+":"+val);
                        good=0;
                    }
                }
                case 2 -> {
                    if (val % 2 == 0) { // L
                        rec[2] = val / 2;
                        //Logger.info("Good:2:"+val);
                        good++;
                    } else {
                        Logger.error("BAD:2:"+val);
                        good = 0;

                    }
                }
            }
            if( good==3 ){
                timestamp = Instant.now().toEpochMilli(); // Store the timestamp of the received message
                good=0;
                int value = rec[0]/2 + (rec[1]<<6)+ ((rec[2]%4)<<14);
                int addr = rec[2]/4;

                if( log )       // If the message isn't an empty string and logging is enabled, store the data with logback
                    Logger.tag("RAW").warn(priority + "\t" + id + "\t[ok] " + Tools.fromIntsToHexString(rec,"\t")+ " => "+addr+"|"+value );
               //Logger.info("Good sequence: "+Tools.fromIntsToHexString(rec,"\t"));
                forwardData(addr+";"+value);
            }
        }

    }

    @Override
    public synchronized boolean writeBytes(byte[] data) {
        return write(data);
    }
}