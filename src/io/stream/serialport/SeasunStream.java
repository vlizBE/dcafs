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

    private final byte[] rec = new byte[3];
    private int good=0;

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
            switch (good) {
                case 0,1 -> {
                    if (b % 2 == 1) { // H
                        rec[good] = (byte)(b - 1);
                        good++;
                    }else{
                        good=0;
                    }
                }
                case 2 -> {
                    if (b % 2 == 0) { // L
                        rec[2] = (byte)(b / 2);
                        good++;
                    } else {
                        good = 0;
                    }
                }
            }
        }
        if( good==3 ){
            int value = rec[0]*256 + rec[1]*2 + rec[0]%4;
            int addr = rec[2]/4;

            if(debug)
                Logger.debug(id+"(ss) -> "+Tools.fromBytesToHexString(rec,0,3));
            if( log )		// If the message isn't an empty string and logging is enabled, store the data with logback
                Logger.tag("RAW").warn( priority + "\t" + label+"|"+id + "\t[hex] " + Tools.fromBytesToHexString(rec,0,3) );

            forwardData(addr+";"+value);
        }
    }
    @Override
    public synchronized boolean writeBytes(byte[] data) {
        return write(data);
    }
}