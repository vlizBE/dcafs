package io.stream;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.ConfirmCollector;
import io.stream.serialport.ModbusStream;
import io.stream.serialport.MultiStream;
import io.stream.serialport.SerialStream;
import io.stream.tcp.ModbusTCPStream;
import io.stream.tcp.TcpStream;
import io.stream.udp.UdpServer;
import io.stream.udp.UdpStream;
import io.telnet.TelnetCodes;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * The class holds all the information required about a datasource to acquire
 * data from it. It uses the internal class StreamDescriptor to hold this
 * information. All connections are made using the Netty 4.x library.
 */
public class StreamManager implements StreamListener, CollectorFuture, Commandable {

	private final BlockingQueue<Datagram> dQueue; // Holds the data for the DataWorker

	// Netty 
	private Bootstrap bootstrapTCP;		// Bootstrap for TCP connections
	private Bootstrap bootstrapUDP;	  	// Bootstrap for UDP connections

	private final EventLoopGroup eventLoopGroup;	// Event loop used by the netty stuff

	private final IssuePool issues;			// Handles the issues/problems that arise
	private int retryDelayMax = 30;			// The minimum time between reconnection attempts
	private int retryDelayIncrement = 5;	// How much the delay increases between attempts

	private final HashMap<String, ConfirmCollector> confirmCollectors = new HashMap<>();

	private final LinkedHashMap<String,BaseStream> streams = new LinkedHashMap<>();

	private Path settingsPath = Path.of("settings.xml"); // Path to the xml file
	private boolean debug = false; // Whether in debug mode, gives more feedback

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // scheduler for the connection attempts
	private static final String XML_PARENT_TAG="streams";
	private static final String XML_CHILD_TAG="stream";
	private RealtimeValues rtvals;

	public StreamManager(BlockingQueue<Datagram> dQueue, IssuePool issues, EventLoopGroup nettyGroup, RealtimeValues rtvals ) {
		this.dQueue = dQueue;
		this.issues = issues;	
		this.eventLoopGroup = nettyGroup;
		this.rtvals=rtvals;
	}
	public StreamManager(BlockingQueue<Datagram> dQueue, IssuePool issues, RealtimeValues rtvals) {
		this(dQueue,issues, new NioEventLoopGroup(), rtvals);
	}

	public void enableDebug(){
		debug=true;
	}

	/**
	 * Get the optional stream associated with the given id
	 * @param id The id to look for
	 * @return The BaseStream optional or an empty optional of none found
	 */
	public Optional<BaseStream> getStream( String id ){
		return Optional.ofNullable(streams.get(id.toLowerCase()));
	}

	/**
	 * Get the optional stream associated with the given id but only if it is writable with a valid connection
	 * @param id The id to look for
	 * @return The BaseStream optional or an empty optional if none found
	 */
	public Optional<BaseStream> getWritableStream( String id ){
		// Get the stream, check if it's writable if so get the writable and has a valid connection or return an empty optional
		return Optional.ofNullable( streams.get(id.toLowerCase()) ).filter( bs -> bs.isWritable() && bs.isConnectionValid() );
	}
	/**
	 * Get the Writable of the stream associated with the id as an optional
	 * @param id The stream to look for
	 * @return The optional writable or an empty one
	 */
	public Optional<Writable> getWritable(String id ){
		// Get the stream, check if it's writable if so get the writable or return an empty optional if not
		return getStream(id).filter(BaseStream::isWritable).map(bs -> (Writable) bs);
		//TODO Check if this still works
	}

	/* **************************** S T A T U S ************************************************************************/
	/**
	 * Request a string holding info regarding the status of each connection
	 * 
	 * @return A string holding info regarding the status of each connection.
	 */
	public String getStatus() {
		StringJoiner join = new StringJoiner("");

		for( BaseStream stream : streams.values() ){
			long ttl = Instant.now().toEpochMilli() - stream.getLastTimestamp();
			if( !stream.isConnectionValid() ){
				join.add("NC ");
			}else if (ttl > stream.readerIdleSeconds *1000 && stream.readerIdleSeconds != -1) {
				join.add("!! ");
			}

			join.add(stream.getInfo()).add("\t");
			if (stream.getLastTimestamp() == -1) {
				join.add("No data yet!").add("\r\n");
			} else {
				join.add(TimeTools.convertPeriodtoString(ttl, TimeUnit.MILLISECONDS)).add(" [");
				join.add(TimeTools.convertPeriodtoString(stream.readerIdleSeconds, TimeUnit.SECONDS)).add("]").add("\r\n");
			}
		}
		return join.toString();
	}

	/**
	 * Request information regarding the settings of all the connections, this is
	 * very rudimentary.
	 * 
	 * @return The label, id, address and TTL of each stream
	 */
	public String getSettings() {
		StringJoiner join = new StringJoiner("\r\n");
		streams.values().forEach(
			stream -> join.add( stream.getInfo() + " Max. TTL:" + TimeTools.convertPeriodtoString(stream.readerIdleSeconds,TimeUnit.SECONDS))
		);
		return join.toString();
	}


	/**
	 * Get a list of all the StreamDescriptors available
	 * @param html Whether to use html formatting
	 * @return A String with a line for each StreamDescriptor which looks like
	 *         Sxx=id
	 */
	public String getStreamList(boolean html) {
		StringJoiner join = new StringJoiner(html ? "<br>" :"\r\n");
		join.setEmptyValue("None yet");
		int a = 1;
		for (String id : streams.keySet()) {
			join.add( "S" + (a++) + ":" + id);
		}
		return join.toString();
	}
	/**
	 * Get a list of all currently active labels
	 * 
	 * @return A list of all currently active labels (fe. nmea,li7000...) separated
	 *         by ','
	 */
	public String getActiveLabels() {
		StringJoiner join = new StringJoiner(", ","Currently used labels: ","\r\n");
		
		streams.values().stream().filter( desc -> !join.toString().contains(desc.getLabel()) )
								  .forEach( desc -> join.add( desc.getLabel() ) );			

		return join.toString();
	}

	/**
	 * Retrieve the contents of the confirm/reply buffer from the various streams
	 * Mainly used for debugging
	 * @return Contents of the confirm/reply buffer from the various streams
	 */
	public String getConfirmBuffers() {
		StringJoiner join = new StringJoiner("\r\n");
		confirmCollectors.forEach( (id, cw) -> join.add(">>"+cw.getID()).add( cw.getStored().length() == 0 ? " empty" : cw.getStored()));
		return join.toString();
	}
	/* *************************************  S E T U P **************************************************************/
	/**
	 * Disconnect all connections
	 */
	public void disconnectAll() {
		streams.forEach((k,v) -> v.disconnect() );
		eventLoopGroup.shutdownGracefully();
	}

	/* ********************************** W R I T I N G **************************************************************/
	/**
	 * Send bytes over a specified stream
	 * @param id The name/title of the stream
	 * @param txt The data to transmit
	 * @return True if it was written
	 */
	public String writeBytesToStream( String id, byte[] txt ) {
		Optional<BaseStream> streamOpt = getStream(id.toLowerCase());
		if ( streamOpt.isPresent() ) {
			BaseStream stream = streamOpt.get();
			if( !stream.isWritable() ){
				Logger.error("The stream " + id + " is readonly.");
				return "";
			}
			if( !stream.isConnectionValid() ){
				Logger.error("No connection to stream named " + stream);
				reloadStream(id);
				return "";
			}
			if( debug )
				Logger.info("Sending '"+Tools.fromBytesToHexString(txt) + "' to " + stream );

			Writable wr = (Writable)stream;
			wr.writeBytes( txt );
			return new String(txt);
		}else{
			Logger.error("Didn't find stream named " + id);
			return "";
		}
	}

	/**
	 * Write something to a stream that expects a reply
	 * @param wf Future for this write
	 * @param ref Reference to this action
	 * @param id The id of the stream to write to
	 * @param txt The text to write
	 * @param reply The reply to expect
	 * @return False if stream doesn't exist or doesn't allow being written to otherwise true
	 */
	public boolean writeWithReply(CollectorFuture wf, String ref, String id, String txt, String reply){
		return writeWithReply(wf,ref,id,txt,reply,3,3);
	}
	public boolean writeWithReply(CollectorFuture wf, String ref, String id, String txt, String reply,long replyWait,int replyTries){
		BaseStream stream = this.streams.get( id.toLowerCase() );

		if( stream == null || !stream.isWritable() || !stream.isConnectionValid() ){
			Logger.error( "Stream still null, not writable or no valid connection (looking for "+id+")");
			return false;
		}
		
		ConfirmCollector cw = confirmCollectors.get(ref+"_"+id);
		if( cw==null ){
			cw = new ConfirmCollector( ref+"_"+id,replyTries,(int)replyWait, (Writable)stream, eventLoopGroup);
			cw.addListener(wf);
			stream.addTarget(cw);
			confirmCollectors.put(ref+"_"+id, cw );
		}
		cw.addConfirm(txt.split(";"), reply);
		return true;
	}
	/**
	 * Standard way of writing ascii data to a channel, with or without requesting a certain reply
	 * @param id The id of the stream to write to
	 * @param txt The ascii data to transmit
	 * @param reply The expected reply to the transmit
	 * @return The string that was written or an empty string if failed
	 */
	public String writeToStream(String id, String txt, String reply) {

		if( txt.startsWith("\\h(") ){
			txt = txt.substring( 3, txt.indexOf(")") ); //remove the \h(...)
			return writeBytesToStream( id, Tools.fromHexStringToBytes(txt) );
		}

		Optional<BaseStream> streamOptional = getWritableStream(id);
		if( streamOptional.isPresent() ){
			BaseStream stream = streamOptional.get();
			ConfirmCollector cw = confirmCollectors.get(id);

			if( cw!=null && cw.isEmpty() ) {
				confirmCollectors.remove(id);
				Logger.info("Removed empty ConfirmCollector "+id);
				cw=null;
			}

			if( cw==null ){// If none exists yet
				if( txt.contains(";") || !reply.isEmpty() ){
					cw = new ConfirmCollector( id,3,3, (Writable)stream, scheduler );
					cw.addListener(this);
					if( !reply.isEmpty()) // No need to get data if we won't use it
						stream.addTarget(cw);
					confirmCollectors.put(stream.getID(),cw);
				}else{
					if( txt.indexOf("\\") < txt.length()-2 ){
						txt = Tools.fromEscapedStringToBytes(txt);
					}
					boolean written;
					boolean nullEnded = Tools.isNullEnded(txt);
					if( txt.endsWith("\\0") || nullEnded){
						if( nullEnded )
							txt = txt.substring(0,txt.length()-1);
						txt=StringUtils.removeEnd(txt,"\\0");
						written=((Writable)stream).writeString(txt);
					}else{
						written=((Writable)stream).writeLine(txt);
					}
					if( !written )
						Logger.error("writeString/writeLine failed to "+id+" for "+txt);
					return written?txt:"";
				}
			}
			cw.addConfirm(txt.split(";"), reply);
			return txt;
		}else{
			var bs = streams.get(id);
			if( bs==null){
				Logger.error("No such stream "+id);
			}else if( !bs.isWritable() ){
				Logger.error("Found the stream "+id+", but not writable");
			}else if( !bs.isConnectionValid()){
				Logger.error("Found the writable stream "+id+", but no valid connection");
			}
			return "";
		}
	}
	/**
	 * Request the amount of registered streams
	 * 
	 * @return Amount of registered streams
	 */
	public int getStreamCount() {
		return streams.size();
	}
	/**
	 * Get the id of the stream based on the index in the hashmap
	 * 
	 * @param index The index in the hashmap to retrieve
	 * @return The ID of the stream on the index position or empty of bad index
	 */
	public String getStreamID( int index ) {
		if( index ==-1 || index >= streams.size() )
			return "";
		return (String)streams.keySet().toArray()[index];
	}
	/* ************************************************************************************************* */
	/**
	 * Reload the settings of a channel and re-initialize
	 * 
	 * @param id ID of the stream to reload
	 * @return True if reload was successful
	 */
	public String reloadStream( String id ) {

		Logger.info("Reloading "+id+ " from "+ settingsPath.toAbsolutePath());
		if(Files.notExists(settingsPath)){
			Logger.error("Failed to read xml file at "+ settingsPath.toAbsolutePath());
			return "Failed to read xml";
		}

		var childOpt = XMLfab.withRoot(settingsPath,"dcafs","streams").getChild("stream","id",id);
		var baseOpt = getStream(id);
		if( childOpt.isEmpty() )
			return "No stream named "+id+" found.";

		if( baseOpt.isPresent() ){ // meaning reloading an existing one
			var str = baseOpt.get();
			str.disconnect();
			str.removeRealtimeValues(rtvals); // Remove the existing ones
			str.readFromXML(childOpt.get());
			str.shareRealtimeValues(rtvals);
			str.reconnectFuture = scheduler.schedule( new DoConnection( str ), 0, TimeUnit.SECONDS );
			return "Reloaded and trying to reconnect";
		}else{
			addStreamFromXML(childOpt.get()).shareRealtimeValues(rtvals);
			return "Loading new stream.";
		}
	}
	/* ***************************** A D D I N G C H A N N E L S ******************************************/
	/**
	 * Add the streams by reading the settings.xml
	 * 
	 * @param settingsPath The path to the settings.xml
	 */
	public void readSettingsFromXML( Path settingsPath ) {

		if( XMLtools.readXML(settingsPath).isEmpty())
			return;

		this.settingsPath=settingsPath;

		if( !streams.isEmpty()){
			streams.values().forEach(BaseStream::disconnect);
		}
		streams.values().forEach( s -> s.removeRealtimeValues(rtvals));
		streams.clear(); // Clear out before the reread

		XMLtools.getFirstElementByTag( settingsPath, "streams").ifPresent( ele -> {
			retryDelayIncrement = XMLtools.getChildIntValueByTag(ele, "retrydelayincrement", 5);
			retryDelayMax = XMLtools.getChildIntValueByTag(ele, "retrydelaymax", 60);

			for( Element el : XMLtools.getChildElements( ele, XML_CHILD_TAG)){
				BaseStream bs = addStreamFromXML(el);
				bs.shareRealtimeValues(rtvals);
				streams.put(bs.getID().toLowerCase(), bs);
			}
		});
	}
	/**
	 * Add a single channel from an XML element
	 * 
	 * @param stream The element containing the channel information
	 */
	public BaseStream addStreamFromXML( Element stream ){
		
    	switch( stream.getAttribute("type").toLowerCase() ){
			case "tcp": case "tcpclient":
				TcpStream tcp = new TcpStream( dQueue, stream );
				tcp.setEventLoopGroup(eventLoopGroup);
				tcp.addListener(this);
				bootstrapTCP = tcp.setBootstrap(bootstrapTCP);
				tcp.reconnectFuture = scheduler.schedule( new DoConnection( tcp ), 0, TimeUnit.SECONDS );
				return tcp;
			case "udp":case "udpclient":
				UdpStream udp = new UdpStream( dQueue, stream );
				udp.setEventLoopGroup(eventLoopGroup);
				udp.addListener(this);
				bootstrapUDP = udp.setBootstrap(bootstrapUDP);
				udp.reconnectFuture = scheduler.schedule( new DoConnection( udp ), 0, TimeUnit.SECONDS ); 
				return udp;
			case "udpserver":
				UdpServer serv = new UdpServer( dQueue, stream );
				serv.setEventLoopGroup(eventLoopGroup);
				serv.addListener(this);
				serv.reconnectFuture = scheduler.schedule( new DoConnection( serv ), 0, TimeUnit.SECONDS );
				return serv;
			case "serial":
				SerialStream serial = new SerialStream( dQueue, stream );
				serial.setEventLoopGroup(eventLoopGroup);
				if( serial.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(serial), serial.readerIdleSeconds, TimeUnit.SECONDS);
				}
				serial.addListener(this);
				serial.reconnectFuture = scheduler.schedule( new DoConnection( serial ), 0, TimeUnit.SECONDS );
				return serial; 
			case "modbus":
				if( XMLtools.hasChildByTag(stream,"address")){ // Address means tcp
					ModbusTCPStream mbtcp = new ModbusTCPStream( dQueue, stream );
					mbtcp.setEventLoopGroup(eventLoopGroup);
					mbtcp.addListener(this);
					bootstrapTCP = mbtcp.setBootstrap(bootstrapTCP);
					mbtcp.reconnectFuture = scheduler.schedule( new DoConnection( mbtcp ), 0, TimeUnit.SECONDS );
					return mbtcp;
				}else{
					ModbusStream modbus = new ModbusStream( dQueue, stream );
					modbus.setEventLoopGroup(eventLoopGroup);
					modbus.addListener(this);
					modbus.reconnectFuture = scheduler.schedule( new DoConnection( modbus ), 0, TimeUnit.SECONDS );
					return modbus;
				}
			case "multiplex":
				MultiStream mStream = new MultiStream( dQueue, stream );
				mStream.setEventLoopGroup(eventLoopGroup);
				if( mStream.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(mStream), mStream.readerIdleSeconds, TimeUnit.SECONDS);
				}
				mStream.addListener(this);
				mStream.reconnectFuture = scheduler.schedule( new DoConnection( mStream ), 0, TimeUnit.SECONDS );
				return mStream;
			case "local":
				LocalStream local = new LocalStream( dQueue, stream);
				local.setEventLoopGroup(eventLoopGroup);
				local.addListener(this);
				if( local.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(local), local.readerIdleSeconds, TimeUnit.SECONDS);
				}
				local.reconnectFuture = scheduler.schedule( new DoConnection( local ), 0, TimeUnit.SECONDS );
				return local;                  		
    		default: Logger.error( "aNo such type defined" );
		}
		return null;
	}
	/**
	 * Stores the settings of a stream to the settings.xml. Writing isn't done if the address is already in use by a different stream.
	 * If a stream with the name is already present, unless overwrite = true writing is aborted
	 * @param id The id of the stream to write
	 * @param overwrite If true an existing element with that id will be removed
	 * @return True if ok, false if failed or aborted
	 */
	public boolean addStreamToXML( String id, boolean overwrite ){
		BaseStream stream = streams.get(id.toLowerCase());
		if( stream == null){
			Logger.warn("No such stream to write to xml "+id);
			return false;
		}

		// Check if it already exists (based on id and address?)
		XMLfab fab = XMLfab.withRoot(settingsPath, "dcafs",XML_PARENT_TAG);
		boolean exists = fab.hasChild(XML_CHILD_TAG, "id", stream.getID() ).isPresent();

		if( exists && !overwrite ){
			Logger.warn("Already such stream ("+id+") in the settings.xml, not overwriting");
			return false;
		}
		stream.writeToXML(fab);
		return fab.build();
	}
	/* ************************************************************************************************* **/
	/**
	 * Class that handles making a connection to a channel
	 */
	public class DoConnection implements Runnable {
		
		BaseStream base;

		public DoConnection( BaseStream base ){
			this.base = base;
			base.reconnecting=true;
		}		
		@Override
		public void run() {	
			try{
				if( base==null) {
					Logger.error("Can't reconnect if the object isn't valid");
					return;
				}
				base.disconnect();
				if( base.connect() ){
					base.reconnecting=false;
					return; //if Ok, nothing else to do?
				} 
			
				int delay = retryDelayIncrement*(base.connectionAttempts+1);
				if( delay > retryDelayMax )
					delay = retryDelayMax;
				Logger.error( "Failed to connect to "+base.getID()+", scheduling retry in "+delay+"s. ("+base.connectionAttempts+" attempts)" );				
				String device = base.getID().replace(" ","").toLowerCase();
				if( issues!=null )
					issues.addIfNewAndStart(device+".conlost", "Connection lost to "+base.getID());
				base.reconnectFuture = scheduler.schedule( new DoConnection( base ), delay, TimeUnit.SECONDS );
			} catch (Exception ex) {		
				Logger.error( "Connection thread interrupting while trying to connect to "+base.getID());
				Logger.error( ex );
			}
		}
	}
	/* ************************** * C H E C K I N G   S T R E A M S  ************************************/
	/**
	 * Check if the stream is still ok/connected and maybe reconnect
	 * @param id The stream to check
	 * @param reconnect If true and not connected, a reconnect attempt will be made
	 * @return True if ok 
	 */
	public boolean isStreamOk( String id, boolean reconnect ){
		BaseStream base = streams.get(id.toLowerCase());
		if( base == null){
			Logger.warn("Couldn't find stream: "+id+" in list of "+streams.size());
			return false;
		}
		boolean alive = base.isConnectionValid();
		if( !alive && reconnect )
			this.reloadStream(id);
		return alive;
	}
	/**
	 * Check if the stream is still ok/connected
	 * @param id The stream to check
	 * @return True if the stream is ok/connected
	 */
	public boolean isStreamOk( String id ){
		return isStreamOk(id,false);
	}

	/* ***************************************************************************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {
		String find = request[0].toLowerCase().replaceAll("\\d+","_");
		return switch( find ) {
			case "ss", "streams" -> replyToStreamCommand(request[1], html);
			case "rios" -> replyToStreamCommand("rios", html);
			case "raw","stream" -> "Request for "+request[0]+":"+request[1]+" "+( addForwarding(request[1],wr)?"ok":"failed");
			case "s_","h_" -> doSorH( request);
			case "store" -> replyToStoreCommand(request,html);
			case "","stop" -> removeWritable(wr)?"Ok.":"";
			default -> "Unknown Command";
		};
	}
	/**
	 * The streampool can give replies to certain predetermined questions
	 * @param request The question to ask
	 * @param html Whether the answer should use html or regular line endings
	 * @return The answer or Unknown Command if the question wasn't understood
	 */
	public String replyToStreamCommand(String request, boolean html ){

		String nl = html?"<br>":"\r\n";

		String[] cmds = request.replace(" ","").split(",");

		String device="";
		if( cmds.length > 1 ){				
			device = cmds[1].replace(" ", "").toLowerCase(); // Remove spaces
		}

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		StringJoiner join = new StringJoiner(nl);
		BaseStream stream;
		XMLfab fab;

		switch( cmds[0] ){
			case "?":
				join.add(TelnetCodes.TEXT_RESET+ora+"Notes"+reg)
					.add("-> ss: and streams: do the same thing")
					.add("-> Every stream has at least:")
					.add("   - a unique id which is used to identify it")
					.add("   - a label which is used to determine how the data is processed")
					.add("   - an eol (end of line), the default is crlf")
					.add("   - ...");
				join.add("").add(cyan+"Add new streams"+reg)
					.add(green+" ss:addtcp,id,ip:port<,label> "+reg+"-> Add a TCP stream to xml (optional label) and try to connect")
					.add(green+" ss:addudp,id,ip:port<,label> "+reg+"-> Add a UDP stream to xml (optional label) and connect")
					.add(green+" ss:addserial,id,port:baudrate<,label>"+reg+" -> Add a serial stream to xml (optional label) and try to connect" )
					.add(green+" ss:addlocal,id,label,source "+reg+"-> Add a internal stream that handles internal data")
				.add("").add(cyan+"Info about streams"+reg)
					.add(green+" ss:labels "+reg+"-> get active labels.")
					.add(green+" ss:buffers "+reg+"-> Get confirm buffers.")
					.add(green+" ss:status "+reg+"-> Get streamlist.")
					.add(green+" ss:requests "+reg+"-> Get an overview of all the datarequests held by the streams")
				.add("").add(cyan+"Interact with stream objects"+reg)
					.add(green+" ss:recon,id "+reg+"-> Try reconnecting the stream")
					.add(green+" ss:reload<,id> "+reg+"-> Reload the stream with the given id or all if no id is specified.")
					.add(green+" ss:store,id "+reg+"-> Update the xml entry for this stream")
					.add(green+" ss:alter,id,parameter:value "+reg+"-> Alter the given parameter options label,baudrate,ttl")
					.add(green+" ss:addwrite,id,when:data "+reg+"-> Add a triggered write, possible when are hello (stream opened) and wakeup (stream idle)")
					.add(green+" ss:addcmd,id,when:data "+reg+"-> Add a triggered cmd, possible when are open,idle,!idle,close")
				.add("").add(cyan+"Route data from or to a stream"+reg)
					.add(green+" ss:forward,source,id "+reg+"-> Forward the data from a source to the stream, source can be any object that accepts a writable")
					.add(green+" ss:connect,id1,if2 "+reg+"-> Data is interchanged between the streams with the given id's")
					.add(green+" ss:echo,id "+reg+"-> Toggles that all the data received on this stream will be returned to sender")
					.add(green+" ss:send,id,data(,reply) "+reg+"-> Send the given data to the id with optional reply")
				.add("").add(cyan+"Send data to stream via telnet"+reg)
					.add("Option 1) First get the index of the streams with ss or streams")
					.add("          Then use Sx:data to send data to the given stream (eol will be added)")
					.add("Option 2) ss:send,id,data -> Send the data to the given stream and append eol"+TelnetCodes.TEXT_BRIGHT);
				return join.toString();
			case "send":
					if( cmds.length < 3 ) // Make sure we got the correct amount of arguments
						return "Bad amount of arguments, need 3 send,id,data(,reply)";
					if( getStream(cmds[1]).isEmpty() )
						return "No such stream: "+cmds[1];

					String written = writeToStream(cmds[1],cmds[2],cmds.length>3?cmds[3]:"");
					if( written.isEmpty() )
						return "Failed to write data";
					return "Data written: "+written;
			case "buffers": return getConfirmBuffers();
			case "labels" :case "rios": return this.getActiveLabels();
			case "requests":
				join.setEmptyValue("No requests yet.");
				streams.values().stream().filter( base -> base.getRequestsSize() !=0 )
								.forEach( x -> join.add( x.getID()+" -> "+x.listTargets() ) );
				return join.toString();
			case "cleartargets":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 ss:clearrequests,id";
				return "Targets cleared:"+getStream(cmds[1]).map(BaseStream::clearTargets).orElse(0);
			case "recon":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 (recon,id)";
				stream = streams.get(cmds[1].toLowerCase());

				if( stream != null){
					stream.disconnect();
					if(stream.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0 ) {
						Logger.info("Already scheduled to reconnect");
						stream.reconnectFuture = scheduler.schedule(new DoConnection(stream), 5, TimeUnit.SECONDS);
					}
					return "Trying to reconnect to "+cmds[1];
				}else{
					return "Already waiting for reconnect attempt";
				}
			case "reload":
				if( cmds.length == 1 ){
					readSettingsFromXML(settingsPath);
					return "Settings reloaded.";
				}else if(cmds.length == 2){
					return reloadStream( cmds[1] );
				}
			case "store":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 (store,id)";

				stream = streams.get(cmds[1].toLowerCase());
				if( stream == null )
					return "No such stream: "+cmds[1];
				
				if( this.addStreamToXML(cmds[1],false) )
					return "Updated XML";
				return "Failed to update XML! Entry might exist already";
			case "trigger":
				stream = streams.get(cmds[1].toLowerCase());
				if( cmds.length<4)
					return "Incorrect amount of arguments, expected ss:trigger,id,when,cmd";
				if( stream == null )
					return "No such stream: "+cmds[1];

				String event = cmds[2];
				String cmd = request.substring( request.indexOf(event+",")+event.length()+1);

				fab = XMLfab.withRoot(settingsPath,XML_PARENT_TAG); // get a fab pointing to the streams node

				if( fab.selectChildAsParent("stream","id",cmds[1]).isEmpty() )
					return "No such stream  "+cmds[1];

				fab.addChild("cmd",cmd).attr("when",event);
				stream.addTriggeredAction(event,cmd);
				return fab.build()?"Trigger added":"Altering xml failed";
			case "alter":
				if( cmds.length != 3)
					return "Bad amount of arguments, should be ss:alter,id,param:value";
				stream = streams.get(cmds[1].toLowerCase());

				if( stream == null )
					return "No such stream: "+cmds[1];

				String[] alter = cmds[2].split(":");
				if( alter.length==1)
					return "Not enough arguments for altering";

				if( alter.length>2)
					alter[1] = cmds[2].substring(cmds[2].indexOf(":")+1);

				fab = XMLfab.withRoot(settingsPath,XML_PARENT_TAG); // get a fab pointing to the streams node

				if( fab.selectChildAsParent("stream","id",cmds[1]).isEmpty() )
					return "No such stream '"+cmds[1]+"'";

				boolean reload=false;
				switch (alter[0]) {
					case "label" -> {
						stream.setLabel(alter[1]);
						fab.alterChild("label", alter[1]);
					}
					case "baudrate" -> {
						if (!(stream instanceof SerialStream))
							return "Not a Serial port, no baudrate to change";
						((SerialStream) stream).setBaudrate(Tools.parseInt(alter[1], -1));
						fab.alterChild("serialsettings", ((SerialStream) stream).getSerialSettings());
					}
					case "ttl" -> {
						if (!alter[1].equals("-1")) {
							stream.setReaderIdleTime(TimeTools.parsePeriodStringToSeconds(alter[1]));
							fab.alterChild("ttl", alter[1]);
						} else {
							fab.removeChild("ttl");
						}
						reload = true;
					}
					default -> {
						fab.alterChild(alter[0], alter[1]);
						reload = true;
					}
				}
				if( fab.build() ){
					if( reload )
						this.reloadStream(device);	
					return "Alteration applied";
				}
				return "Failed to alter stream!";
			case "addwrite":
				if( cmds.length < 3 ){
					return "Bad amount of arguments, should be ss:addwrite,id,when:data";
				}
			case "addcmd":
					if( cmds.length < 3 ){
						return "Bad amount of arguments, should be ss:addcmd,id,when:cmd";
					}
					if( cmds[2].split(":").length==1)
						return "Doesn't contain a proper when:data pair";
					var sOpt = getStream(cmds[1]);
					if( sOpt.isEmpty())
						return "No such stream: "+cmds[1];
					var data = request.substring(request.indexOf(":")+1);
					var when= cmds[2].substring(0,cmds[2].indexOf(":"));
					if( sOpt.get().addTriggeredAction(when,data) ){
						XMLfab.withRoot(settingsPath,XML_PARENT_TAG).selectChildAsParent("stream","id",cmds[1])
								.ifPresent( fa -> fa.addChild(cmds[0].substring(3),data).attr("when",when).build());
						return "Added triggered "+cmds[0].substring(3);
					}else{
						return "Failed to add, invalid when";
					}
			case "echo":
					if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
						return "Bad amount of arguments, need 2 (echo,id)";
					BaseStream bs0 = streams.get(cmds[1].toLowerCase());	
					if( bs0 == null || !bs0.isWritable() )
						return "No such writable stream: "+cmds[1];
					if( bs0.hasEcho() ){
						bs0.disableEcho();
						return "Echo disabled on "+cmds[1];
					}else{
						bs0.enableEcho();
						return "Echo enabled on "+cmds[1];
					}
			case "tunnel": case "connect":
					if( cmds.length != 3 ) // Make sure we got the correct amount of arguments
						return "Bad amount of arguments, need 3 (tunnel,fromid,toid)";

					int s1Ok = getWritable(cmds[1]).map( wr-> addForwarding(cmds[2], wr)?1:0).orElse(-1);
					if( s1Ok != 1 )
						return s1Ok==-1?"No writable "+cmds[1]:"No such source "+cmds[2];

					int s2Ok = getWritable(cmds[2]).map( wr-> addForwarding(cmds[1], wr)?1:0).orElse(-1);
					if( s2Ok != 1 )
						return s2Ok==-1?"No writable "+cmds[1]:"No such source "+cmds[1];
					
					return "Tunnel established between "+cmds[1]+" and "+cmds[2];
			case "link": case "forward":
				if( cmds.length != 3 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 3 (link,fromid,toid)";
				var wr = getWritable(cmds[2]);
				if(wr.isPresent()){
					dQueue.add( Datagram.system(cmds[1]).writable(wr.get()) );
					return "Tried enabling the forward from "+cmds[1]+" to "+cmds[2];
				}else{
					return "No such stream: "+cmds[2];
				}
			case "addtcp":
				if( cmds.length < 3 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need at least 3 ss:addtcp,id,ip:port(,label)";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that id ("+cmds[1]+") not creating it";

				if( !cmds[2].contains(":") )
					return "No port number specified";

				String label = cmds.length==3?"void":"";
				if( cmds.length>=4)
					label=request.substring( request.indexOf(","+cmds[3])+1);

				cmds[1]=cmds[1].toLowerCase();

				TcpStream tcp = new TcpStream( cmds[1], cmds[2], dQueue, label, 1 );
				tcp.addListener(this);
				tcp.setEventLoopGroup(eventLoopGroup);
				tcp.setBootstrap(bootstrapTCP);

				if( !addStreamToXML(cmds[1],false) ){
					tcp.reconnectFuture = scheduler.schedule( new DoConnection( tcp ), 0, TimeUnit.SECONDS );
					try{
						tcp.reconnectFuture.get(2,TimeUnit.SECONDS);
						streams.put( cmds[1], tcp );
						addStreamToXML(cmds[1],true);
					}catch(CancellationException | ExecutionException | InterruptedException | TimeoutException e){
						return "Failed to connect.";
					}
					return "Connected to "+cmds[1]+", use 'raw:"+cmds[1]+"' to see incoming data.";
				}
				return "Failed to update XML! Entry might exist already";
			case "addudp":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 ss:addudp,id,ip:port,label";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that id ("+cmds[1]+") not creating it";

				if( cmds.length>4)
					cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);
				UdpStream udp = new UdpStream(cmds[1], cmds[2], dQueue, cmds[3], 1 );
				udp.addListener(this);
				udp.setEventLoopGroup(eventLoopGroup);
				udp.setBootstrap(bootstrapUDP);

				streams.put( cmds[1], udp );

				if( addStreamToXML(cmds[1],false) ){
					udp.reconnectFuture = scheduler.schedule( new DoConnection( udp ), 0, TimeUnit.SECONDS );
					return "Trying to connect...";
				}
				return "Failed to update XML! Entry might exist already";
			case "udpserver":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addudpserver,id,port,label)";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that id ("+cmds[1]+") not creating it";

				cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);

				new UdpServer(cmds[1],Integer.parseInt(cmds[2]),dQueue,cmds[3]);

				break;
			case "addserial":
				if( cmds.length < 3 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need at least three ss:addserial,id,portname:baudrate(,label)";

				cmds[1]=cmds[1].toLowerCase();

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that id ("+cmds[1]+") not creating it";

				String serLabel = cmds.length==3?"void":"";
				if( cmds.length>=4)
					serLabel=request.substring( request.indexOf(","+cmds[3])+1);

				String[] portAndBaud = cmds[2].split(":");
				String port = portAndBaud[0];
				String baud = portAndBaud.length==2?portAndBaud[1]:"19200";
				
				if( !SerialStream.portExists(port) && !port.contains("ttyGS") && !port.contains("printer"))
					return "No such port on this system. Options: "+ SerialStream.portList();
				
				SerialStream serial = new SerialStream( port, dQueue, serLabel, 1);
				serial.setEventLoopGroup(eventLoopGroup);
				serial.alterSerialSettings(baud+",8,1,none");

				serial.setID(cmds[1]);
				serial.addListener(this);

				streams.put(cmds[1].toLowerCase(), serial);
				addStreamToXML(cmds[1],true);

				return serial.connect()?"Connected to "+port:"Failed to connect to "+port;	
			case "addlocal":
				if( cmds.length != 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, ss:addlocal,id,label,source";
				LocalStream local = new LocalStream( cmds[1],cmds[2],cmds[3],dQueue);
				local.addListener(this);
				streams.put( cmds[1].toLowerCase(), local);
				addStreamToXML(cmds[1],true);
				return "Local stream added";			
			case "":
				return "List of available streams:"+nl+this.getStreamList(html);
			case "status" :
				return getStatus();
			default: return "Unknown command: "+request;
		}
		return "";
	}

	/**
	 * Listener stuff
	 *
	 */
	@Override
	public void notifyIdle( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStart(device+".conidle", "TTL passed for "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.IDLE));
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.WAKEUP));
	}
	@Override
	public boolean notifyActive(String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStop(device+".conidle", "TTL passed for "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.IDLE_END));
		return true;
	}
	@Override
	public void notifyOpened( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStop(device+".conlost", "Connection lost to "+id);

		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.HELLO));
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.OPEN));

	}
	@Override
	public void notifyClosed( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStart(device+".conlost", "Connection lost to "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.CLOSE));
	}
	@Override
	public boolean requestReconnection( String id ) {
		BaseStream bs = streams.get(id.toLowerCase());
		if( bs == null){
			Logger.error("Bad id given for reconnection request: "+id);
			return false;
		}
		Logger.error("Requesting reconnect for "+bs.getID());
		if( bs.reconnectFuture==null || bs.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0 ){
			bs.reconnectFuture = scheduler.schedule( new DoConnection( bs ), 5, TimeUnit.SECONDS );
			return true;
		}
		return false;
	}

	/* 	--------------------------------------------------------------------	*/
	public boolean addForwarding(String cmd, Writable writable) {

		if( writable != null ){
			Logger.info("Received data request from "+writable.getID()+" for "+cmd);
		}else if( cmd.startsWith("email") ){
			Logger.info("Received request through email for "+cmd);
		}
		String[] items = cmd.split(":");
		String type = items.length==2?items[0]:"id";
		String search = items.length==2?items[1].toLowerCase():items[0].toLowerCase();

		switch( type ){
			case "label": case "ll":
				return getStream( search ).filter( bs -> bs.getLabel().startsWith(search) ).map( bs -> bs.addTarget(writable) ).orElse(false);
			case "generic": case "gen":
				for( String item : items[1].split(",")){
					streams.values().stream().filter( x -> x.getLabel().startsWith("generic:")||x.getLabel().startsWith("gen:")) // Label must start with generic
							.filter( x -> x.getLabel().contains(item))	  	   // Label must contain the word
							.forEach( x -> x.addTarget(writable) ); // add it, don't care about duplicates
				}
				break;
			case "id":
				if( !getStream(search).map( bs -> bs.addTarget(writable) ).orElse(false) ) {
					var stream = streams.entrySet().stream().filter(set -> set.getKey().startsWith(search))
							.map(Map.Entry::getValue).findFirst();
					if(stream.isEmpty())
						return false;
					stream.get().addTarget(writable);
				}
				return true;
			default:
				Logger.warn("Unknown type: "+type+ " possible ones: id, label/ll, generic/gen");
				return false;
		}

		return true;
	}


	private String doSorH( String[] request ){
		return switch( request[1] ){
			case "??" -> "Sx:y -> Send the string y to stream x";
			case "" -> "No use sending an empty string";
			default -> {
				var stream = getStreamID( Tools.parseInt( request[0].substring(1), 0 ) -1);
				if( !stream.isEmpty()){
					request[1] = request[1].replace("<cr>", "\r").replace("<lf>", "\n"); // Normally the delimiters are used that are chosen in settings file, extra can be added

					var written = "";
					if( request[0].startsWith("h")){
						written = writeBytesToStream(stream, Tools.fromHexStringToBytes(request[1]) );
					}else{
						written = writeToStream(stream, request[1], "" );
					}
					if( !written.isEmpty() )
						yield "Sending '"+written+"' to "+stream;
					yield "Failed to send "+request[1]+" to "+stream;

				}else{
					yield switch( getStreamCount() ){
						case 0 -> "No streams active to send data to.";
						case 1 ->"Only one stream active. S1:"+getStreamID(0);
						default -> "Invalid number chosen! Must be between 1 and "+getStreamCount();
					};
				}
			}
		};
	}
	public String replyToStoreCommand(String[] request, boolean html ){
		if( request.length == 1 )
			return "Not enough arguments, need at least two";

		var cmds= request[1].split(",");
		String id = cmds[0];

		if( id.equalsIgnoreCase("?")){
			return "todo Help";
		}

		var bsOpt = getStream(id);
		if( bsOpt.isEmpty()) // Check if that stream exists
			return "No such stream: "+id;

		// It does, so dig for the node
		var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("streams");
		if( dig.isInvalid())
			return "No streams yet";

		dig.goDown("stream","id",id);
		if( dig.isInvalid() )
			return "No such stream yet "+id;

		// At this point, the digger is pointing to the stream node for the given id
		var fabOpt = XMLfab.alterDigger(dig);
		if( fabOpt.isEmpty())
			return "No valid fab created";
		var fab=fabOpt.get();

		fab.alterChild("store");
		dig.goDown("store");
		if( !dig.current().get().hasAttribute("delimiter"))
			fab.attr("delimiter",",");

		// At this point 'store' certainly exists in memory and dig is pointing to it

		switch (cmds[1]) {
			case "addblank", "addb" -> { // Adds an ignore node
				fab.down();
				fab.addChild("ignore");
				fab.build();
				return "Blank added";
			}
			case "addreal","addr" -> {
				if (cmds.length < 3)
					return "Not enough arguments: store:id,addreal,name<,index>";
				fab.down();
				if( dig.peekAt("real","id",cmds[2]).hasValidPeek())
					return "Already a real with that id, try something else?";

				fab.addChild("real").attr("id",cmds[2]).attr("unit");
				if( cmds.length==4 ) {
					if(NumberUtils.isCreatable(cmds[3])) {
						fab.attr("index", cmds[3]);
					}else{
						return "Not a valid index: "+cmds[3];
					}
				}
				fab.build();
				return "Real added";
			}
			case "delim", "delimiter" -> {
				if (cmds.length < 3)
					return "Not enough arguments: store:id,delim,delimiter";
				fab.attr("delimiter", cmds.length == 4 ? "," : cmds[2]);
				fab.build();
				return "Set the delimiter";
			}
		}

		return "Unknown command: "+request[0]+":"+request[1];
	}
	/**
	 * Remove the given writable from the various sources
	 * @param wr The writable to remove
	 * @return True if any were removed
	 */
	public boolean removeWritable(Writable wr) {
		if( wr==null)
			return false;

		boolean removed=false;
		for( BaseStream bs : streams.values() ){
			if( bs.removeTarget(wr) )
				removed=true;
		}
		return removed;
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		switch (ids[0]) {
			case "confirm" -> {
				confirmCollectors.remove(ids[1]);
				if (confirmCollectors.isEmpty())
					Logger.info("All confirm requests are finished");
			}
			case "math" -> dQueue.add(Datagram.system("store:" + message + "," + result));
			default -> Logger.error("Unknown Collector type: " + id);
		}
	}

	/**
	 * Remove the confirmCollector with the given id from the hashmap
	 * @param id The id to look for
	 */
	public void removeConfirm(String id){
		if( confirmCollectors.values().removeIf( cc -> cc.getID().equalsIgnoreCase(id)) ){
			Logger.info("ConfirmCollector removed: "+id);
		}else{
			Logger.info("ConfirmCollector not found: "+id);
		}
	}
	/**
	 * Class that checks if age of last data is higher than ttl, if so issue the idle cmds.
	 * Reschedule the task for either ttl time or difference between ttl and time since last data
	 * Code copied from Netty and altered
	 */
	private final class ReaderIdleTimeoutTask implements Runnable {

		private final BaseStream stream;

		ReaderIdleTimeoutTask(BaseStream stream) {
			this.stream = stream;
		}

		@Override
		public void run() {

			if( stream==null) // No use scheduling timeout checks if the stream isn' valid
				return;

			if (!stream.isConnectionValid()) { // No use scheduling timeout if there's no connection
				Logger.warn(stream.getID()+" -> Connection invalid, waiting for reconnect");
				requestReconnection(stream.getID());
				scheduler.schedule(this, stream.readerIdleSeconds, TimeUnit.SECONDS);
				return;
			}
			long currentTime = Instant.now().toEpochMilli();
			long lastReadTime = stream.getLastTimestamp();
			long nextDelay = stream.readerIdleSeconds *1000 - (currentTime - lastReadTime);

			// If the next delay is less than longer ago than a previous idle
			if (nextDelay <= 0 ){
				// Reader is idle - set a new timeout and notify the callback.
				scheduler.schedule(this, stream.readerIdleSeconds, TimeUnit.SECONDS);
				if( nextDelay > -1000*stream.readerIdleSeconds) { // only apply this the first time
					Logger.warn(stream.getID()+" is idle for "+stream.readerIdleSeconds+"s");
					notifyIdle(stream.getID());
				}
			} else {
				// Read occurred before the timeout - set a new timeout with shorter delay.
				scheduler.schedule(this, nextDelay, TimeUnit.MILLISECONDS);
			}
		}
	}
}
