package io.telnet;

import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public class CommandLineInterface {
    ByteBuf buffer = Unpooled.buffer(64);
    private ArrayList<String> history = new ArrayList<>();
    private int histIndex=-1;

    Channel channel;

    public CommandLineInterface( Channel channel ){
        this.channel=channel;

        if( channel !=null ) {
            channel.writeAndFlush(TelnetCodes.WILL_SGA); // Enable sending individual characters
            channel.writeAndFlush(TelnetCodes.WILL_ECHO);
        }
    }
    public Optional<byte[]> receiveData(byte[] data ){

        // Work with buffer
        StringJoiner join = new StringJoiner(" ");
        byte[] rec=null;
        for( int a=0;a<data.length;a++ ){
            byte b = data[a];
            if( b == TelnetCodes.IAC ){ // Meaning start of command sequence
                join.add( TelnetCodes.toReadableIAC(data[a++]))
                        .add(TelnetCodes.toReadableIAC(data[a++]))
                        .add(TelnetCodes.toReadableIAC(data[a]));
            }else if( b == 27){ // Escape codes
                a++;
                Logger.info("Received: "+ (char)b+ " or " +Integer.toString(b)+" "+Integer.toString(data[a])+Integer.toString(data[a+1]));
                if( data[a]==91){
                    a++;
                    switch(data[a]){
                        case 65: // Arrow Up
                            sendHistory(-1);
                            Logger.info("Arrow Up");
                            break;
                        case 66:
                            sendHistory(1);
                            Logger.info("Arrow Down"); break; // Arrow Down
                        case 67: // Arrow Right
                            // Only move to the right if current space is used
                            if( buffer.getByte(buffer.writerIndex()) != 0 ) {
                                buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() + 1);
                                writeString(TelnetCodes.CURSOR_RIGHT);
                            }
                            break;
                        case 68: // Arrow Left
                            Logger.info( "Value at current index:" +buffer.getByte(buffer.writerIndex()));
                            Logger.info( "Value at current index:" +buffer.writerIndex());
                            if( buffer.writerIndex() != 0 ) {
                                writeString(TelnetCodes.CURSOR_LEFT);
                                buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() - 1);
                            }
                            break;
                    }
                }
            }else if( b == '\n'){ //LF
                Logger.info("Received LF");
                writeByte(b); // echo LF
            }else if( b == '\r') { // CR
                Logger.info("Received CR");
                writeByte(b); // echo CR
                int wi = buffer.writerIndex();
                while( buffer.getByte(wi) != 0)
                    wi++;
                buffer.setIndex(0,wi);
                rec = new byte[buffer.readableBytes()];
                buffer.readBytes(rec);
                String r = new String(rec);
                if(!history.contains(r)) {
                    history.add(new String(rec));
                    if( history.size()>50)
                        history.remove(0);
                    histIndex = history.size();
                }
                buffer.discardReadBytes();
            }else if( b == 127){
                Logger.info("Backspace");
                writeByte(b);
                buffer.setIndex( buffer.readerIndex(),buffer.writerIndex()-1);
            }else{
                Logger.info("Received: "+ (char)b+ " or " +Integer.toString(b));
                writeByte(b);
                buffer.writeByte(b);
            }
        }
        return Optional.ofNullable(rec);
    }
    private void sendHistory(int adj){

        // Return when the history buffer is empty
        if( history.isEmpty() )
            return;

        histIndex += adj; // Alter the pointer

        if( histIndex<0) // Can't go lower than 0
            histIndex=0;

        if (histIndex == history.size() ) // Shouldn't go out of bounds
            histIndex = history.size() - 1;

        Logger.info("Sending "+histIndex);
        writeString("\r>" + history.get(histIndex));//Move cursor and send history
        writeString(TelnetCodes.CLEAR_LINE_END); // clear the rest of the line
        buffer.clear(); // clear the buffer
        buffer.writeBytes(history.get(histIndex).getBytes()); // fill the buffer
    }
    public synchronized boolean writeByte( byte data ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush( new byte[]{data});
            return true;
        }
        return false;
    }
    public synchronized boolean writeString( String message ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush(message.getBytes());
            return true;
        }
        return false;
    }
}


