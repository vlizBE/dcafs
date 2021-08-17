package io.stream.tcp;

import io.stream.StreamListener;
import io.Writable;
import das.Commandable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;

public class TcpServer implements StreamListener, Commandable {

	private BlockingQueue<Datagram> dQueue; // TransHandler can also process other commands if given this queue

	private int serverPort = 5542; // The port the server is active on default is 5542
	private ChannelFuture serverFuture;

	private final Path xmlPath;

	private final HashMap<String,TransDefault> defaults = new HashMap<>();

	private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	private final EventLoopGroup workerGroup;

	private final ArrayList<TransHandler> clients = new ArrayList<>();
	static final String XML_PARENT_TAG = "transserver";
	private final HashMap<String,ArrayList<Writable>> targets = new HashMap<>();

	private boolean active;

	public TcpServer(Path xml, EventLoopGroup workerGroup) {
		this.workerGroup = workerGroup;
		xmlPath = xml;

		active = readSettingsFromXML(XMLtools.readXML(xmlPath), false);
	}

	public TcpServer(Path xml) {
		this(xml, new NioEventLoopGroup());
	}

	/**
	 * Change the port of the server
	 * 
	 * @param port The new port to use
	 */
	public boolean setServerPort(int port) {
		if (this.serverPort != port && port != -1) {
			Logger.info("New port isn't the same as current one. current=" + this.serverPort + " req=" + port);
			serverPort = port;
			alterXML();
			restartServer();
			active=true;
		}
		return active;
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
	 * Checks whether or not StreamManager info can be found in the settings file.
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
			serverPort = XMLtools.getIntAttribute(tran, "port", -1);
			if (serverPort == -1)
				serverPort = XMLtools.getChildIntValueByTag(tran, "port", 5542);
			defaults.clear();
			for( Element client : XMLtools.getAllElementsByTag(xml, "default")){
				TransDefault td = new TransDefault(client.getAttribute("id"),client.getAttribute("address"));
				td.setLabel( XMLtools.getStringAttribute(client,"label","system"));
				XMLtools.getChildElements(client, "cmd").forEach( req -> td.addCommand( req.getTextContent() ) );
				defaults.put(td.id, td );
			}
			if (run)
				run();
			return true;
		}
		return false;
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

	/**
	 * Restart the server
	 */
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
						th.setLabel(td.label);
						th.writeLine("Welcome back "+th.getID()+"!");
						break;
					}
				}
				if(targets.containsKey(th.getID())){
					targets.get(th.getID()).forEach( th::addTarget );
				}
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

		fab.selectOrAddChildAsParent("default","id",handler.getID());
		fab.attr("address",handler.getIP());
		if( handler.getLabel().equalsIgnoreCase("system")){
			fab.removeAttr("label");
		}else{
			fab.attr("label",handler.getLabel() );
		}
		fab.clearChildren(); // start from scratch
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
	 * @param request the command
	 * @param wr the writable to send the answer to
	 * @return the reply
	 */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {
		String[] cmds = request[1].split(",");

		if( !active ) {
			if(cmds[0].equalsIgnoreCase("start")&&cmds.length==2){
				if( setServerPort(NumberUtils.toInt(cmds[1],-1)) )

					return "Server started on "+cmds[1];
				return "Invalid port number given.";
			}else{
				return "No server active yet, use ts:start,port to start one";
			}

		}
		if( cmds[0].equals("create"))
			return "Server already exists";

		Optional<TransHandler> hOpt = cmds.length>1?getHandler(cmds[1]):Optional.empty();

		switch( cmds[0] ){
			case "?":
				return "ts:store,id/index(,newid) -> Store the session in the xml\r\n"+
						"ts:add,id/index,cmd -> Add the cmd to the id/index\r\n"+
						"ts:clear,id/index -> Clear all cmds from the client id/index\r\n"+
						"ts:list -> List of all the connected clients\r\n"+
						"ts:defaults -> List of all the defaults\r\n"+
						"ts:alter,id,ref:value -> Alter some settings\r\n"+
						"ts:forward,id -> Forward data received on the trans to the issuer of the command\r\n"+
						"ts:reload -> Reload the xml settings.\r\n";
			case "store":
				if( hOpt.isEmpty() )
					return "Invalid id";
				var handler = hOpt.get();
				handler.setID(cmds.length==3?cmds[2]:handler.getID());
				storeHandler(handler,wr);
				return "";
			case "add":
				return getHandler(cmds[1]).map( h -> {
					StringJoiner join = new StringJoiner(",");
					for( int a=2;a<cmds.length;a++ )
						join.add(cmds[a]);
					h.addHistory(join.toString());
					return cmds[1]+"  added "+join;
				}).orElse("No such client: "+cmds[1]);
			case "clear":
				return getHandler(cmds[1]).map( h -> {
					h.clearRequests();
					return cmds[1]+"  cleared.";}).orElse("No such client: "+cmds[1]);
			case "defaults":
				StringJoiner lines = new StringJoiner("\r\n");
				defaults.forEach( (id,val) -> lines.add(id+" -> "+val.ip+" => "+String.join(",",val.commands)));
				return lines.toString();
			case "reload":
				if( readSettingsFromXML(XMLtools.readXML(xmlPath),false) )
					return "Defaults reloaded";
				return "Reload failed";
			case "alter":
				if( hOpt.isEmpty() )
					return "Invalid id";
				if( cmds.length<3)
					return "Not enough arguments: trans:alter,id,ref:value";
				String ref = cmds[2].substring(0,cmds[2].indexOf(":"));
				String value =  cmds[2].substring(cmds[2].indexOf(":")+1);
				switch( ref ){
					case "label":
						hOpt.get().setLabel(value);
						return "Altered label to "+value;
					case "id":
						hOpt.get().setID(value);
						return "Altered id to "+value;
					default: return "Nothing called "+ref;
				}

			case "forward":
				if( cmds.length==1)
					return "No enough parameters given for forwarding...";

				if( defaults.containsKey(cmds[1]) || hOpt.isPresent() ) {
					if (!targets.containsKey(cmds[1])) {
						targets.put(cmds[1], new ArrayList<>());
					}
					var list = targets.get(cmds[1]);

					if( !list.contains(wr)){// no exact match
						list.removeIf(Objects::isNull); // Remove invalid ones
						list.removeIf( w -> w.getID().equalsIgnoreCase(wr.getID()));// Remove id match
						list.add(wr);
					}
				}
				return hOpt.map( h -> {
					h.addTarget(wr);
					return "Added to target for "+cmds[1];
				}).orElse(cmds[1]+" not active yet, but recorded request");
			case "": case "list": 
				return "Server running on port "+serverPort+"\r\n"+getClientList();
			default: 
				return "unknown command "+request[0]+":"+request[1];
		}
	}

	@Override
	public boolean removeWritable(Writable wr) {
		boolean removed=false;
		for( var list : targets.values()) {
			if( list.removeIf(w -> w.equals(wr)))
				removed=true;
		}
		return removed;
	}

	/**
	 * Class to hold the info regarding a default, this will allow history of a connection to be reproduced.
	 * Eg. if the default is stored with calc:clock, this will be applied if the that client connects again
	 */
	public static class TransDefault{
		String ip;
		String id;
		String label="system";
		ArrayList<String> commands = new ArrayList<>();

		public TransDefault( String id, String ip ){
			this.ip=ip;
			this.id=id;
		}
		public void setLabel(String label){
			this.label=label;
		}
		public boolean addCommand( String cmd ){
			if( commands.contains(cmd))
				return false;
			return commands.add(cmd);
		}
		public List<String> getCommands(){return commands;}
	}
}