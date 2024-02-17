package io.telnet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public class CommandLineInterface {
    private static final int BUFFER_SIZE = 128;
    ByteBuf buffer = Unpooled.buffer(BUFFER_SIZE);       // Buffer that holds the received data
    private final ArrayList<String> cmdHistory = new ArrayList<>(); // Buffer that holds the processed commands
    private int cmdHistoryIndex =-1; // Pointer to the last send historical cmd

    Channel channel;

    public CommandLineInterface( Channel channel ){
        this.channel=channel;

        if( channel !=null ) {
            channel.writeAndFlush(TelnetCodes.WILL_SGA); // Enable sending individual characters
            channel.writeAndFlush(TelnetCodes.WILL_ECHO);
        }
    }

    /**
     * Receive data to process
     * @param data The data received
     * @return And optional response
     */
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
                        case 66: // Arrow Down
                            sendHistory(1);
                            break;
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
            }else if( b == '\r' || b==19 ) { // CR
                writeByte((byte)13); // echo CR
                if(b==19)
                    insertByte((byte)0);
                int wi = buffer.writerIndex();
                while( buffer.getByte(wi) != 0)
                    wi++;
                buffer.setIndex(0,wi);
                rec = new byte[buffer.readableBytes()];
                buffer.readBytes(rec);
                buffer.clear();
                buffer.setZero(0,wi);
                String r = new String(rec);
                cmdHistory.remove(r);
                cmdHistory.add(r);
                if( cmdHistory.size()>50)
                    cmdHistory.remove(0);
                cmdHistoryIndex = cmdHistory.size();

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

    /**
     * Shift the content of the buffer left starting with the current writerindex
     */
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

    /**
     * Insert a byte at the current writerindex, shifting everything to the right of it ... to the right
     * @param b The byte to insert
     */
    private void insertByte( byte b ){
        if( buffer.writerIndex() > 2 && b==0 ){
            if( buffer.getByte(buffer.writerIndex()-1)==0)
                return;
        }
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

    /**
     * Send the historical command referenced to by the current histindex value and alter this value
     * @param adj The alteration to be applied to histIndex
     */
    private void sendHistory(int adj){

        // Return when the history buffer is empty
        if( cmdHistory.isEmpty() )
            return;

        cmdHistoryIndex += adj; // Alter the pointer

        if( cmdHistoryIndex <0) // Can't go lower than 0
            cmdHistoryIndex =0;

        if (cmdHistoryIndex == cmdHistory.size() ) // Shouldn't go out of bounds
            cmdHistoryIndex = cmdHistory.size() - 1;

        Logger.debug("Sending "+ cmdHistoryIndex);
        writeString("\r>" + cmdHistory.get(cmdHistoryIndex));//Move cursor and send history
        writeString(TelnetCodes.CLEAR_LINE_END); // clear the rest of the line
        buffer.clear(); // reset the reader and writer index
        buffer.writeBytes(cmdHistory.get(cmdHistoryIndex).getBytes()); // fill the buffer
        buffer.setZero( buffer.writerIndex(),BUFFER_SIZE-1); // clear the rest of the buffer
    }

    /**
     * Write a single byte to the channel this CLI is using
     * @param data The byte of data to send
     * @return True if the channel was active
     */
    public synchronized boolean writeByte( byte data ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush( new byte[]{data});
            return true;
        }
        return false;
    }

    /**
     * Write a string message to the channel this CLI is using
     * @param message The message to send
     * @return True if the channel was active
     */
    public synchronized boolean writeString( String message ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush(message.getBytes());
            return true;
        }
        return false;
    }
}


