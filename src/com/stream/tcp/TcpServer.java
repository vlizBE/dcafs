package com.stream.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;

import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import util.xml.XMLfab;
import util.tools.*;

import util.xml.XMLtools;
import worker.Datagram;

import com.stream.StreamListener;
import com.stream.Writable;

import org.tinylog.Logger;

public class TcpServer implements StreamListener {

	private BlockingQueue<Datagram> dQueue; // TransHandler can also process other commands if given this queue

	private int serverPort = 5542; // The port the server is active on default is 5542
	private ChannelFuture serverFuture;

	private final Path xmlPath;

	private final HashMap<String,TransDefault> defaults = new HashMap<>();

	private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	private final EventLoopGroup workerGroup;

	private final ArrayList<TransHandler> clients = new ArrayList<>();
	static final String XML_PARENT_TAG = "transserver";

	public TcpServer(Path xml, EventLoopGroup workerGroup) {
		this.workerGroup = workerGroup;
		xmlPath = xml;
		readSettingsFromXML(XMLtools.readXML(xmlPath), false);
	}

	public TcpServer(Path xml) {
		this(xml, new NioEventLoopGroup());
	}

	/**
	 * Change the port of the server
	 * 
	 * @param port The new port to use
	 */
	public void setServerPort(int port) {
		if (this.serverPort != port && port != -1) {
			Logger.info("New port isn't the same as current one. current=" + this.serverPort + " req=" + port);
			serverPort = port;
			alterXML();
			restartServer();
		}
	}
	/**
	 * Set the queue worked on by a @see BaseWorker
	 * 
	 * @param dQueue The queue from the BaseWorker
	 */
	public void setDataQueue(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
	}

	/**
	 * Checks whether or not StreamPool info can be found in the settings file.
	 * 
	 * @param xml The settings file
	 * @return True if settings were found
	 */
	public static boolean inXML(Document xml) {
		return XMLtools.getFirstElementByTag(xml, XML_PARENT_TAG) != null;
	}

	public boolean readSettingsFromXML(Document xml, boolean run) {
		Element tran = XMLtools.getFirstElementByTag(xml, XML_PARENT_TAG);
		if (tran != null) {
			Logger.info("Settings for the TransServer found.");
			this.serverPort = XMLtools.getIntAttribute(tran, "port", -1);
			if (this.serverPort == -1)
				this.serverPort = XMLtools.getChildIntValueByTag(tran, "port", 5542);
			defaults.clear();
			for( Element client : XMLtools.getAllElementsByTag(xml, "default")){
				TransDefault td = new TransDefault(client.getAttribute("id"),client.getAttribute("address"));
				XMLtools.getChildElements(client, "cmd").forEach( req -> td.addCommand( req.getTextContent() ) );
				defaults.put(td.id, td );
			}
			if (run)
				run();
		} else {
			Logger.warn("No settings for the transserver found, creating defaults!");
			alterXML();

			if (run)
				run();
			return false;
		}
		return true;
	}

	public void alterXML() {
		Logger.warn("Altering the XML");

		XMLfab fab = XMLfab.withRoot(xmlPath, "settings", XML_PARENT_TAG).clearChildren().attr("port", this.serverPort);

		// Adding the clients
		for (Entry<String,TransDefault> cip : defaults.entrySet()) {
			fab.addChild("default").attr("address", cip.getValue().ip).attr("id", cip.getKey()).down();
			cip.getValue().getCommands().forEach(fab::addChild);
		}
		fab.build();
	}

	/* ********************************************************************************************************** **/
	public void run() {
		run(this.serverPort);
	}

	private void run(int port) {
		this.serverPort = port;
		// Netty
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 50)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch){
							ch.pipeline().addLast("framer",
									new DelimiterBasedFrameDecoder(512, true, Delimiters.lineDelimiter())); // Max 512
																											// char,
																											// strip
																											// delimiter
							ch.pipeline().addLast("decoder", new ByteArrayDecoder());
							ch.pipeline().addLast("encoder", new ByteArrayEncoder());

							TransHandler handler = new TransHandler("system", dQueue);
							handler.setListener(TcpServer.this);
							clients.add(handler);

							ch.pipeline().addLast(handler);
						}
					});

			// Start the server.
			Logger.info("Starting TransServer on port " + port + " ...");
			serverFuture = b.bind(port);
			serverFuture.addListener((ChannelFutureListener) future -> {
				if (!future.isSuccess()) {
					Logger.error("Failed to start the TransServer (bind issue?)");
					System.exit(0);
				} else if (future.isSuccess()) {
					Logger.info("Started the TransServer.");
				}
			});
			serverFuture.sync();
		} catch (InterruptedException e) {
			Logger.error(e);
			// Restore interrupted state...
			Thread.currentThread().interrupt();
		}
	}

	private void restartServer() {
		if (serverFuture != null) {
			serverFuture.cancel(true);
			run();
		}
	}

	/* ******************** * D E F A U L T  */
	@Override
	public void notifyIdle(String title) {
		// Not used for server
	}

	@Override
	public boolean notifyActive(String title) {
		Logger.info(title+" is active");
		for( TransHandler th : clients ){
			if( th.getID().equalsIgnoreCase(title) ){
				Logger.info("Found matching id "+title);
				for( TransDefault td : defaults.values() ){
					if( td.ip.equalsIgnoreCase(th.getIP())){
						th.writeHistory(td.commands);
						th.setID(td.id);
					}
				}
				th.writeLine("Welcome back "+th.getID()+"!");
				return true;
			}else{
				Logger.info("No matching ID "+title+" vs "+th.getID() );
			}
		}
		return false;
	}

	@Override
	public void notifyOpened(String id) {
		Logger.info(id+" is opened");
	}

	/**
	 * Used for the handler to inform the listener that its connection is closed
	 * @param id The id of the handler
	 */
	@Override
	public void notifyClosed(String id) {
		Logger.info(id+" is closed");
		
		if( clients.removeIf( client -> client.getID().equalsIgnoreCase(id) ) ){
			Logger.info("Removed client handler for "+id);
		}else{
			Logger.info("Couldn't find handler for "+id);
		}
	}

	/**
	 * Handler can use this request to have the server reconnect its connection
	 * @param id The id of the handler
	 * @return True if successful
	 */
	@Override
	public boolean requestReconnection(String id) {
		return false;
	}

	private Optional<TransHandler> getHandler( String id){
		int index = Tools.parseInt(id, -1);
		if( index != -1 ){
			return Optional.ofNullable(clients.get(index));
		}
		return clients.stream().filter( h->h.getID().equalsIgnoreCase(id)).findFirst();
	}

	/**
	 * Store the TransHandler information in the xml file as a default
	 * @param handler The handler to store
	 * @param wr The writable that issued the command
	 */
	public void storeHandler( TransHandler handler, Writable wr ){
		XMLfab fab = XMLfab.withRoot(xmlPath, "settings", XML_PARENT_TAG);
		if( fab.selectParent("default", "id", handler.getID() ).isPresent() ){
			fab.clearChildren();
		}else{
			fab.addChild("default").attr("address",handler.getIP()).attr("id",handler.getID()).down();
		}
		for( String h : handler.getHistory() ){
			fab.addChild("cmd",h);
		}
		if( fab.build() != null ){
			wr.writeLine(handler.getID()+" Stored!");
		}else{
			wr.writeLine("Storing "+handler.getID()+" failed");
		}
	}

	/**
	 * Get a list of all the clients currently connected to the server
	 * @return the list with format index -> id -> history
	 */
	public String getClientList(){
		StringJoiner join = new StringJoiner("\r\n");
		join.setEmptyValue("No connections yet.");
		int index=0;

		for( TransHandler th : clients ){
			join.add( index +" -> "+th.getID()+" --> "+th.getHistory(" "));
			index++;
		}
		return join.toString();
	}

	/**
	 * Execute and reply to commands given in as a readable string
	 * @param req the command
	 * @param wr the writable to send the answer to
	 * @return the reply
	 */
	public String replyToRequest(String req, Writable wr) {
		String[] cmd = req.split(",");

		if( cmd[0].equals("create"))
			return "Server already exists";

		switch( cmd[0] ){
			case "?": 
				return "trans:store,id/index -> Store the session in the xml\r\n"+
						"trans:add,id/index,cmd -> Add the cmd to the id/index\r\n"+
						"trans:clear,id/index -> Clear all cmds from the client id/index\r\n"+
						"trans:list -> List of all the connected clients\r\n"+
						"trans:defaults -> List of all the defaults\r\n"+
						"trans:reload -> Reload the xml settings.\r\n";
			case "store":
				var hOpt = getHandler(cmd[1]);
				if( hOpt.isEmpty() )
					return "Invalid id";
				var handler = hOpt.get();
				handler.setID(cmd.length==3?cmd[2]:handler.getID());
				storeHandler(handler,wr);
				return "";
			case "add":
				return getHandler(cmd[1]).map( h -> {
					StringJoiner join = new StringJoiner(",");
					for( int a=2;a<cmd.length;a++ )
						join.add(cmd[a]);
					h.addHistory(join.toString());
					return cmd[1]+"  added "+join.toString();}).orElse("No such client: "+cmd[1]);
			case "clear":
				return getHandler(cmd[1]).map( h -> {
					h.clearRequests();
					return cmd[1]+"  cleared.";}).orElse("No such client: "+cmd[1]);
			case "defaults":
				StringJoiner lines = new StringJoiner("\r\n");
				defaults.forEach( (id,val) -> lines.add(id+" -> "+val.ip+" => "+String.join(",",val.commands)));
				return lines.toString();
			case "reload":
				if( readSettingsFromXML(XMLtools.readXML(xmlPath),false) )
					return "Defaults reloaded";
				return "Reload failed";
			case "": case "list": 
				return "Server running on port "+serverPort+"\r\n"+getClientList();
			default: 
				return "Unknown command "+req;
		}
	}

	/**
	 * Class to hold the info regarding a default, this will allow history of a connection to be reproduced.
	 * Eg. if the default is stored with calc:clock, this will be applied if the that client connects again
	 */
	public static class TransDefault{
		String ip;
		String id;
		ArrayList<String> commands = new ArrayList<>();

		public TransDefault( String id, String ip ){
			this.ip=ip;
			this.id=id;
		}
		public boolean addCommand( String cmd ){
			if( commands.contains(cmd))
				return false;
			return commands.add(cmd);
		}
		public List<String> getCommands(){return commands;}
	}
}