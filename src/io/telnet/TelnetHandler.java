package io.telnet;

import das.Configurator;
import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class TelnetHandler extends SimpleChannelInboundHandler<byte[]> implements Writable {
	
	protected BlockingQueue<Datagram> dQueue;								// Queue that receives raw data for processing
	
	/* Pretty much the local descriptor */
	protected static final String LABEL = "telnet";			// The label that determines what needs to be done with a message
	protected Channel channel;	// The channel that is handled
	protected String remoteIP = "";		// The ip of the handler
	private InetSocketAddress remote;

	protected String newLine = "\r\n";			// The string to end the messages send with		
	protected String lastSendMessage="";			// The last message that was send

	/* OTHER */
	protected ArrayList<String> ignoreIP= new ArrayList<>();	// List of IP's to ignore, not relevant for StreamHandler, but is for the telnet implementation
	protected boolean clean=true;	// Flag that determines if null characters etc need to be cleaned from a received message
	protected boolean log=true;	// Flag that determines if raw data needs to be logged

	private Path settingsPath;
	private HashMap<String,String> macros = new HashMap<>();

 	String repeat = "";
	String title = "dcafs";
	String id="";
	String start="";

	boolean config=false;
	Configurator conf=null;

	CommandLineInterface cli;

	/* ****************************************** C O N S T R U C T O R S ******************************************* */
	/**
	 * Constructor that requires both the BaseWorker queue and the TransServer queue
	 * 
	 * @param dQueue the queue from the @see BaseWorker
	 * @param ignoreIPlist list of ip's to ignore (meaning no logging)
	 */
    public TelnetHandler(BlockingQueue<Datagram> dQueue, String ignoreIPlist, Path settingsPath){
		this.dQueue = dQueue;
		ignoreIP.addAll(Arrays.asList(ignoreIPlist.split(";")));
		ignoreIP.trimToSize();
		this.settingsPath=settingsPath;
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
			remote = (InetSocketAddress)ctx.channel().remoteAddress();;	// Store this as remote address
			remoteIP = remote.getAddress().getHostAddress();
			if( remote.getAddress() instanceof Inet4Address){
				Logger.info("IPv4: "+((Inet4Address)remote.getAddress()));
			}else{
				Logger.info("IPv6: "+((Inet6Address)remote.getAddress()));
			}

			XMLfab.withRoot(settingsPath,"dcafs","settings","telnet")
					.selectChildAsParent( "client","host",remote.getHostName())
					.ifPresent( f -> {
						id = f.getCurrentElement().getAttribute("id");
						start = f.getChild("start").map(c -> c.getTextContent()).orElse("");
						for( var c : f.getChildren("macro")){
							macros.put( c.getAttribute("ref"),c.getTextContent());
						}
					});

			id = XMLfab.withRoot(settingsPath,"dcafs","settings","telnet")
					.selectChildAsParent( "client","host",remote.getHostName())
					.map( f -> f.getCurrentElement().getAttribute("id")).orElse("");

		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}

		cli = new CommandLineInterface(channel); // Start the cli

		if( id.isEmpty()) {
			writeString(TelnetCodes.TEXT_RED + "Welcome to " + title + "!\r\n" + TelnetCodes.TEXT_RESET);
		}else{
			writeString(TelnetCodes.TEXT_RED + "Welcome back " + id + "!\r\n" + TelnetCodes.TEXT_RESET);
		}
		writeString( TelnetCodes.TEXT_GREEN + "It is " + new Date() + " now.\r\n"+TelnetCodes.TEXT_RESET);
		writeString( TelnetCodes.TEXT_BRIGHT_BLUE+"> Common Commands: [h]elp,[st]atus, rtvals, exit...\r\n");
		writeString( TelnetCodes.TEXT_BRIGHT_YELLOW +">");
		channel.flush();
		if( !start.isEmpty() ){
			dQueue.add( Datagram.build(start).label(LABEL).writable(this).origin("telnet:"+channel.remoteAddress().toString()));
		}
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {   
		Logger.debug("Not implemented yet - channelRegistered");
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
		Logger.debug("Not implemented yet - channelUnregistered");
		dQueue.add( Datagram.system("nb").writable(this)); // Remove this from the writables when closed
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {

		var recOpt= cli.receiveData(data);

		if( recOpt.isEmpty())
			return;

		var rec = recOpt.get();

		if( config ){ // Config mode
			String reply = conf.reply(new String(rec));
			if( !reply.equalsIgnoreCase("bye") ) {
				if( !reply.isEmpty())
					writeLine(reply);
				return;
			}
			config=false;
			writeLine( TelnetCodes.TEXT_BLUE+"Bye! Back to telnet mode..."+TelnetCodes.TEXT_YELLOW);
			writeString(">");
			return;
		}else if( new String(rec).equalsIgnoreCase("cfg")){
			config=true;
			conf = new Configurator( settingsPath,this ); // Always start with a new one
			return;
		}

		distributeMessage(
				Datagram.build(rec)
						.label(LABEL)
						.writable(this)
						.origin("telnet:"+channel.remoteAddress().toString())
						.timestamp() );
	}

	public void distributeMessage( Datagram d ){
		d.label( LABEL+":"+repeat );

		if( d.getData().endsWith("!!") ) {
			if( d.getData().length()>2) {
				repeat = d.getData().replace("!!", "");
				writeString("Mode changed to '"+repeat+"'\r\n>");
				return;
			}else {
				d.label(LABEL);
				repeat="";
				writeString("Mode cleared!\r\n>");
				return;
			}
		}else if( d.getData().startsWith(">>")) {
			var split = new String[2];
			if( !d.getData().contains(":")){
				writeLine("Missing :");
				return;
			}
			String cmd = d.getData().substring(2);
			split[0] = cmd.substring( 0,cmd.indexOf(":"));
			split[1] = cmd.substring(split[0].length()+1);

			switch( split[0] ){
				case "id":
					id=split[1];
					writeString(
						XMLfab.withRoot(settingsPath,"dcafs","settings","telnet").noChild("client","id",id)
							.map( f -> {
										f.alterChild("client","host",remote.getHostName())
										.attr("id",id).build();
										return "ID changed to "+id+"\r\n>";
									}).orElse("ID already in use")
						);
					return;
				case "talkto":
					writeString("Talking to "+split[1]+", send !! to stop\r\n>");
					repeat = "telnet:write,"+split[1]+",";
					return;
				case "start":
					if( id.isEmpty() ){
						writeLine("Please set an id first with >>id:newid");
						return;
					}
					start = split[1];
					writeString("Startup command has been set to '"+start+"'");

					writeLine( XMLfab.withRoot(settingsPath,"dcafs","settings","telnet").selectChildAsParent("client","id",id)
							.map( f -> {
								f.addChild("start",split[1]);
								return "Start set to "+id+"\r\n>";
							}).orElse("Couldn't find the node"));
					return;
				case "macro":
					if( !split[1].contains("->")) {
						writeLine("Missing ->");
						return;
					}
					var ma = split[1].split("->");
					writeString( XMLfab.withRoot(settingsPath,"dcafs","settings","telnet").selectChildAsParent("client","id",id)
							.map( f -> {
								f.addChild("macro",ma[1]).attr("ref",ma[0]).build();
								macros.put(ma[0],ma[1]);
								return "Macro "+ma[0]+" replaced with "+ma[1]+"\r\n>";
							}).orElse("Couldn't find the node\r\n>"));
					return;
				default:
					writeLine( "Unknown telnet command: "+d.getData());
					return;
			}
		}else{
			d.setData(repeat+d.getData());
		}
		var macro = macros.get(d.getData());
		if( macro!=null)
			d.setData(macro);
		if ( d.getData().equalsIgnoreCase("bye")||d.getData().equalsIgnoreCase("exit")) {
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
		dQueue.add( Datagram.system("nb"));
		if (cause instanceof TooLongFrameException){	
			Logger.warn("Unexpected exception caught"+cause.getMessage()+" "+addr, true); 
			ctx.flush();
		}
	}
	
	/* ***************************************************************************************************************/
	/* *************************************** W R I T A B L E  ******************************************************/
	/* ***************************************************************************************************************/
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
	/* ***********************************************************************************************************/
	/* ****************************************** I N H E R I T A N C E ******************************************/
	/* ***********************************************************************************************************/
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

	/* ***********************************************************************************************************/
	/* ************************************* S T A T U S *********************************************************/
	/* ***********************************************************************************************************/
	/**
	 * Get the channel object
	 * @return The channel
	 */
	public Channel getChannel(){
		return channel;
	}
	@Override
	public String getID() {
		return id.isEmpty()?LABEL:id;
	}


}