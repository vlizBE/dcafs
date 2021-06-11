package com.telnet;

import com.stream.Writable;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import org.tinylog.Logger;
import util.tools.Tools;
import worker.Datagram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.BlockingQueue;

public class TelnetHandler extends SimpleChannelInboundHandler<byte[]> implements Writable {
	
	protected BlockingQueue<Datagram> dQueue;								// Queue that receives raw data for processing
	
	/* Pretty much the local descriptor */
	protected static final String LABEL = "telnet";			// The label that determines what needs to be done with a message
	protected Channel channel;	// The channel that is handled
	protected String remoteIP = "";		// The ip of the handler

	protected String newLine = "\r\n";			// The string to end the messages send with		
	protected String lastSendMessage="";			// The last message that was send

	/* OTHER */
	protected ArrayList<String> ignoreIP= new ArrayList<>();	// List of IP's to ignore, not relevant for StreamHandler, but is for the telnet implementation
	protected boolean clean=true;	// Flag that determines if null characters etc need to be cleaned from a received message
	protected boolean log=true;	// Flag that determines if raw data needs to be logged
	
	protected static final byte[] EOL = new byte[]{13,10};

	String repeat = "";
	String title = "dcafs";
	String mode ="";
	byte[] last={'s','t'};
	/* ****************************************** C O N S T R U C T O R S ********************************************/
	/**
	 * Constructor that requires both the BaseWorker queue and the TransServer queue
	 * 
	 * @param dQueue the queue from the @see BaseWorker
	 * @param ignoreIPlist list of ip's to ignore (meaning no logging)
	 */
    public TelnetHandler(BlockingQueue<Datagram> dQueue, String ignoreIPlist){
		this.dQueue = dQueue;
		ignoreIP.addAll(Arrays.asList(ignoreIPlist.split(";")));
		ignoreIP.trimToSize();	
	}

	/* ************************************** N E T T Y  O V E R R I D E S ********************************************/

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		Logger.info("Not implemented yet - user event triggered");
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
    	
		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// Incase the remote address is not null
			remoteIP = ctx.channel().remoteAddress().toString();	// Store this as remote address
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}   
		
		writeString( TelnetCodes.TEXT_RED + "Welcome to "+title+"!\r\n"+TelnetCodes.TEXT_RESET);
		writeString( TelnetCodes.TEXT_GREEN + "It is " + new Date() + " now.\r\n"+TelnetCodes.TEXT_RESET);
		writeString( TelnetCodes.TEXT_BRIGHT_BLUE+"> Common Commands: [h]elp,[st]atus, rtvals, exit...\r\n");
		writeString( TelnetCodes.TEXT_YELLOW +">");
		channel.flush();
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {   
		Logger.debug("Not implemented yet - channelRegistered");
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
		Logger.debug("Not implemented yet - channelUnregistered");
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {

    	if( data.length!=0 ) {
			if (data[0] == -1) { // meaning client-server negotiation -1 = 0xFF
				int offset = 3;
				while (data.length > offset && data[offset] == -1) {
					offset += 3;
				}
				data = Arrays.copyOfRange(data, offset, data.length);
				Logger.debug("Removed control sets: " + offset / 3);
			}
			if (data.length >= 1) {
				Logger.debug(Tools.fromBytesToHexString(data));
			}
			if (data.length == 3 && data[0] == 27 && data[1] == '[') { // replace up arrow with last command
				switch (data[2]) {
					case 'A':
						data = last;
						break; // Up arrow
					case 'B':
						break; // Down arrow
					case 'C':
						break; // Right arrow
					case 'D':
						break; // Left arrow
				}
			} else { // keep last command if it wasn't the up arrow
				last = data;
			}
		}
		Datagram d = new Datagram( data, 1, LABEL );	// Build a datagram, based on known information
		d.setOriginID("telnet:"+channel.remoteAddress().toString());
		d.setTimestamp(Instant.now().toEpochMilli());		   
		d.setWritable(this);

		distributeMessage( d );	// What needs to be done with the received data
	}
	public void distributeMessage( Datagram d ){
		d.setLabel( LABEL+":"+repeat );
		d.setWritable(this);

		if( d.getMessage().endsWith("!!") ) {
			if( d.getMessage().length()>2) {
				repeat = d.getMessage().replace("!!", "");
				d.setLabel( "telnet:"+repeat);	
				this.writeString("Mode changed to '"+repeat+"'\r\n");
				return;
			}else {
				d.setLabel(LABEL);
				repeat="";
				this.writeString("Mode cleared!\r\n>");
				return;
			}
		}else {
			d.setMessage(repeat+d.getMessage());
		}
		
		if ( d.getMessage().equalsIgnoreCase("bye")||d.getMessage().equalsIgnoreCase("exit")) {
			// Close the connection after sending 'Have a good day!' if the client has sent 'bye' or 'exit'.
			ChannelFuture future = channel.writeAndFlush( "Have a good day!\r\n");   			
			future.addListener(ChannelFutureListener.CLOSE);
        } else {
			dQueue.add(d);
        }
	}
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
       ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised, but don't send messages if it's related to remote ignore	
		String addr = ctx.channel().remoteAddress().toString();

		if (cause instanceof TooLongFrameException){	
			Logger.warn("Unexpected exception caught"+cause.getMessage()+" "+addr, true); 
			ctx.flush();
		}
	}
	
	/**********************************************************************************************************************/
	/**************************************** S E N D I N G  D A T A ******************************************************/
	/**********************************************************************************************************************/
	/**
	 * Sending data that will be appended by the default newline string.
	 * @param message The data to send.
	 * @return True If nothing was wrong with the connection
	 */
	public synchronized boolean writeLine( String message ){
		return writeString( message + newLine );
	}
	/**
	 * Sending data that won't be appended with anything
	 * @param message The data to send.
	 * @return True If nothing was wrong with the connection
	 */
	public synchronized boolean writeString( String message ){					
		if( channel != null && channel.isActive()){			
			channel.writeAndFlush(message.getBytes());
			lastSendMessage = message;	// Store the message for future reference		
			return true;
		}
		return false;
	}
	public synchronized boolean writeBytes( byte[] data ){
		if( channel != null && channel.isActive()){
			channel.writeAndFlush(data);
			lastSendMessage = new String(data);	// Store the message for future reference
			return true;
		}
		return false;
	}
	/************************************************************************************************************/
	/******************************************* I N H E R I T A N C E ******************************************/
	/************************************************************************************************************/
	/**
	 * Add an ip to the ignore list, mostly used to prevent checkers to flood the logs with status messages 
	 * @param ip The IP to ignore
	 */
	public void addIgnoreIP( String ip ){
		ignoreIP.add(ip);
	}
	/**
	 * Check to see if an ip is part of the ignore list
	 * @param ip The IP to check
	 * @return True if it is, false if not
	 */
	protected boolean notIgnoredIP( String ip ){
		ip=ip.substring(1);
		for( String ignore : ignoreIP ){
			if( ip.startsWith(ignore) && !ignore.isBlank() )
				return false;
		}
		return true;
	}
	/**
	 * Change the title of the handler, title is used for telnet client etc representation
	 * @param title The new title
	 */
	public void setTitle( String title ) {
    	this.title=title;
	}
	/**
	 * Get the title of the handler
	 * @return The title
	 */
	public String getTitle( ){
		return title;
	}

	/************************************************************************************************************/
	/************************************** S T A T U S *********************************************************/
	/************************************************************************************************************/
	/**
	 * Get the channel object
	 * @return The channel
	 */
	public Channel getChannel(){
		return channel;
	}
	@Override
	public String getID() {
		return LABEL;
	}

	@Override
	public boolean isConnectionValid() {
		if( channel==null)
			return false;
		return channel.isActive();
	}

	@Override
	public Writable getWritable() {
		return this;
	}
}