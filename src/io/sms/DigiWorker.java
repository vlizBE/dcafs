package io.sms;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.DeadThreadListener;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class DigiWorker implements Runnable, SMSSending{

	private TelnetClientHandler handler;
	private Channel digi;
	int connectTries = 1;
	
	private static final StringDecoder DECODER = new StringDecoder();
	private static final StringEncoder ENCODER = new StringEncoder();
	
	EventLoopGroup group = new NioEventLoopGroup();
	String server = "";
	String user = "";
	String pass = "";
	
	static final byte[] lfcr = {13,10};
	static final byte[] crlf = {10,13};
	static final byte[] cl =   {62};
	static final byte[] dp =   {58};
	
	static final ByteBuf[] crlfdpDeli    = { Unpooled.copiedBuffer(lfcr), Unpooled.copiedBuffer(crlf), Unpooled.copiedBuffer(dp) };
	
	boolean failed = false;
	
	private BlockingQueue<String[]> smsQueue = new LinkedBlockingQueue<>(); // Queue of sms's to transmit
	private final Map<String,String> to = new HashMap<>(); // Hashmap containing phone number vs reference
	private final ArrayList<String[]> retry = new ArrayList<>(); // Queue for retries
	protected DeadThreadListener listener;

	/**
	 * Constructor that just want's to know the server name
	 * @param server The name of the server (or ip)
	 */
	public DigiWorker( String server ){
		this.server = server;
	}
	/**
	 * Constructor for a server that requires login
	 * @param server The name of the server (or ip)
	 * @param user Username to log in
	 * @param pass Password to log ing
	 */
	public DigiWorker( String server, String user, String pass ){
		this.server = server;
		this.user=user;
		this.pass=pass;
	}
	/**
	 * Constructor if all the needed information is found in an xml file
	 * @param xml The XML document containing: server, login,pass etc
	 */
	public DigiWorker( Document xml ){
		readSettingsFromXML(xml);
	}
	/**
	 * Add a listener to be notified of the event the thread fails.
	 * 
	 * @param listener the listener
	 */
	public void setEventListener( DeadThreadListener listener ){
		this.listener=listener;
	}
	/**
	 * Get the queue that can be used to have this worker sends SMS
	 * 
	 * @return The queue for this worker
	 */
	public BlockingQueue<String[]> getQueue(){
		return smsQueue;
	}
	/**
	 * Set the queue this worker should use
	 * 
	 * @param queue The queue this worker should get it's work from
	 */
	public void setQueue( BlockingQueue<String[]> queue ){
		this.smsQueue = queue;
	}
	/**
	 * Retrieve the phone number vs references
	 * @return Get a list of all the stored phone numbers with their references
	 */
	public String getSMSBook( ){
		StringJoiner join = new StringJoiner("\r\n");

		join.add("-SMS Book-");
		to.forEach(
			(ref,phone) -> join.add( ref+" -> "+phone)
		);
		return join.toString();
	}
	/**
	 * Get the setup of the server connection
	 * @return Serven and username used
	 */
	public String getServerInfo(){
		StringJoiner join = new StringJoiner( "\r\n" );
		join.add("--SMS settings--");
		join.add("Server: "+server);
		join.add("User: "+user);
		return join.toString();
	}
	public void sendSMS( String to, String content ){
		smsQueue.add( new String[]{to, content.length() > 150 ? content.substring(0, 150) : content} );
	}
	@Override
	public void run() {
		
		Thread.currentThread().setContextClassLoader( getClass().getClassLoader() );		
		while( !Thread.currentThread().isInterrupted() ){
			try {
				String[] work = smsQueue.take();
				while( digi == null || !digi.isOpen() ){
					digi = startClient(); // try to establish the connection
					if( digi == null ){ // if failed
						TimeUnit.SECONDS.sleep(10L*connectTries);
						connectTries++; // Next time, wait 10s longer
						Logger.error("Couldn't connect to server, trying again in 20s");
					}
				}
				connectTries=1; // Reset the retries counter
				int cnt=0;
				while( handler.channel==null || !handler.channel.isWritable() ){
					TimeUnit.SECONDS.sleep(1); // Wait for the connection
					Logger.info("Waiting for connection to open...");
					cnt++;
					if( cnt == 25 ) // wait up to 25s, leave the while if reached
						break;
				}
				if( cnt == 25){
					Logger.error("Failed to connect to Digi Server.");
					continue;
				}
				//Trying to log in
				if( !confirmedSend(user,250,10) ){ 
					failed=true;					
				}else{
					if( !confirmedSend(pass,250,10) ){ // Try again
						failed=true;					
					}	
				}
				if( !work[0].startsWith("+")){// if a reference was given instead of a phone number
					work[0] = to.get(work[0]); // Get the phonenumber(s) associated with the reference
					if( work[0].indexOf(",")>0){ // The phonenumbers are delimited with a ','
						String[] subs = work[0].split(",");
						for( int x=1;x<subs.length;x++){
							smsQueue.add(new String[]{subs[x],work[1]}); // Add the split reference to the queue, skipping the first
						}
						work[0]=subs[0]; // Alter work to point to the first
					}
				}
				if( retry.size() > 1 ) // no idea why this is done
					retry.remove(0); 
				retry.add( work ); // Add this to retry queue incase transmit fails
				work[0] = work[0].replace( "+", "" );
				
				//Send SMS
				String tosend= work[0]+" \""+work[1]+'"';
				confirmedSend("sendsms "+tosend ,2500,10);
				//check reply
				TimeUnit.SECONDS.sleep(10); //Wait 10 seconds before trying to send next one
			} catch (InterruptedException e) {
				Logger.error("InterruptedException: "+e.getMessage());
			} 			
		}
		Logger.error("Managed to get out of while loop... ");
		listener.notifyCancelled("DigiWorker");
	}
	/**
	 * Check to see if the settings xml contains setup for the digiworker
	 * @param xml The XML document to check
	 * @return True if settings were found
	 */
	public static boolean inXML( Document xml){
		return XMLtools.getFirstElementByTag( xml, "digi") != null;
	}
	/**
	 * Read the setup from the provided xml document
	 * @param xml The xml to retrieve the info from
	 * @return True if setup was read
	 */
	public boolean readSettingsFromXML( Document xml){
		Element digiEle = XMLtools.getFirstElementByTag( xml, "digi");
		if( digiEle == null)
			return false;
		
		Element serverEle = XMLtools.getFirstChildByTag(digiEle, "server");
		assert serverEle != null;
		this.server = serverEle.getTextContent();
		this.user = XMLtools.getStringAttribute( serverEle, "user", "");
		this.pass = XMLtools.getStringAttribute( serverEle, "pass", "");
		Logger.info("Digiworker.readSettingsFromXML\tServer="+server+"\tUser="+user+"\tpassword="+pass);
		readSMSBook(digiEle);
		return true;
	}
	/**
	 * Read the email to reference listing in the settings xml from the provided element
	 * @param book The xml element containing the email information
	 * @return True if found
	 */
	public boolean readSMSBook( Element book ){
		if( book == null )
			return false;
		for( Element el : XMLtools.getChildElements( book, "refto" )){
        	if( el != null ){
		    	String ref = el.getAttribute("ref");
		    	if( ref !=null ){
		    		this.addTo(ref, el.getFirstChild().getNodeValue() );
		    	}
        	}
		}
		return true;
	}
	/**
	 * Add a phone number to an existing id/reference
	 * @param id The id/reference to add to
 	 * @param phoneNr The phone number to add
	 */
	public void addTo( String id, String phoneNr ){
		phoneNr = phoneNr.replace(";", ",");
		to.put(id, phoneNr);
		Logger.info("Changed "+id+" to "+to.get(id));
	}
	/**
	 * Send an sms request to the server
	 * @param line The text to send
	 * @param time The time to wait between attempts
	 * @param attempts The amount of attempts to execute
	 * @return True if succeeded
	 */
	private boolean confirmedSend( String line, long time, int attempts){
		handler.write(line);
		while( !handler.isReady()){ 
			try {
				Thread.sleep(time);
				int l = line.length();
				if( attempts < 5 )
					Logger.info("Waiting for reply to "+line.substring(0, Math.min(l, 30))+" (cut to 30 chars) ("+attempts+")");
				attempts --;
				if( attempts == 0)
					return false;
			} catch (InterruptedException e) {
				Logger.error(e);
			} 			
		}
		return true;
	}
	/**
	 * Stop the processing thread, used mainly for debugging (checking if it recovers)
	 */
	public void stopWorker(){
		Thread.currentThread().interrupt();
	}
	/**
	 * Start the telnet client that will talk to the digi telnet server
	 */
	private Channel startClient(){		
		handler = new TelnetClientHandler();

		Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
	         @Override
	         public void initChannel(SocketChannel ch) {
	        	 	 ch.pipeline().addLast(new DelimiterBasedFrameDecoder(1024, crlfdpDeli));
	              	 ch.pipeline().addLast("decoder", DECODER);
	               	 ch.pipeline().addLast("encoder", ENCODER);
	               	 ch.pipeline().addLast( handler );	                     
	           }
	          });		       
        try {// Start the connection attempt.
			return b.connect(server, 23).sync().channel();
		} catch (InterruptedException e) {
			Logger.error(e);				
		} 
        return null;
	}
	/**
	 * Handler class that processes incoming and outgoing messages.
	 */
	private class TelnetClientHandler extends ChannelInboundHandlerAdapter {
	    Channel channel;
	    boolean ready =false;
	    
	    @Override
	    public void channelActive(ChannelHandlerContext ctx) {
			Logger.info("Channel Active: "+ctx.channel().remoteAddress() );
	       	channel = ctx.channel();
		}
		@Override
    	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			Logger.error("Exception caught -> "+cause.toString());
		}
	    @Override
	    public void channelRead(ChannelHandlerContext ctx, Object msg) {
	    	if( msg.toString().startsWith("SMS send failure")){
	    		Logger.error("Failed to send SMS");
	    		// Retry in a minute?
	    		smsQueue.add( retry.remove(0) );
	    	}else if(msg.toString().startsWith("SMS send success") ){
				Logger.info("Send SMS OK");
			}else{
	    		Logger.info( msg );
	    	}
	    	ready=true;
	    }
	    public void write(String line){
	    	ready = false;	    	
	    	channel.writeAndFlush(line+"\r\n");	    	
	    }
	    public boolean isReady(){
	    	return ready;
	    }
	}
}
