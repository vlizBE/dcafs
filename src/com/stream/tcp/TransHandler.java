package com.stream.tcp;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.stream.StreamListener;
import com.stream.Writable;

import org.tinylog.Logger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import worker.Datagram;

public class TransHandler extends SimpleChannelInboundHandler<byte[]> implements Writable{

	private final BlockingQueue<Datagram> dQueue;

	private String id="";
	private String label;

	private StreamListener listener;

	private Channel channel;

	private InetSocketAddress remote;

	private final List<Writable> targets = new ArrayList<>();
    
	private static final String eol="\r\n";
	private final ArrayList<String> history = new ArrayList<>();

    public TransHandler( String label, BlockingQueue<Datagram> dQueue ){
        this.label=label;
        this.dQueue=dQueue;
    }

	/**
	 * Set the id of this handler
	 * @param id The id to set
	 */
	public void setID(String id){
		this.id=id;
	}

	/**
	 * Set the listener of the server here
	 * @param listener The listener aka server
	 */
	public void setListener( StreamListener listener){
        this.listener=listener;
    }
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			Logger.info(id+ " triggered "+evt);
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {

		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// If the remote address is not null
			remote = (InetSocketAddress)ctx.channel().remoteAddress();	// Store this as remote address
			Logger.info("Connection established to "+remote.getHostName());
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}

		if( listener != null )
			listener.notifyActive(getID());
		
		if( channel != null ){    	  // If the channel is valid, add a future listener
			
			channel.flush();
			channel.writeAndFlush("Hello?\r\n".getBytes());

           	ChannelFuture closeFuture = channel.closeFuture();           
           	closeFuture.addListener((ChannelFutureListener) future -> {
					future.cancel(true);

					if( channel!=null)
						channel.close();

					if( listener != null ){
						listener.notifyClosed(getID());
					}
				});
        }else{
    	    Logger.error( "Channel is null. (handler:"+getID()+")");
		} 
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {    
		// Not used 
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
		//Not used
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {
       
       	String msg = new String( data );	// Convert the raw data to a readable string

       	if ( !(msg.isBlank() )) { //make sure that the received data is not 'null' or an empty string
			msg = msg.replace("\n", "");   // Remove newline characters
			msg = msg.replace("\r", "");   // Remove carriage return characters
			msg = msg.replace("\0","");    // Remove null characters

			if( msg.startsWith(">>>") ){
			   	msg = msg.substring(3);
			   	if( msg.startsWith("label:") ){
					this.label=msg.split(":")[1];
					writeLine("Altered label to "+label);	
					return;				
				}
				if( msg.startsWith("id:") ){
					this.id=msg.split(":")[1];
					writeLine("Altered id to "+id);	
					return;				
				}
				if( msg.startsWith("store") ){
					// Somehow save to xml?
					writeLine("Stored setup to xml");
					msg="trans:store,"+getID();
				}
				switch( msg ){
					case "id?": 	writeLine("id is "+id);		break;
					case "label?":  writeLine("label is "+id);	break;
					default: Logger.warn("Unknown message "+msg+" for "+id); break;
				}								 
			}else{
				history.add(msg);
			}		   

		   
			Datagram d = new Datagram( this, data, msg, 1, label );	// Build a datagram, based on known information
			d.setOriginID(id);

			if( !targets.isEmpty() ){
				targets.stream().forEach(dt -> dt.writeLine( new String(data) ) );
				targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
			}
			
		   	dQueue.put(d);
	   }
	}

	/**
	 * Write the given data without changing it
	 * @param data The data to write
	 * @return True if the channel was ok
	 */
    public boolean writeString(String data) {
        if( channel==null)
            return false;
        channel.writeAndFlush(data.getBytes());
        return true;
    }
	/**
	 * Write the given data and eol
	 * @param data The data to write
	 * @return True if the channel was ok
	 */
    public boolean writeLine(String data) {
        if( channel==null)
            return false;
        channel.writeAndFlush((data+eol).getBytes());
        return true;
    }

	/**
	 * Disconnect the handler
	 * @return True if disconnected
	 */
	public boolean disconnect(){
        if( channel != null ){
            channel.close();
        }else{
        	return false;
		}
        return true;
    }

	/**
	 * Check if the connection is valid
	 * @return True if it's still active
	 */
	public boolean isConnectionValid(){
        return channel!=null && channel.isActive();
    }

	/**
	 * Get the IP where this connection originates from
	 * @return The ip
	 */
	public String getIP(){
		return remote.getAddress().getHostAddress();
	}

	/**
	 * Add a command to the history of this handler and execute it
	 * @param cmd The command to add
	 */
	public void addHistory( String cmd ){
		history.add(cmd);
		Logger.info(id+" -> Adding history: "+cmd);
		useQueue(cmd);
	}

	/**
	 * Add a list of commands to the history of this handler and execute them
	 * @param data The List containing the commands
	 */
	public void writeHistory( List<String> data ){
		data.forEach( this::addHistory );
	}
	@Override
	public String getID() {
		if( id.isEmpty() ){
			return remote.getHostName();
		}
		return id;
	}
	public void clearRequests(){
		useQueue("nothing");
	}

	/**
	 * Send a command to be processed
	 * @param cmd The command to execute
	 */
	private void useQueue(String cmd){
		Datagram d = new Datagram( cmd, 1, "system" );	// Build a datagram, based on known information
		d.setOriginID(id);
		d.setWritable(this);
		d.toggleSilent();
		dQueue.add(d);
	}
	@Override
	public Writable getWritable() {
		return this;
	}

	/**
	 * Get a list of this handlers history
	 * @param delimiter The delimiter to use between elements
	 * @return Delimited listing of the handlers history
	 */
	public String getHistory(String delimiter){
		return String.join(delimiter, history);
	}

	/**
	 * This handlers history
	 * @return The history List
	 */
	public List<String> getHistory(){
		return history;
	}
}
