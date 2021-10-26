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
                Logger.debug("Received: "+ (char)b+ " or " +Integer.toString(b)+" "+Integer.toString(data[a])+Integer.toString(data[a+1]));
                if( data[a]==91){
                    a++;
                    switch(data[a]){
                        case 65: // Arrow Up
                            sendHistory(-1);
                            break;
                        case 66:
                            sendHistory(1);
                            break; // Arrow Down
                        case 67: // Arrow Right
                            // Only move to the right if current space is used
                            if( buffer.getByte(buffer.writerIndex()) != 0 ) {
                                buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() + 1);
                                writeString(TelnetCodes.CURSOR_RIGHT);
                            }
                            break;
                        case 68: // Arrow Left

                            if( buffer.writerIndex() != 0 ) {
                                writeString(TelnetCodes.CURSOR_LEFT);
                                buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() - 1);
                            }
                            break;
                    }
                }
            }else if( b == '\n'){ //LF
                writeByte(b); // echo LF
            }else if( b == '\r') { // CR
                writeByte(b); // echo CR
                int wi = buffer.writerIndex();
                while( buffer.getByte(wi) != 0)
                    wi++;
                buffer.setIndex(0,wi);
                rec = new byte[buffer.readableBytes()];
                buffer.readBytes(rec);
                buffer.clear();
                buffer.setZero(0,wi);
                String r = new String(rec);
                if(!history.contains(r)) {
                    history.add(new String(rec));
                    if( history.size()>50)
                        history.remove(0);
                    histIndex = history.size();
                }
            }else if( b == 126){// delete
                writeString(TelnetCodes.CURSOR_RIGHT);
                if( buffer.getByte(buffer.writerIndex()+1)!=0x00){
                    buffer.setIndex( buffer.readerIndex(),buffer.writerIndex()+1);
                    shiftLeft();
                }else{
                    writeByte((byte)127);// do backspace
                    buffer.setByte(buffer.writerIndex(),0x00); // delete current value in buffer
                }
            }else if( b == 127){ // Backspace
                if( buffer.getByte(buffer.writerIndex())!=0x00){
                    shiftLeft();
                }else{
                    writeByte((byte)127);
                    buffer.setByte(buffer.writerIndex()-1,0x00);
                    buffer.setIndex( buffer.readerIndex(),buffer.writerIndex()-1);
                }
            }else{
                insertByte(b);
            }
        }
        return Optional.ofNullable(rec);
    }
    private void shiftLeft(){
        int old = buffer.writerIndex()-1; // index to the left
        buffer.setIndex(buffer.readerIndex(),old); // Shift index to the left
        writeString( TelnetCodes.CURSOR_LEFT ); // Shift cursor to the left

        while( buffer.getByte(buffer.writerIndex())!=0x00 ){
            byte tw = buffer.getByte(buffer.writerIndex()+1);
            buffer.writeByte(tw);
            if( tw==0){
                writeString(TelnetCodes.CURSOR_RIGHT);
                writeByte((byte)127);
            }else{
                writeByte(tw);
            }

        }
        writeString(TelnetCodes.cursorLeft(buffer.writerIndex()-old-1));
        buffer.setIndex(buffer.readerIndex(),old);
    }
    private void insertByte( byte b ){

        byte old = buffer.getByte(buffer.writerIndex());
        buffer.writeByte(b);
        writeByte(b);
        int offset=0;
        while( old!=0) {
            byte ol = buffer.getByte(buffer.writerIndex());
            buffer.writeByte(old);
            writeByte(old);
            old=ol;
            offset++;
        }
        if( offset!=0 ) {
            buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() - offset);
            writeString(TelnetCodes.cursorLeft(offset));
        }
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


