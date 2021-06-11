package com.stream.tcp;

import com.stream.StreamListener;
import com.stream.Writable;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.tinylog.Logger;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class TcpHandler extends SimpleChannelInboundHandler<byte[]>{

    protected BlockingQueue<Datagram> dQueue;

    protected boolean idle=false;
    protected String id="";
    protected String label="";
    protected int priority = 1;

    protected List<StreamListener> listeners;

    protected Channel channel;
    protected boolean clean=true;
    protected boolean debug=false;
    protected boolean log=true;

    protected Long timeStamp=-1L;
    protected Long passed;

    protected InetSocketAddress remote;
    protected Writable writable;

    protected List<Writable> targets;
    
    String eol="\r\n";
    boolean udp=false;

    public TcpHandler( String id,String label, BlockingQueue<Datagram> dQueue ){
        this.id=id;
        this.label=label;
        this.dQueue=dQueue;
    }
    public TcpHandler( String id,String label, BlockingQueue<Datagram> dQueue, Writable writable ){
        this(id,label,dQueue);
        this.writable=writable;
    }
    public void setTargets(List<Writable> targets){
        this.targets = targets;
    }
    public void setEOL( String eol ){
        this.eol=eol;
    }
	public String getIP(){
		return remote.getAddress().getHostAddress();
    }
    public long getTimestamp(){
        return timeStamp;
    }
    public void setPriority( int priority){this.priority=priority;}
    public boolean toggleUDP(){
        udp=!udp;
        return udp;
    }
    /* StreamListener */
    public void setStreamListeners( List<StreamListener> listeners ){
        this.listeners=listeners;
    }
    public void addStreamListener( StreamListener listener ){
        if( listeners == null)
            listeners = new ArrayList<>();
        listeners.add(listener);
    }
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    	if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE ) {
            	if( !idle) {
					Logger.error( "IdleNotify for "+id+" "+label);
					listeners.forEach( l-> l.notifyIdle(id));
					idle=true;
            	}
            }else if (e.state() == IdleState.WRITER_IDLE) {
            	Logger.error( "WRITER IDLE for "+id);
            }else {
            	Logger.error( "Something went Wrong");
            }
        }else{
    	    Logger.info(id+" -> Unknown user event... "+evt);
        }
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised, but don't send messages if it's related to remote ignore	
		String address = ctx.channel().remoteAddress().toString();

		if (cause instanceof TooLongFrameException){	
			Logger.warn(id+" -> Unexpected exception caught: "+cause.getMessage(), true);
			ctx.flush();
		}else if( cause instanceof java.net.PortUnreachableException){
			if( !udp ){
				Logger.error("Device/Port unreachable, probably offline: "+address);
				ctx.flush();
				ctx.close();							// Close the channel
			}		
		}else{
		    Logger.error(cause);
			Logger.error( id+" -> Unexpected exception caught: " + cause.getMessage() );
			ctx.close();							// Close the channel
		}
	}
    @Override
    public void channelActive(ChannelHandlerContext ctx) {

		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// Incase the remote address is not null
			remote = (InetSocketAddress)ctx.channel().remoteAddress();	// Store this as remote address
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}
	
        Logger.info("Channel Opened: "+ctx.channel().remoteAddress() +" ("+label+")");
        if( !label.equals("telnet")&&!label.equals("trans") && !id.isBlank()){
            listeners.forEach( l-> l.notifyOpened(id) );
        }     
		
        ChannelFuture closeFuture = channel.closeFuture();           
        closeFuture.addListener((ChannelFutureListener) future -> {
             future.cancel(true);

             Logger.info( "Channel Closed! "+ remote.toString() +" ("+label +")");

             if( channel!=null)
                 channel.close();

             listeners.forEach( l-> l.requestReconnection(id));
             listeners.forEach( l-> l.notifyClosed(id));

         });
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {   
        // Don't care about this  
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
        // Don't care about this     
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {
       
       String msg = new String( data );	// Convert the raw data to a readable string
	   
	   if( idle ){
		    idle=false;
		    listeners.forEach( l-> l.notifyActive(id));
	   }	
       if ( !(msg.isBlank() && clean)) { //make sure that the received data is not 'null' or an empty string           
            if( clean ){        	   
                msg = msg.replace("\n", "");   // Remove newline characters
                msg = msg.replace("\r", "");   // Remove carriage return characters
                msg = msg.replace("\0","");    // Remove null characters
                //msg = msg.trim();
            }
            Datagram d = new Datagram( writable,data, msg, priority, label );	// Build a datagram, based on known information
            d.setOriginID(id);
            d.setTimestamp(Instant.now().toEpochMilli());

            if( !dQueue.add(d) ){
                Logger.error(id +" -> Failed to add data to the queue");
            }

		    if(debug)
			    Logger.info( d.getTitle()+" -> " + d.getMessage());
				   
            // Log anything and everything (except empty strings)
            if( !msg.isBlank() && log ) {        // If the message isn't an empty string and logging is enabled, store the data with logback
                Logger.tag("RAW").warn(priority + "\t" + label+"|"+id + "\t" + msg);
            }
			if( !targets.isEmpty() ){
                targets.stream().forEach(dt -> dt.writeLine( new String(data) ) );
                targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
			}
		
            long p = Instant.now().toEpochMilli() - timeStamp;	// Calculate the time between 'now' and when the previous message was received
            if( p > 0 ){	// If this time is valid
                passed = p; // Store it
            }                    
            timeStamp = Instant.now().toEpochMilli();    		// Store the timestamp of the received message
        }
	}
    public boolean writeString(String data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush(data.getBytes());
        return true;
    }
    public boolean writeLine(String data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush((data+eol).getBytes()); 
        return true;
    }
    public boolean writeBytes(byte[] data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush(data);
        return true;
    }
    public boolean disconnect(){
        if( channel != null ){
           // channel.close();
            channel.disconnect();
            return true;
        }
        return false;
    }
    public boolean isConnectionValid(){
        return channel!=null&&channel.isActive();
    }
}
