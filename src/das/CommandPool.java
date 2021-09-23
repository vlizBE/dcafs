package das;

import io.email.Email;
import io.email.EmailSending;
import io.email.EmailWorker;
import com.fazecast.jSerialComm.SerialPort;
import io.sms.SMSSending;
import io.stream.StreamManager;
import io.Writable;
import io.collector.FileCollector;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.gis.GisTools;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;
import worker.DebugWorker;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class CommandPool {

	private final ArrayList<Commandable> bulkCommandable = new ArrayList<>();
	private final HashMap<String,Commandable> commandables = new HashMap<>();

	private ArrayList<ShutdownPreventing> sdps;

	private StreamManager streampool = null; // To be able to interact with attached devices
	private EmailWorker emailWorker; // To be able to send emails and get status

	private DAS das;

	private final String workPath;
	private final Path settingsPath;

	static final String UNKNOWN_CMD = "unknown command";

	private EmailSending sendEmail = null;
	private SMSSending sendSMS = null;
	/* ******************************  C O N S T R U C T O R *********************************************************/
	/**
	 * Constructor requiring a link to the @see RealtimeValues for runtime values
	 */
	public CommandPool( String workPath){
		this.workPath=workPath;
		settingsPath = Path.of(workPath,"settings.xml");
		Logger.info("CommandPool started with workpath: "+workPath);
	}
	/**
	 * Add an implementation of the Commandable interface
	 * @param id The first part of the command (so whatever is in front of the : )
	 * @param cmdbl The implementation
	 */
	public void addCommandable( String id, Commandable cmdbl){
		commandables.put(id,cmdbl);
	}
	public void addBulkCommandable( Commandable cmdbl){
		bulkCommandable.add(cmdbl);
	}
	public void addShutdownPreventing( ShutdownPreventing sdp){
		if( sdps==null)
			sdps = new ArrayList<>();
		sdps.add(sdp);
	}
	/* ****************************  S E T U P - C H E C K U P: Adding different parts from dcafs  *********************/
	/**
	 * Give the DAS object, so it has access to everything it might need
	 * 
	 * @param das The reference to everything including itself... should be removed in the future
	 */
	public void setDAS(DAS das) {
		this.das = das;
	}

	/**
	 * To be able to send emails, access to the emailQueue is needed
	 * 
	 * @param emailWorker A reference to the emailworker
	 */
	public void setEmailWorker(EmailWorker emailWorker) {

		this.emailWorker = emailWorker;
		sendEmail = emailWorker.getSender();
	}

	/**
	 * Enable SMS sending from the CommandPool
	 * @param sms The object that allows sms sending
	 */
	public void setSMSSending(SMSSending sms){
		sendSMS = sms;
	}
	/**
	 * To interact with streams/channels, access to the streampool is needed
	 *
	 * @param streampool  A reference to the streampool
	 */
	public void setStreamPool(StreamManager streampool) {
		this.streampool = streampool;
	}

	/* ************************************ * R E S P O N S E *************************************************/

	public void emailResponse( Datagram d ) {
		Logger.info( "Executing email command ["+d.getData()+"], origin: " + d.getOriginID() );
		emailResponse( d, "Bot Reply" );
	}

	public void emailResponse(Datagram d, String header) {
		/* If there's no valid queue, can't do anything */
		if ( sendEmail!=null ) {
			Logger.info("Asked to email to " + d.getOriginID() + " but no worker defined.");
			return;
		}
		/* Notification to know if anyone uses the bot. */
		if ( (!d.getOriginID().startsWith("admin") && !emailWorker.isAddressInRef("admin",d.getOriginID()) ) && header.equalsIgnoreCase("Bot Reply")  ) {
			sendEmail.sendEmail( Email.toAdminAbout("DCAFSbot").content("Received '" + d.getData() + "' command from " + d.getOriginID()) );
		}
		/* Processing of the question */
		d.setData( d.getData().toLowerCase());

		/* Writable is in case the question is for realtime received data */
		String response = createResponse( d.getData(), d.getWritable(), false, true );

		if (!response.toLowerCase().contains(UNKNOWN_CMD)) {
			response = response.replace("[33m ", "");
			sendEmail.sendEmail( Email.to(d.getOriginID()).subject(header).content(response.replace("\r\n", "<br>")));
		} else {
			sendEmail.sendEmail(
					Email.to(d.getOriginID())
							.subject(header)
							.content("Euh " + d.getOriginID().substring(0, d.getOriginID().indexOf(".")) + ", no idea what to do with '" + d.getData() + "'..."));
		}
	}

	/**
	 * A question is asked to the BaseReq through this method, a Writable is
	 * passed for streaming data questions
	 * 
	 * @param question The command/Question to process
	 * @param wr The writable (if any) this question originates from
	 * @param remember If the command should be recorded in the raw data
	 * @return The response to the command/question
	 */
	public String createResponse(String question, Writable wr, boolean remember) {
		return createResponse(question, wr, remember, false);
	}

	/**
	 * A question is asked to the BaseReq through this method, a Writable is
	 * passed for streaming data questions
	 * 
	 * @param question The command/Question to process
	 * @param wr  Writable in order to be able to respond to streaming
	 *                 data questions
	 * @param remember If the command should be recorded in the raw data
	 * @param html     If the response should you html encoding or not
	 * @return The response to the command/question
	 */
	public String createResponse(String question, Writable wr, boolean remember, boolean html) {

		String result = UNKNOWN_CMD;

		if (!html) // if html is false, verify that the command doesn't imply the opposite
			html = question.endsWith("html");

		question = question.replace("html", "");

		if (remember) // If to store commands in the raw log (to have a full simulation when debugging)
			Logger.tag("RAW").info("1\tsystem\t" + question);

		int dp = question.indexOf(":");

		String[] split = new String[]{"",""};
		if( dp != -1){
			split[0]=question.substring(0, question.indexOf(":"));
			split[1]=question.substring(question.indexOf(":")+1);
		}else{
			split[0]=question;
		}
		split[0]=split[0].toLowerCase();
		String find = split[0].replaceAll("[0-9]+", "_");
		
		if( find.equals("i_c") || find.length() > 3 ) // Otherwise, adding integrated circuits with their name is impossible
			find = split[0];
			
		find = find.isBlank() ? "nothing" : find;
		
		switch( find ){
			case "admin": result=doADMIN(split, html); break;
			case "cmds":     result=doCMDS( split, html); break;
			case "checksum": result=doCHECKSUM(split[0]); break;
			case "conv": result=doCONVert(split); break;

			case "email": result=doEMAIL(split, wr,html); break;
			case "fc": result=doFileCollector(split, html); break;
			case "help": case "h": result=doHelp(split,html); break;
			case "h_": result=doH_(split); break;

			case "lt": result=doListThread(split); break;
			case "nothing": result=doNOTHING(split,wr); break;
			case "raw": case "stream": result=doRAW(split, wr); break;
			case "read": result=doREAD(split, wr ); break;
			case "retrieve": result=doRETRIEVE(split, wr,html); break;
			case "reqtasks": result=doREQTASKS(split); break;
			case "rios": result=doRIOS(split, wr,html); break;
			
			case "trans": result=doTRANS(split, wr); break;
			case "sd": result=doShutDown(split, wr,html); break;
			case "st": result=doSTatus(split, html); break;
			case "stop": result=doSTOP( wr ); break;
			case "serialports": result=doSERIALPORTS(split, html); break;
			case "sleep": result=doSLEEP(split, wr); break;
			case "ss": result=doStreamS(split, wr,html); break;
			case "s_": result=doS_(split); break;
			case "upgrade": result=doUPGRADE(split, wr,html); break;
		}	

		if( result.startsWith(UNKNOWN_CMD) ){
			var cmdOpt = commandables.entrySet().stream()
						.filter( ent -> {
							String key = ent.getKey();
							if( key.equals(split[0]))
								return true;
							return Arrays.stream(key.split(";")).anyMatch(k->k.equals(split[0]));
						}).map(Map.Entry::getValue).findFirst();

			if( cmdOpt.isPresent()) {
				result = cmdOpt.get().replyToCommand(split, wr, html);
				if( result.startsWith(UNKNOWN_CMD) ) {
					Logger.warn("Found "+find+" but corresponding cmd to do: " + question);
				}
			}else{
				String res;
				for( var cd : bulkCommandable ){
					result = cd.replyToCommand(split,wr,html);
					if( !result.startsWith(UNKNOWN_CMD))
						break;
				}
				if( result.startsWith(UNKNOWN_CMD)) {
					if (split[1].equals("?") || split[1].equals("list")) {
						var nl = html ? "<br>" : "\r\n";
						res = doCmd("tm", split[0] + ",sets", wr) + nl + doCmd("tm", split[0] + ",tasks", wr);
					}else if( split[1].equalsIgnoreCase("reload")){
						res = doCmd("tm","reload,"+split[0],wr);
					} else {
						res = doCmd("tm", "run," + split[0] + ":" + split[1], wr);
					}
					if (!res.startsWith("No ") && !res.startsWith("Not "))
						result = res;
				}
				if( result.startsWith(UNKNOWN_CMD) ) {
					Logger.warn("Not defined:" + question + " because no method named " + find + ".");
				}
			}
		}

		if( wr!=null ) {
			if (!wr.getID().equalsIgnoreCase("telnet"))
				Logger.debug("Hidden response for " + wr.getID() + ": " + result);
		}else{
			Logger.debug("Hidden response to " + question + ": " + result);
		}
		if( result.isEmpty())
			return "";
		return result + (html ? "<br>" : "\r\n");
	}
	/* ****************************************** C O M M A N D A B L E ********************************************* */
	private String doCmd( String id, String command, Writable wr){
		var c = commandables.get(id);
		if( c==null) {
			Logger.error("No "+id+" available");
			return UNKNOWN_CMD+": No "+id+" available";
		}
		return c.replyToCommand(new String[]{id,command},wr,false);
	}
	/* ******************************************  C O M M A N D S ****************************************************/
	/**
	 * Command that creates a list of all available commands. Then execute a request
	 * with '?' from each which should return the info.
	 * 
	 * @param request The full request as received, [0]=method and [1]=command
	 * @param html    True if the command needs to be html formatted
	 * @return Response to the request
	 */
	public String doCMDS(String[] request, boolean html) {
		String nl = html ? "<br>" : "\r\n";
		
		
		return "Needs a rewrite";
	}
	/* ******************************************************************************/
	/**
	 * Calculate the checksum of the given item, for now only rawyesterday exists
	 * @param request The full command checksum:something
	 * @return Calculated checksum
	 */
	public String doCHECKSUM( String request ){
		
		// Check for files with wildcard? 2019-07-24_RAW_0.log.zip
		StringBuilder b = new StringBuilder();

		switch( request ){
			case "rawyesterday":
				String yesterday = "raw"+File.separator+"zipped"+File.separator+TimeTools.formatNow( "yyyy-MM", -1)+File.separator+TimeTools.formatNow( "yyyy-MM-dd", -1)+"_RAW_x.log.zip";
				int cnt=0;
				String path = yesterday.replace("x", ""+cnt);
				boolean ok = Files.exists( Path.of(workPath,path) );
				
				while(ok){					
					String md5 = MathUtils.calculateMD5( Path.of(workPath,path) );
					b.append(path).append("\t").append(md5).append("\r\n");
					cnt++;
					path = yesterday.replace("x", ""+cnt);
					ok = Files.exists( Path.of(workPath,path) );
				}
				return b.toString();
			case "?":
				return "checksum:rawyesterday -> Calculate checksum of the stored raw data (WiP";	
			default:
				return UNKNOWN_CMD;
		}
	}  
	/* ********************************************************************************************/
	/**
	 * Try to update a file received somehow (email or otherwise)
	 * Current options: dcafs,script and settings (dcafs is wip)
	 * 
	 * @param request The full command update:something
	 * @param wr The 'writable' of the source of the command
	 * @param html Whether to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doUPGRADE(String[] request, Writable wr, boolean html) {
		
		Path p;
		Path to;
		Path refr;

		String[] spl = request[1].split(",");

		switch (spl[0]) {
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
				join.add( "upgrade:dcafs -> Try to update dcafs (todo)")
					.add( "upgrade:tmscript,tm id -> Try to update the given taskmanagers script")
					.add( "upgrade:settings -> Try to update the settings.xml");
				return join.toString();			
			case "dcafs":
				return "todo";
			case "tmscript"://fe. update:tmscript,tmid
				var ori = doCmd("tm","getpath,"+spl[1],wr );
				if( ori.isEmpty() )
					return "No such script";

				p = Path.of(ori);
				to = Path.of(ori.replace(".xml", "")+"_" + TimeTools.formatUTCNow("yyMMdd_HHmm") + ".xml");
				refr = Path.of(workPath,"attachments",spl[1]);
				try {
					if( Files.exists(p) && Files.exists(refr) ){
						Files.copy(p, to );	// Make a backup if it doesn't exist yet
						Files.move(refr, p , StandardCopyOption.REPLACE_EXISTING );// Overwrite
						
						// somehow reload the script
						return doCmd("tm","reload,"+spl[1],wr);// Reloads based on id
					}else{
						Logger.warn("Didn't find the needed files.");
						return "Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					Logger.error(e);
				}
				break;
			case "settings":
				p = Path.of(workPath,"settings.xml");

				to = Path.of( workPath,"settings_" + TimeTools.formatNow("yyMMdd_HHmm") + ".xml");
				refr = Path.of( workPath,"attachments"+File.separator+"settings.xml");
				try {
					if( Files.exists(p) && Files.exists(refr) ){
						Files.copy(p, to );	// Make a backup if it doesn't exist yet
						Files.copy(refr, p , StandardCopyOption.REPLACE_EXISTING );// Overwrite
						das.setShutdownReason( "Replaced settings.xml" );    // restart das
						System.exit(0);
					}else{
						Logger.warn("Didn't find the needed files.");
						return "Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				break;
			default: return UNKNOWN_CMD;
		}
		return UNKNOWN_CMD;
	}
	/**
	 * Command to retrieve a setup file, can be settings.xml or a script
	 * fe. retrieve:script,scriptname.xml or retrieve:setup for the settings.xml
	 * 
	 * @param request The full command update:something
	 * @param wr The 'writable' of the source of the command
	 * @param html Whether to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doRETRIEVE(String[] request, Writable wr, boolean html) {
		
		if( sendEmail==null)
			return "Can't retrieve without EmailWorker";

		String[] spl = request[1].split(",");
		
		switch( spl[0] ){
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n","",html?"<br>":"\r\n");
				join.add( "retrieve:tmscript,tm id,<email/ref> -> Request the given taskmanager script through email")
					.add( "retrieve:settings,<email/ref> -> Request the current settings.xml through email");
				return join.toString();
			case "tmscript":case "tmscripts":
				if( spl.length < 3 )
					return "Not enough arguments retrieve:type,tmid,email in "+request[0]+":"+request[1];

				var p = doCmd("tm","getpath,"+spl[1],wr );
				if( p.isEmpty() )
					return "No such script";

				sendEmail.sendEmail( Email.to(spl[2]).subject("Requested tm script: "+spl[1]).content("Nothing to say").attachment(p) );
				return "Tried sending "+spl[1]+" to "+spl[2];
			case "setup":
			case "settings":
				Path set = Path.of(workPath,"settings.xml");
				if( Files.notExists(set) ){
					return "No such file: "+ set;
				}
				if( spl.length!=2)
					return "Not enough arguments, expected retrieve:setup,email/ref";
				sendEmail.sendEmail(Email.to(spl[1]).subject("Requested file: settings.xml").content("Nothing to say").attachment(workPath+File.separator+"settings.xml") );
				return "Tried sending settings.xml to "+spl[1];
			default: return UNKNOWN_CMD+":"+spl[0];
		}
	}
	/* *******************************************************************************/
	public String doTRANS(String[] request, Writable wr ){
		return doCmd("ts","forward,"+request[1],wr);
	}
	public String doSTOP( Writable wr ) {
		if( streampool.removeWritable(wr) )
			return "Removed forwarding to "+wr.getID();
		commandables.values().forEach( c -> c.removeWritable(wr) );
		return "No matches found for "+wr.getID();
	}
	public String doRAW( String[] request, Writable wr ){
		if( streampool.addForwarding(request[1], wr ) ){
			return "Request for "+request[0]+":"+request[1]+" ok.";
		}else{
			return "Request for "+request[0]+":"+request[1]+" failed.";
		}
	}

	/**
	 * Execute commands associated with the @see StreamManager
	 * 
	 * @param request The full command split on the first :
	 * @param wr The 'writable' of the source of the command
	 * @param html Whether to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doStreamS(String[] request, Writable wr, boolean html ){
		if( streampool == null ){
			return "No StreamManager defined.";
		}
		return streampool.replyToCommand(request[1], wr, html);
	}
	public String doRIOS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return "rios -> Get a list of the currently active streams.";
		return doStreamS( new String[]{"streams","rios"}, wr, html);
	}
	public String doH_( String[] request){
		if( streampool == null )
			return "No StreamManager defined.";

		if( request[1].equals("?") ){
			return "Hx:y -> Send the hex y to stream x";
		}
		int nr = Tools.parseInt( request[0].substring(1), -1 );    		
		if( nr >= 0 && nr <= streampool.getStreamCount()){
			String channel = streampool.getStreamID(nr);
			
			boolean ok = !streampool.writeBytesToStream(channel, Tools.fromHexStringToBytes(request[1]) ).isEmpty();

			if( !ok )
				return "Failed to send "+request[1]+" to "+channel;
			return "Sending command '"+request[1]+"' to "+channel;
		}else{
			switch( streampool.getStreamCount() ){
				case 0:
					return "No streams active to send data to.";
				case 1:
					return "Only one stream active. S1:"+streampool.getStreamID(0);
				default:
					return "Invalid number chosen! Must be between 1 and "+streampool.getStreamCount();    					    			
			}
		}
	}
	public String doS_( String[] request ){	
		
		if( streampool == null )
			return "No StreamManager defined.";

		if( request[1].equals("?") ){
			return "Sx:y -> Send the string y to stream x";
		}
		if( request[1].isEmpty() )
			return "No use sending an empty string";

		String stream = streampool.getStreamID( Tools.parseInt( request[0].substring(1), 0 ) -1);
		if( !stream.isEmpty()){
			request[1] = request[1].replace("<cr>", "\r").replace("<lf>", "\n"); // Normally the delimiters are used that are chosen in settings file, extra can be added
			
			if( !streampool.writeToStream(stream, request[1], "" ).isEmpty() )
				return "Sending '"+request[1]+"' to "+stream;
			return "Failed to send "+request[1]+" to "+stream;
			
		}else{
			switch( streampool.getStreamCount() ){
				case 0:
					return "No streams active to send data to.";
				case 1:
					return "Only one stream active. S1:"+streampool.getStreamID(0);
				default:
					return "Invalid number chosen! Must be between 1 and "+streampool.getStreamCount();    					    			
			}
		}
	}
	/**
	 * Execute commands associated with serialports on the system
	 * 
	 * @param request The full command split on the first :
	 * @param html Whether to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doSERIALPORTS( String[] request, boolean html ){
		StringBuilder response = new StringBuilder();
		
		if( request[1].equals("?") )
			return " -> Get a list of available serial ports on the PC running dcafs.";

		response.append("Ports found: ").append(html ? "<br>" : "\r\n");
		for( SerialPort p : SerialPort.getCommPorts())
			response.append(p.getSystemPortName()).append(html ? "<br>" : "\r\n");
		response.append(html?"<br>":"\r\n");
		return response.toString();
	}
	/**
	 * Execute command to shut down dcafs, can be either sd or shutdown or sd:reason
	 * 
	 * @param request The full command split on the first :
	 * @param wr The 'writable' of the source of the command
	 * @param html Whether to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doShutDown( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return "sd:reason -> Shutdown the program with the given reason, use force as reason to skip checks";
		String reason = request[1].isEmpty()?"Telnet requested shutdown":request[1];
		if( !request[1].equalsIgnoreCase("force") && sdps!=null) {
			for (var sdp : sdps) {
				if (sdp.shutdownNotAllowed()) {
					if (wr != null)
						wr.writeLine("Shutdown prevented by " + sdp.getID());
					return "Shutdown prevented by " + sdp.getID();
				}
			}
		}
		das.setShutdownReason( reason );
		System.exit(0);                    
		return "Shutting down program..."+ (html?"<br>":"\r\n");
	}
	/**
	 * Get the content of the help.txt
	 * 
	 * @param request The full command split on the first :
	 * @param html Whether to use html for newline etc
	 * @return Content of the help.txt or 'No telnetHelp.txt found' if not found
	 */
	public String doHelp( String[] request, boolean html ){		
		String nl = html?"<br":"\r\n";
		StringJoiner join = new StringJoiner(nl,"",nl);
		join.setEmptyValue(UNKNOWN_CMD+": "+request[0]+":"+request[1]);
		switch(request[1]){
			case "?":
					join.add("help -> First use tips");
				break;
				case "":
					join.add(TelnetCodes.TEXT_RED+"General commands"+TelnetCodes.TEXT_YELLOW);
					join.add("  st -> Get the current status of dcafs, lists streams, databases etc");
					join.add("  cmds -> Get al list of all available commands").add("");
					join.add(TelnetCodes.TEXT_RED+"General tips"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Look at settings.xml file (in dcafs.jar folder) in a viewer to see what dcafs does")
						.add("   -> Open two or more telnet instances fe. one for commands and other for live data").add("");
					join.add(TelnetCodes.TEXT_RED+"Recommended workflow:"+TelnetCodes.TEXT_YELLOW);
					join.add(TelnetCodes.TEXT_GREEN+"1) Connect to a data source"+TelnetCodes.TEXT_YELLOW)
						.add("   -> For udp, tcp and serial, use streams:? or ss:? for relevant commands")
						.add("   -> For MQTT, use mqtt:? for relevant commands")
						.add("   -> For I2C/SPI check the manual and then use i2c:?");
					join.add(TelnetCodes.TEXT_GREEN+"2) Look at received data"+TelnetCodes.TEXT_YELLOW)
						.add("   -> raw:streamid -> Show the data received at the stream with the given id eg. raw:gps")
						.add("   -> raw:label:streamlabel -> Show the data received at the streams with the given label")
						.add("   -> mqtt:forward,id -> Show the data received from the mqtt broker with the given id")
						.add("   -> mqtt:forward,id -> Show the data received from the mqtt broker with the given id")
						.add("   -> i2c:forward,id -> Show the data received from the i2c device with the given id");
					join.add(TelnetCodes.TEXT_GREEN+"3) Alter the data stream to a delimited set of values"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Use MathForward to apply arithmetic operations on it, see mf:?")
						.add("      See the result with math:id")
						.add("   -> If the stream contains various messages, split it out using FilterForward, see ff:?")
						.add("      See the result with filter:id");
					join.add(TelnetCodes.TEXT_GREEN+"4) Collect the data after the optional math and filter"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Use generics to store the data in memory, see gens:?")
						.add("   -> Use MathCollector to calculate averages, standard deviation etc, see mc:? (todo:implementing commands)")
						.add("   -> See a snapshot of the data in memory with rtvals or rtval:name to receive updates on a specific on")
						.add("   -> Use ValMap to collect data that's formatted according to param,value (or any other delimiter)");
					join.add(TelnetCodes.TEXT_GREEN+"5) Create/connect to a database"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> Send dbm:? for commands related to the database manager");
					join.add(TelnetCodes.TEXT_GREEN+"6) Somehow get the data received in 1 into 5"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> See the manual about how to use generics (Reference Guide -> Generics)");
					join.add(TelnetCodes.TEXT_GREEN+"7) Do other things"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> For scheduling events, check taskmanager");
					join.add("   -> ...").add("");
				break;
			case "start": 

				break;
			default:	return UNKNOWN_CMD+":"+request[1];
		}
		return join.toString();
	}

	public String doListThread( String[] request ){	
		if( request[1].equals("?") )
			return " -> Get a list of the currently active threads";

		StringBuilder response = new StringBuilder();
		ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        Thread[] lstThreads = new Thread[currentGroup.activeCount()];
		currentGroup.enumerate(lstThreads);
		response.append("\r\n");
		for (Thread lstThread : lstThreads)
			response.append("Thread ID:").append(lstThread.getId()).append(" = ").append(lstThread.getName()).append("\r\n");
		return response.toString();   
	}
	public String doREAD( String[] request, Writable wr ){
		das.getDataQueue().add( Datagram.build("").writable(wr).label("read:"+request[1]) ); //new Datagram(wr,"",1,"read:"+request[1]));
		return "Request for readable "+request[1]+" from "+wr.getID()+" issued";
	}
	public String doADMIN( String[] request, boolean html ){
		String nl = html?"<br":"\r\n";
		String[] cmd = request[1].split(",");
		switch( cmd[0] ){
			case "?":
				StringJoiner join = new StringJoiner(nl);
				join.add("admin:getlogs -> Get the latest logfiles")
					.add("admin:gettasklog -> Get the taskmananger log")
					.add("admin:adddebugnode -> Adds a debug node with default values")
					.add("admin:sms -> Send a test SMS to the admin number")
					.add("admin:haw -> Stop all workers")
					.add("admin:clock -> Get the current timestamp")
					.add("admin:regex,<regex>,<match> -> Test a regex")
					.add("admin:ipv4 -> Get the IPv4 and MAC of all network interfaces")
					.add("admin:ipv6 -> Get the IPv6 and MAC of all network interfaces")
					.add("admin:gc -> Fore a java garbage collection")
					.add("admin:reboot -> Reboot the computer (linux only)");
				return join.toString();
			case "getlogs":
				if( sendEmail != null ){
					sendEmail.sendEmail( Email.toAdminAbout("Statuslog").subject("File attached (probably)")
							.attachment( Path.of(workPath,"logs","info.log") ));
					sendEmail.sendEmail( Email.toAdminAbout("Errorlog").subject("File attached (probably)")
							.attachment(Path.of(workPath,"logs","errors_"+TimeTools.formatUTCNow("yyMMdd")+".log" )) );
					return "Sending logs (info,errors) to admin...";
				}
				return "No email functionality active.";
			case "gettasklog":
				if(sendEmail!=null){
					sendEmail.sendEmail(Email.toAdminAbout("Taskmanager.log").subject("File attached (probably)")
							.attachment(Path.of(workPath, "logs", "taskmanager.log")));
					return "Trying to send taskmanager log";
				}
				return "No email functionality active.";

			case "getlastraw":
				Path it = Path.of(workPath,"raw",TimeTools.formatUTCNow("yyyy-MM"));
				if( sendEmail==null)
					return "No email functionality active.";
				try {
					var last = Files.list(it).filter( f -> !Files.isDirectory(f)).max( Comparator.comparingLong( f -> f.toFile().lastModified()));
					if( last.isPresent() ){
						var path = last.get();
						sendEmail.sendEmail(Email.toAdminAbout("Taskmanager.log").subject("File attached (probably)").attachment(path));
						return "Tried sending " + path;
					}
					return "File not found";
				} catch (IOException e) {
					e.printStackTrace();
					return "Something went wrong trying to get the file";
				}
			case "adddebugnode":
				DebugWorker.addBlank(XMLfab.withRoot(settingsPath,"dcafs","settings"));
				return "Tried to add node";
			case "sms":
				if(sendSMS!=null ) {
					sendSMS.sendSMS("admin", "test");
					return "Trying to send SMS";
				}
				return "No SMS functionality present";
			case "haw":
				das.haltWorkers();
				return nl+"Stopping all worker threads.";
			case "clock": return TimeTools.formatLongUTCNow();
			case "regex":
				if( cmd.length != 3 )
					return "Invalid amount of parameters";
				return "Matches? "+cmd[1].matches(cmd[2]);
			case "ipv4": return Tools.getIP("", true);
			case "ipv6": return Tools.getIP("", false);
			case "gc":
				System.gc();
				return "Tried to execute GC";
			case "reboot":
				String os = System.getProperty("os.name").toLowerCase();
				if( !os.startsWith("linux")){
					return "Only Linux supported for now.";
				}
				try {
					ProcessBuilder pb = new ProcessBuilder("bash","-c","shutdown -r +1");
					pb.inheritIO();

					Logger.error("Started restart attempt at "+TimeTools.formatLongUTCNow());
					pb.start();

					System.exit(0); // shutting down das
				} catch (IOException e) {
					Logger.error(e);
				}
				return "Never gonna happen?";

			default: return UNKNOWN_CMD+" : "+request[1];
		}
	}
	public String doEMAIL( String[] request, Writable wr, boolean html ){
		
		if( request[1].equalsIgnoreCase("addblank") ){
			if( EmailWorker.addBlankEmailToXML(  XMLfab.withRoot(settingsPath, "settings"), true,true) )
				return "Adding default email settings";
			return "Failed to add default email settings";
		}

		if( emailWorker == null ){
			if(request[1].equals("reload") 
					&& XMLfab.withRoot(settingsPath, "settings").getChild("email").isPresent() ){
				das.addEmailWorker();
			}else{
				return "No EmailWorker defined (yet), use email:addblank to add blank to xml.";
			}
		}
		// Allow a shorter version to email to admin, replace it to match the standard command
		request[1] = request[1].replace("toadmin,","send,admin,");

		// Check if the content part of a send command is a command itself, if so replace it
		if( request[1].startsWith("send,") ){ // If it's a send request
			String[] parts = request[1].split(",");
			if( parts.length==4){ // Check if the amount of components is correct
				String rep = createResponse(parts[3],wr,false,true); //if so, use content as command
				if( !rep.startsWith("unknown")) // if this resulted in a response
					parts[3]=rep; //replace the command
				request[1] = String.join(",",parts);
			}
		}
		return emailWorker.replyToSingleRequest(request[1], html);
	}

	public String doREQTASKS( String[] request ){
		if( request[1].equals("?") )
			return ":x -> Send a list of all the taskset executions to x";

		if(  request[1].equals("") )
			return "No recipient given.";
		
		if( sendEmail==null )
			return "No email functionality active";

		sendEmail.sendEmail( Email.to(request[1]).subject("Executed tasksets").content("Nothing to add")
				.attachment( Path.of(workPath,"logs","tasks.csv").toString() ) );
		return "Sending log of taskset execution to "+request[1];
	}

	public String doNOTHING( String[] request, Writable wr ){
		if( request[1].equals("?") )
			return " -> Clear the datarequests";
		if( wr != null ){
			streampool.removeWritable(wr);
			commandables.values().forEach( c -> c.removeWritable(wr) );
		}
		return "Clearing all data requests\r\n";
	}	

	
	public String doSTatus( String[] request, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a status update";

		String response = "";	
		try{
			response =  das.getStatus(html);   
		}catch( java.lang.NullPointerException e){
			Logger.error(e);
		}
		return response;       	
	}

	public String doCONVert( String[] request){
		if( request[1].equals("?") )
			return " -> Convert a coordinate in the standard degrees minutes format";		
		
		BigDecimal bd60 = BigDecimal.valueOf(60);	            	
		StringBuilder b = new StringBuilder();
		String[] items = request[1].split(";");
		ArrayList<Double> degrees = new ArrayList<>();
		
		for( String item : items ){
			String[] nrs = item.split(" ");		            	
			if( nrs.length == 1){//meaning degrees!	 		            				            		
				degrees.add(Tools.parseDouble(nrs[0], 0));		            			            		
			}else if( nrs.length == 3){//meaning degrees minutes seconds!
				double degs = Tools.parseDouble(nrs[0], 0);
				double mins = Tools.parseDouble(nrs[1], 0);
				double secs = Tools.parseDouble(nrs[2], 0);
				
				BigDecimal deg = BigDecimal.valueOf(degs);
				BigDecimal sec = BigDecimal.valueOf(secs);	            		
				BigDecimal min = sec.divide(bd60, 7, RoundingMode.HALF_UP).add(BigDecimal.valueOf(mins));
				deg = deg.add(min.divide(bd60,7, RoundingMode.HALF_UP));
				degrees.add(deg.doubleValue());
			}
		}
		if( degrees.size()%2 == 0 ){ //meaning an even number of values
			for( int a=0;a<degrees.size();a+=2){
				double la = degrees.get(a);
				double lo = degrees.get(a+1);
							
				b.append("Result:").append(la).append(" and ").append(lo).append(" => ").append(GisTools.fromDegrToDegrMin(la, -1, "°")).append(" and ").append(GisTools.fromDegrToDegrMin(lo, -1, "°"));
				b.append("\r\n");
			}
		}else{
			for( double d : degrees ){
				b.append("Result: ").append(degrees).append(" --> ").append(GisTools.fromDegrToDegrMin(d, -1, "°")).append("\r\n");
			}
		}    				
		return b.toString();
	}
	public String doSLEEP( String[] request, Writable wr ){
		if( request[1].equals("?") || request[1].split(",").length!=2 ){
			return "sleep:rtc,<time> -> Let the processor sleep for some time using an rtc fe. sleep:1,5m sleep 5min based on rtc1";
		}
		String os = System.getProperty("os.name").toLowerCase();
		if( !os.startsWith("linux")){
			return "Only Linux supported for now.";
		}
		
		int seconds;
		String[] cmd = request[1].split(",");
		seconds = TimeTools.parsePeriodStringToSeconds(cmd[1]);

		
		try {
			StringJoiner tempScript = new StringJoiner( "; ");
			tempScript.add("echo 0 > /sys/class/rtc/rtc"+cmd[0]+"/wakealarm");
			tempScript.add("echo +"+seconds+" > /sys/class/rtc/rtc"+cmd[0]+"/wakealarm");
			tempScript.add("echo mem > /sys/power/state");

			ProcessBuilder pb = new ProcessBuilder("bash","-c", tempScript.toString());
			pb.inheritIO();
			Process process;

			Logger.error("Started sleep attempt at "+TimeTools.formatLongUTCNow());
			process = pb.start();
			process.waitFor();
			Logger.error("Woke up again at "+TimeTools.formatLongUTCNow());

			// do wake up stuff
			var tmCmd = commandables.get("tm");
			if( tmCmd != null ){
				tmCmd.replyToCommand(new String[]{"tm","run,*:wokeup"},wr,false);
			}
		} catch (IOException | InterruptedException e) {
			Logger.error(e);
		}
		return "Waking up at "+TimeTools.formatLongUTCNow();
	}


	public String doFileCollector( String[] request, boolean html ) {
		String[] cmds = request[1].split(",");
		StringJoiner join = new StringJoiner(html?"<br":"\r\n");

		Optional<FileCollector> fco ;

		switch( cmds[0] ) {
			case "?":
				join.add(TelnetCodes.TEXT_MAGENTA+"The FileCollectors store data from sources in files with custom headers and optional rollover");
				join.add(TelnetCodes.TEXT_GREEN+"Create/Alter the FileCollector"+TelnetCodes.TEXT_YELLOW)
					 .add("   fc:addnew,id,src,path -> Create a blank filecollector with given id, source and path")
					 .add("   fc:alter,id,param:value -> Alter some elements, options: eol, path, sizelimit, src");
				join.add(TelnetCodes.TEXT_GREEN+"Add optional parts"+TelnetCodes.TEXT_YELLOW)
					 .add("   fc:addrollover,id,count,unit,format,zip? -> Add rollover (unit options:min,hour,day,week,month,year")
					 .add("   fc:addcmd,id,trigger:cmd -> Add a triggered command, triggers: maxsize,idle,rollover")
					 .add("   fc:addheader,id,headerline -> Adds the header to the given fc")
					 .add("   fc:addsizelimit,id,size,zip? -> Adds a limit of the given size with optional zipping");
				join.add(TelnetCodes.TEXT_GREEN+"Get info"+TelnetCodes.TEXT_YELLOW)
					 .add("   fc:list -> Get a list of all active File Collectors")
					 .add("   fc:? -> Show this message");
				return join.toString();
			case "addnew":
				if( cmds.length<4)
					return "Not enough arguments given: fc:addnew,id,src,path";
				FileCollector.addBlankToXML(XMLfab.withRoot(settingsPath,"dcafs"),cmds[1],cmds[2],cmds[3]);
				var fc = das.addFileCollector(cmds[1]);
				fc.addSource(cmds[2]);
				fc.setPath( Path.of(cmds[3]), workPath );

				return "FileCollector "+cmds[1]+" created and added to xml.";
			case "list":
				return das.getFileCollectorsList(html?"<br":"\r\n");
			case "addrollover":
				if( cmds.length<6)
					return "Not enough arguments given: fc:addrollover,id,count,unit,format,zip?";

				fco = das.getFileCollector(cmds[1]);
				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				if( fco.get().setRollOver(cmds[4],NumberUtils.toInt(cmds[2]),TimeTools.convertToRolloverUnit(cmds[3]),Tools.parseBool(cmds[5],false)) ) {
					XMLfab.withRoot(settingsPath, "dcafs", "collectors")
							.selectOrAddChildAsParent("file", "id", cmds[1])
							.alterChild("rollover",cmds[4]).attr("count",cmds[2]).attr("unit",cmds[3]).attr("zip",cmds[5]).build();
					return "Rollover added";
				}
				return "Failed to add rollover";
			case "addheader":
				if( cmds.length<3)
					return "Not enough arguments given: fc:addheader,id,header";

				fco = das.getFileCollector(cmds[1]);
				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];
				fco.get().flushNow();
				fco.get().addHeaderLine(cmds[2]);
				XMLfab.withRoot(settingsPath, "dcafs", "collectors")
						.selectOrAddChildAsParent("file", "id", cmds[1])
						.addChild("header", cmds[2]).build();
				return "Header line added to "+cmds[1];
			case "addcmd":
				if( cmds.length<3)
					return "Not enough arguments given: fc:adcmd,id,trigger:cmd";
				fco = das.getFileCollector(cmds[1]);
				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				String[] cmd = cmds[2].split(":");
				if( fco.get().addTriggerCommand(cmd[0],cmd[1]) ) {
					XMLfab.withRoot(settingsPath, "dcafs", "collectors")
							.selectOrAddChildAsParent("file", "id", cmds[1])
							.addChild("cmd", cmd[1]).attr("trigger", cmd[0]).build();
					return "Triggered command added to "+cmds[1];
				}
				return "Failed to add command, unknown trigger?";
			case "addsizelimit":
				if( cmds.length<4)
					return "Not enough arguments given: fc:addsizelimit,id,size,zip?";
				fco = das.getFileCollector(cmds[1]);

				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				fco.get().setMaxFileSize(cmds[2],Tools.parseBool(cmds[3],false));
				XMLfab.withRoot(settingsPath, "dcafs", "collectors")
						.selectOrAddChildAsParent("file", "id", cmds[1])
						.addChild("sizelimit", cmds[2]).attr("zip", cmds[3]).build();
				return "Size limit added to "+cmds[1];
			case "alter":
				if( cmds.length<3)
					return "Not enough arguments given: fc:alter,id,param:value";
				int a = cmds[2].indexOf(":");
				if( a == -1)
					return "No valid param:value pair";

				String[] alter = {cmds[2].substring(0,a),cmds[2].substring(a+1)};
				fco = das.getFileCollector(cmds[1]);

				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				var fab = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
										.selectOrAddChildAsParent("file", "id", cmds[1]);

				switch(alter[0]){
					case "path":
						fco.get().setPath(Path.of(alter[1]),workPath);
						fab.alterChild("path",alter[1]).build();
						return "Altered the path";
					case "sizelimit":
						fco.get().setMaxFileSize(alter[1]);
						fab.alterChild("sizelimit",alter[1]).build();
						return "Altered the size limit to "+alter[1];
					case "eol":
						fco.get().setLineSeparator( Tools.fromEscapedStringToBytes(alter[1]));
						fab.attr("eol",alter[1]).build();
						return "Altered the eol string to "+alter[1];
					case "charset":
						fab.attr("charset",alter[1]).build();
						return "Altered the charset to "+alter[1];
					case "src":
						fco.get().addSource(alter[1]);
						fab.attr("src",alter[1]).build();
						return "Source altered to "+alter[1];
					default:
						return "No such param "+alter[0];
				}
			case "reload":
				if( cmds.length!=2)
					return "Not enough arguments given: fc:reload,id";

				fco = das.getFileCollector(cmds[1]);

				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				var opt = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
						.getChild("file", "id", cmds[1]);
				if( opt.isPresent() ) {
					fco.get().flushNow();
					fco.get().readFromXML(opt.get(), workPath);
				}

		}
		return UNKNOWN_CMD+": "+request[0]+":"+request[1];
	}
}
