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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.database.*;
import util.gis.GisTools;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;
import worker.DebugWorker;
import worker.Generic;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CommandPool {

	private static DateTimeFormatter secFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private ArrayList<Commandable> bulkCommandable = new ArrayList<>();
	private HashMap<String,Commandable> commandables = new HashMap<>();

	private ArrayList<ShutdownPreventing> sdps;

	private DataProviding dataProvider; // To have access to the current values
	private StreamManager streampool = null; // To be able to interact with attached devices
	private EmailWorker emailWorker; // To be able to send emails and get status
	private IssuePool issues=null;
	private DatabaseManager dbManager;

	private String title = "";

	private DAS das;

	Map<String, Method> methodMapping = new HashMap<>();
	Document xml;

	private String workPath;
	private Path settingsPath;

	static final String UNKNOWN_CMD = "unknown command";

	private Optional<EmailSending> sendEmail = Optional.empty();
	private Optional<SMSSending> sendSMS = Optional.empty();
	/* ******************************  C O N S T R U C T O R *********************************************************/
	/**
	 * Constructor requiring a link to the @see RealtimeValues for runtime values
	 * @param dataProvider The current dataprovider
	 */
	public CommandPool(DataProviding dataProvider, String workPath){
		this.dataProvider = dataProvider;
		this.workPath=workPath;
		settingsPath = Path.of(workPath,"settings.xml");
		Logger.info("CommandPool started with workpath: "+workPath);
	}
	/**
	 * Constructor requiring a link to the @see RealtimeValues for runtime values and @see IssueCollector to notify problems
	 * @param dataProvider The current dataprovider
	 * @param issues The collector for the issues created by the BaseReq
	 */
	public CommandPool(RealtimeValues dataProvider, IssuePool issues, String workPath) {
		this(dataProvider,workPath);
		this.issues = issues;
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
	 * Give the DAS object so it has access to everything it might need
	 * 
	 * @param das The reference to verything including itself... should be removed in the future
	 */
	public void setDAS(DAS das) {
		this.das = das;
	}

	/**
	 * To be able to send emails, access to the emailQueue is needed
	 * 
	 * @param emailWorker An reference to the emailworker
	 */
	public void setEmailWorker(EmailWorker emailWorker) {

		this.emailWorker = emailWorker;
		sendEmail = Optional.ofNullable(emailWorker.getSender());
	}

	/**
	 * Enable SMS sending from the CommandPool
	 * @param sms The object that allows sms sending
	 */
	public void setSMSSending(SMSSending sms){
		sendSMS = Optional.ofNullable(sms);
	}
	/**
	 * To interact with streams/channels, access to the streampool is needed
	 *
	 * @param streampool  A reference to the streampool
	 */
	public void setStreamPool(StreamManager streampool) {
		this.streampool = streampool;
	}
	/**
	 * To have access to the realtime values
	 * 
	 * @param rtvals A reference to the RealtimeValues
	 */
	public void setDataProvider(DataProviding dataProvider) {
		this.dataProvider = dataProvider;
	}

	/**
	 * Set the IssueCollector to get answers from it
	 * 
	 * @param issues The currently used IssueCollector
	 */
	public void setIssues(IssuePool issues) {
		this.issues = issues;
	}
	/**
	 * Check if the given issue is currently active
	 * 
	 * @param issue The issue to check
	 * @return True if it's active, false if it isn't (or doesn't exists)
	 */
	public boolean checkIssue(String issue) {
		return issues.isActive(issue);
	}
	public ArrayList<String> getActiveIssues(){
		return issues.getActives();
	}
	/**
	 * Set the DatabaseManager to get answers from it
	 * 
	 * @param manager The sqlitesManager currently used
	 */
	public void setDatabaseManager(DatabaseManager manager) {
		dbManager = manager;
	}

	/* ************************************ * R E S P O N S E *************************************************/

	public void emailResponse( Datagram d ) {
		Logger.info( "Executing email command ["+d.getData()+"], origin: " + d.getOriginID() );
		emailResponse( d, "Bot Reply" );
	}

	public void emailResponse(Datagram d, String header) {
		/* If there's no valid queue, can't do anything */
		if ( sendEmail.isEmpty() ) {
			Logger.info("Asked to email to " + d.getOriginID() + " but no worker defined.");
			return;
		}
		/* Notification to know if anyone uses the bot. */
		if ( (!d.getOriginID().startsWith("admin") && !emailWorker.isAddressInRef("admin",d.getOriginID()) ) && header.equalsIgnoreCase("Bot Reply")  ) {
			sendEmail.get().sendEmail( Email.toAdminAbout("DCAFSbot").content("Received '" + d.getData() + "' command from " + d.getOriginID()) );
		}
		/* Processing of the question */
		d.setData( d.getData().toLowerCase());

		/* Writable is in case the question is for realtime received data */
		String response = createResponse( d.getData(), d.getWritable(), false, true );

		if (!response.toLowerCase().contains(UNKNOWN_CMD)) {
			response = response.replace("[33m ", "");
			sendEmail.get().sendEmail( Email.to(d.getOriginID()).subject(header).content(response.replace("\r\n", "<br>")));
		} else {
			sendEmail.get().sendEmail(
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
	 * @param remember Whether or not the command should be recorded in the raw data
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
	 * @param remember Whether or not the command should be recorded in the raw data
	 * @param html     If the response should you html encoding or not
	 * @return The response to the command/question
	 */
	public String createResponse(String question, Writable wr, boolean remember, boolean html) {

		String result = UNKNOWN_CMD;

		if (!html) // if html is false, verify that the command doesn't imply the opposite
			html = question.endsWith("html");

		question = question.replace("html", "");

		if (remember) // Whether or not to store commands in the raw log (to have a full simulation when debugging)
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
		
		if( find.equals("i_c") || find.length() > 3 ) // Otherwise adding integrated circuits with their name is impossible
			find = split[0];
			
		find = find.isBlank() ? "nothing" : find;
		
		Method m = methodMapping.get(find);		

		if (m != null) {
			try {
				result = m.invoke( this, split, wr, html).toString();
			} catch (IllegalAccessException | IllegalArgumentException e) {
				Logger.warn("Invoke Failed: " + question);
				result = "Error during invoke.";
			}catch (InvocationTargetException e) {
				Throwable originalException = e.getTargetException();
				Logger.error( "'"+originalException+"' at "+originalException.getStackTrace()[0].toString()+" when processing: "+question);
				Logger.error(e);
			 }
		}
		if( m == null || result.startsWith(UNKNOWN_CMD) ){
			var cmdOpt = commandables.entrySet().stream()
						.filter( ent -> {
							String key = ent.getKey();
							if( key.equals(split[0]))
								return true;
							return Arrays.stream(key.split(";")).anyMatch(k->k.equals(split[0]));
						}).map(ent -> ent.getValue()).findFirst();

			if( cmdOpt.isPresent()) {
				result = cmdOpt.get().replyToCommand(split, wr, html);
			}else{
				String res;
				for( var cd : bulkCommandable ){
					result = cd.replyToCommand(split,wr,html);
					if( !result.startsWith(UNKNOWN_CMD))
						continue;
				}
				if( result.startsWith(UNKNOWN_CMD)) {
					if (split[1].equals("?") || split[1].equals("list")) {
						var nl = html ? "<br>" : "\r\n";
						res = doCmd("tm", split[0] + ",sets", wr) + nl + doCmd("tm", split[0] + ",tasks", wr);

					} else {
						res = doCmd("tm", "run," + split[0] + ":" + split[1], wr);
					}
					if (!res.startsWith("No ") && !res.startsWith("Not "))
						result = res;
				}
			}
		}
		if( result.startsWith(UNKNOWN_CMD) ) {
			Logger.warn("Not defined:" + question + " because no method named " + find + ".");
		}
		if( wr!=null ) {
			if (!wr.getID().equalsIgnoreCase("telnet"))
				Logger.debug("Hidden response for " + wr.getID() + ": " + result);
		}else{
			Logger.debug("Hidden response to " + question + ": " + result);
		}
		return result + (html ? "<br>" : "\r\n");
	}

	/* *******************************************************************************************/
	/**
	 * Search the class for relevant methods.
	 */
	public void getMethodMapping() {

		Class<?> reqdata = this.getClass();

		ArrayList<Method> methods = new ArrayList<>(Arrays.asList(reqdata.getDeclaredMethods()));

		if (reqdata.getSuperclass() == CommandPool.class) { // To make sure that both the child and the parent class are
														// searched
			methods.addAll(Arrays.asList(reqdata.getSuperclass().getDeclaredMethods()));
		}
		for (Method method : methods) { // Filter the irrelevant methods out
			String com = method.getName();
			if (com.length() >= 3 && com.startsWith("do")) { // Needs to be atleast 3 characters long and start with
																// 'do'
				com = com.substring(2); // Remove the 'do'
				StringBuilder high = new StringBuilder(); // 'high' will contain the capital letters from the command to form the
									// alternative command
				for (int a = 0; a < com.length(); a++) {
					char x = com.charAt(a);
					if (Character.isUpperCase(x) || x == '_') {
						high.append(x);
					}
				}
				methodMapping.put(com.toLowerCase(), method);				
				if (high.length() != com.length()) { // if both commands aren't the same
					methodMapping.put(high.toString().toLowerCase(), method);
				}
			}
		}
		Logger.info("Found " + methodMapping.size() + " usable methods/commands.");
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
	private String doCmd( String[] req, Writable wr){
		var c = commandables.get(req[0]);
		if( c==null) {
			Logger.error("No "+req[0]+" available");
			return UNKNOWN_CMD+": No "+req[0]+" available";
		}
		return c.replyToCommand(req,wr,false);
	}
	/* ******************************************  C O M M A N D S ****************************************************/
	/**
	 * Command that creates a list of all available commands. Then execute a request
	 * with '?' from each which should return the info.
	 * 
	 * @param request The full request as received, [0]=method and [1]=command
	 * @param wr The writable of the source of the command
	 * @param html    True if the command needs to be hmtl formatted
	 * @return Response to the request
	 */
	public String doCMDS(String[] request, Writable wr, boolean html) {
		String nl = html ? "<br>" : "\r\n";
		
		StringJoiner join = new StringJoiner( nl, "List of base commands:"+nl,"");

		if( !request[1].contains("*"))
			request[1]+=".*";

		ArrayList<String> titles = new ArrayList<>();
		methodMapping.keySet().stream().filter( x -> x.matches(request[1]))
									   .forEach( titles::add );

		Collections.sort(titles); // Sort it so that the list is alphabetical

		ArrayList<String> results = new ArrayList<>();
		for (String t : titles) {
			String result;
			try {
				if (t.equals("cmds")) // ignore the cmds command otherwise endless loop
					continue;
					
				result = methodMapping.get(t).invoke(this, new String[]{t,"?"}, null, false).toString(); // Execute command with '?'

				if ( result.isBlank() || result.toLowerCase().startsWith(UNKNOWN_CMD) || result.toLowerCase().startsWith("No")) {
					results.add(t);
				} else {
					result = result.replace("<title>", t);
					if (!result.startsWith(t)) {
						results.add(t+result+nl);
					} else {
						results.add(nl+result);
					}
				}
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				Logger.error(e.getMessage()+" while trying "+t);
				join.add(t);
			}
		}
		if( request[1].startsWith("*")&&request[1].endsWith("*") ){
			request[1]=request[1].replace("*", "");
			boolean lastBlank=false; // So that there arent two empty lines in succession
			for( String l : results){
				for( String sub : l.split(nl) ){
					if( sub.isBlank() && !lastBlank ){						
						join.add(sub);
						lastBlank=true;
					 }else if( sub.contains(request[1]) ){
						join.add(sub);
						lastBlank=false;
					 }
				}
			}						
		}else{
			results.stream().forEach(join::add);
		}
		return join.toString();
	}
	/* ******************************************************************************/
	/**
	 * Calculate the checksum of the given item, for now only rawyesterday exists
	 * @param request The full command checksum:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Calculated checksum
	 */
	public String doCHECKSUM( String[] request, Writable wr, boolean html ){
		
		// Check for files with wildcard? 2019-07-24_RAW_0.log.zip
		StringBuilder b = new StringBuilder();

		switch( request[1] ){
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
				return UNKNOWN_CMD+": "+request[1];
		}
	}  
	/* ********************************************************************************************/
	/**
	 * Try to update a file received somehow (email or otherwise)
	 * Current options: dcafs,script and settings (dcafs is wip)
	 * 
	 * @param request The full command update:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doUPGRADE(String[] request, Writable wr, boolean html) {
		
		Path p=null;
		Path to=null;
		Path refr=null;

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
			case "tmscript"://fe. update:script,tmid


				var ori = doCmd("tm","getpath,"+spl[1],wr );
				if( ori.isEmpty() )
					return "No such script";

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
			default: return UNKNOWN_CMD+": "+spl[0];
		}
		return UNKNOWN_CMD+": "+spl[0];
	}
	/**
	 * Command to retrieve a setup file, can be settings.xml or a script
	 * eg. retrieve:script,scriptname.xml or retrieve:setup for the settings.xml
	 * 
	 * @param request The full command update:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doRETRIEVE(String[] request, Writable wr, boolean html) {
		
		if( sendEmail.isEmpty())
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

				sendEmail.get().sendEmail( Email.to(spl[2]).subject("Requested tm script: "+spl[1]).content("Nothing to say").attachment(p) );
				return "Tried sending "+spl[1]+" to "+spl[2];
			case "setup":
			case "settings":
				Path set = Path.of(workPath,"settings.xml");
				if( Files.notExists(set) ){
					return "No such file: "+ set;
				}
				if( spl.length!=2)
					return "Not enough arguments, expected retrieve:setup,email/ref";
				sendEmail.get().sendEmail(Email.to(spl[1]).subject("Requested file: settings.xml").content("Nothing to say").attachment(workPath+File.separator+"settings.xml") );
				return "Tried sending settings.xml to "+spl[1];
			default: return UNKNOWN_CMD+":"+spl[0];
		}
	}
	/* *******************************************************************************/
	public String doTRANS(String[] request, Writable wr, boolean html ){
		return doCmd("ts","forward,"+request[1],wr);
	}
	public String doSTOP(String[] request, Writable wr, boolean html ) {
		if( streampool.removeWritable(wr) )
			return "Removed forwarding to "+wr.getID();
		commandables.values().forEach( c -> c.removeWritable(wr) );
		return "No matches found for "+wr.getID();
	}
	public String doRAW( String[] request, Writable wr, boolean html ){
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
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
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
	public String doH_( String[] request, Writable wr, boolean html ){
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
	public String doS_( String[] request, Writable wr, boolean html ){	
		
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
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doSERIALPORTS( String[] request, Writable wr, boolean html ){
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
	 * Execute command to shutdown dcafs, can be either sd or shutdown or sd:reason
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doShutDown( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return "sd:reason -> Shutdown the program with the given reason, use force as reason to skip checks";
		String reason = request[1].isEmpty()?"Telnet requested shutdown":request[1];
		if( !request[1].equalsIgnoreCase("force")) {
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
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Content of the help.txt or 'No telnetHelp.txt found' if not found
	 */
	public String doHelp( String[] request, Writable wr, boolean html ){		
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

	public String doListThread( String[] request, Writable wr, boolean html ){	
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
	public String doREAD( String[] request, Writable wr, boolean html ){
		Datagram.build("").writable(wr).label("read:"+request[1]);
		das.getDataQueue().add( Datagram.build("").writable(wr).label("read:"+request[1]) ); //new Datagram(wr,"",1,"read:"+request[1]));
		return "Request for readable "+request[1]+" from "+wr.getID()+" issued";
	}
	public String doADMIN( String[] request, Writable wr, boolean html ){

		String[] cmd = request[1].split(",");
		switch( cmd[0] ){
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
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
					.add("admin:reboot -> Reboot the computer (linux only)")
					.add("admin:methodcall -> Get the time passed since a certain BaseWorker method was called");
				return join.toString();
			case "getlogs":
				if( sendEmail.isEmpty() )
					return "Failed to send logs to admin, no worker.";
				sendEmail.get().sendEmail( Email.toAdminAbout("Statuslog").subject("File attached (probably)")
						.attachment( Path.of(workPath,"logs","info.log") ));
				sendEmail.get().sendEmail( Email.toAdminAbout("Errorlog").subject("File attached (probably)")
						.attachment(Path.of(workPath,"logs","errors_"+TimeTools.formatUTCNow("yyMMdd")+".log" )) );
				return "Sending logs (info,errors) to admin...";
			case "gettasklog":
				if( sendEmail.isEmpty() )
					return "Failed to send logs to admin, no worker.";
				sendEmail.get().sendEmail( Email.toAdminAbout("Taskmanager.log").subject("File attached (probably)")
						.attachment( Path.of(workPath,"logs","taskmanager.log" ) ));
				return "Trying to send taskmanager log";
			case "getlastraw":
				Path it = Path.of(workPath,"raw",TimeTools.formatUTCNow("yyyy-MM"));
				try {
					var last = Files.list(it).filter( f -> !Files.isDirectory(f)).max( Comparator.comparingLong( f -> f.toFile().lastModified()));
					if( last.isPresent() ){
						sendEmail.get().sendEmail( Email.toAdminAbout("Taskmanager.log").subject("File attached (probably)").attachment( last.get() ));
						return "Tried sending "+last.get();
					}else{
						return "File not found";
					}
				} catch (IOException e) {
					e.printStackTrace();
					return "Something went wrong trying to get the file";
				}
			case "adddebugnode":
				DebugWorker.addBlank(XMLfab.withRoot(settingsPath,"dcafs","settings"));
				return "Tried to add node";
			case "sms":
				if(sendSMS.isEmpty())
					return "No SMS sending defined";
				sendSMS.get().sendSMS("admin","test");
				return "Trying to send SMS\r\n";
			case "haw":
				das.haltWorkers();
				return "\r\nStopping all worker threads.";
			case "clock": return TimeTools.formatLongUTCNow();
			case "regex":
				if( cmd.length != 3 )
					return "Invalid amount of parameters";
				return "Matches? "+cmd[1].matches(cmd[2]);
			case "methodcall":
				return das.getLabelWorker().getMethodCallAge( html?"<br>":"\r\n" );
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
					Process process;

					Logger.error("Started restart attempt at "+TimeTools.formatLongUTCNow());
					process = pb.start();
					//process.waitFor();
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
			if(request[1].equals("reload") && XMLtools.hasElementByTag(xml, "email") ){
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

	public String doREQTASKS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return ":x -> Send a list of all the taskset executions to x";

		if(  request[1].equals("") )
			return "No recipient given.";
		
		if( sendEmail.isEmpty() ){
			sendEmail.get().sendEmail( Email.to(request[1]).subject("Executed tasksets").content("Nothing to add")
					.attachment( Path.of(workPath,"logs","tasks.csv").toString() ) );
			return "Sending log of taskset execution to "+request[1]; 
		}
		return "Failed to send Taskset Execution list.";
	}

	public String doNOTHING( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Clear the datarequests";
		if( wr != null ){
			streampool.removeWritable(wr);
			commandables.values().forEach( c -> c.removeWritable(wr) );
		}
		return "Clearing all data requests\r\n";
	}	

	
	public String doSTatus( String[] request, Writable wr, boolean html ){
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

	public String doCONVert( String[] request, Writable wr, boolean html ){
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
	public String doSLEEP( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") || request[1].split(",").length!=2 ){
			return "sleep:rtc,<time> -> Let the processor sleep for some time using an rtc fe. sleep:1,5m sleep 5min based on rtc1";
		}
		String os = System.getProperty("os.name").toLowerCase();
		if( !os.startsWith("linux")){
			return "Only Linux supported for now.";
		}
		
		int seconds = 90;
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
	public String doGENericS( String[] request, Writable wr, boolean html ){

		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		String[] cmd = request[1].split(",");

		switch(cmd[0]){
			case "?":
				join.add("")
					.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
					.add("  Generics (gens) are used to take delimited data and store it as rtvals or in a database.");
				join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
					.add("  - ...");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Create a Generic"+TelnetCodes.TEXT_YELLOW)
					.add("  gens:fromtable,dbid,dbtable,gen id[,delimiter] -> Create a generic according to a table, delim is optional, def is ','")
					.add("  gens:fromdb,dbid,delimiter -> Create a generic with chosen delimiter for each table if there's no such generic yet")
					.add("  gens:addblank,id,format -> Create a blank generic with the given id and format")
					.add("      Options that are concatenated to form the format:")
					.add("       r = a real number" )
					.add("       i = an integer number")
					.add("       t = a piece of text")
					.add("       m = macro, this value can be used as part as the rtval")
					.add("       s = skip, this won't show up in the xml but will increase the index counter")
					.add("       eg. 1234,temp,19.2,hum,55 ( with 1234 = serial number")
					.add("           -> serial number,title,temperature reading,title,humidity reading")
					.add("           -> msrsi -> macro,skip,real,skip,integer");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
				join.add("  gens:? -> Show this info")
					.add("  gens:reload -> Reloads all generics")
					.add("  gens:list -> Lists all generics");

				return join.toString();
			case "reload": 
				das.loadGenerics(true);
				return das.getLabelWorker().getGenericInfo();
			case "fromtable": 
				if(cmd.length < 4 )
					return "To few parameters, gens:fromtable,dbid,table,gen id,delimiter";
				var db = dbManager.getDatabase(cmd[1]);
				if( db ==null)
					return "No such database found "+cmd[1];
				if( db.buildGenericFromTable(XMLfab.withRoot(settingsPath, "dcafs","generics"),cmd[2],cmd[3],cmd.length>4?cmd[4]:",") ){
					return "Generic written";
				}else{
					return "Failed to write to xml";
				}
			case "fromdb":
				if(cmd.length < 3 )
					return "To few parameters, gens:fromdb,dbid,delimiter";
				var dbs = dbManager.getDatabase(cmd[1]);
				if( dbs ==null)
					return "No such database found "+cmd[1];

				if( dbs.buildGenericFromTables(XMLfab.withRoot(settingsPath, "dcafs","generics"),false,cmd.length>2?cmd[2]:",") >0 ){
					return "Generic(s) written";
				}else{
					return "No generics written";
				}
			case "addblank":
				if( cmd.length < 3 )
					return "Not enough arguments, must be generics:addblank,id,format[,delimiter]";
				return Generic.addBlankToXML(XMLfab.withRoot(settingsPath, "dcafs","generics"), cmd[1], cmd[2],cmd.length==4?cmd[3]:",");
			case "list": 
				return das.getLabelWorker().getGenericInfo();
			default:
				return UNKNOWN_CMD+": "+cmd[0];
		}
	}
	public String doMYsqlDump(String[] request, Writable wr, boolean html ){
		String[] cmds = request[1].split(",");
		switch( cmds[0] ){
			case "?": 	return " myd:run,dbid,path -> Run the mysqldump process for the given database";
			case "run":
				if( cmds.length != 3 )
					return "Not enough arguments, must be mysqldump:run,dbid,path";
				Database db = dbManager.getDatabase(cmds[1]);
				if( db == null )
					return "No such database "+cmds[1];
				if( db instanceof SQLiteDB )
					return "Database is an sqlite, not mysql/mariadb";
				if( db instanceof SQLDB ){
					SQLDB sql =(SQLDB)db;
					if( sql.isMySQL() ){
						// do the dump
						String os = System.getProperty("os.name").toLowerCase();
						if( !os.startsWith("linux")){
							return "Only Linux supported for now.";
						}
						try {
							ProcessBuilder pb = new ProcessBuilder("bash","-c", "mysqldump "+sql.getTitle()+" > "+cmds[2]+";");
							pb.inheritIO();
							Process process;
				
							Logger.info("Started dump attempt at "+TimeTools.formatLongUTCNow());
							process = pb.start();
							process.waitFor();
							// zip it?
							if( Files.exists(Path.of(workPath,cmds[2]))){
								if(FileTools.zipFile(Path.of(workPath,cmds[2]))==null) {
									Logger.error("Dump of "+cmds[1]+" created, but zip failed");
									return "Dump created, failed zipping.";
								}
								// Delete the original file
								Files.deleteIfExists(Path.of(workPath,cmds[2]));
							}else{
								Logger.error("Dump of "+cmds[1]+" failed.");
								return "No file created...";
							}
							Logger.info("Dump of "+cmds[1]+" created, zip made.");
							return "Dump finished and zipped at "+TimeTools.formatLongUTCNow();
						} catch (IOException | InterruptedException e) {
							Logger.error(e);
							Logger.error("Dump of "+cmds[1]+" failed.");
							return "Something went wrong";
						}
					}else{
						return "Database isn't mysql/mariadb";
					}
				}else{
					return "Database isn't regular SQLDB";
				}
			default:
				return UNKNOWN_CMD+": "+request[0]+":"+request[1];
		}
	}
	public String doDataBaseManager( String[] request, Writable wr, boolean html ){
		String[] cmds = request[1].split(",");
		
		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		Database db=null;

		String id = cmds.length>=2?cmds[1]:"";
		String dbName = cmds.length>=3?cmds[2]:"";
		String address = cmds.length>=4?cmds[3]:"";
		String user = cmds.length>=5?cmds[4]:"";
		String pass="";

		if( user.contains(":")){
			pass = user.substring(user.indexOf(":")+1);
			user = user.substring(0,user.indexOf(":"));
		}

		switch( cmds[0] ){
			case "?":
				join.add(TelnetCodes.TEXT_MAGENTA+"The databasemanager connects to databases, handles queries and fetches table information");
				join.add(TelnetCodes.TEXT_GREEN+"Glossary"+TelnetCodes.TEXT_YELLOW)
						.add("  alias -> the alias of a column is the reference to use instead of the column name to find the rtval, empty is not used")
						.add("  macro -> an at runtime determined value that can be used to define the rtval reference").add("");
				join.add(TelnetCodes.TEXT_GREEN+"Connect to a database"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addmssql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
						.add("  dbm:addmysql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
						.add("  dbm:addmariadb,id,db name,ip:port,user:pass -> Adds a MariaDB server on given ip:port with user:pass")
						.add("  dbm:addsqlite,id(,filename) -> Creates an empty sqlite database, filename and extension optional default db/id.sqlite")
						.add("  dbm:addinfluxdb,id,db name,ip:port,user:pass -> Adds a Influxdb server on given ip:port with user:pass")
					.add("").add(TelnetCodes.TEXT_GREEN+"Working with tables"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addtable,id,tablename,format (format eg. tirc timestamp(auto filled system time),int,real,char/text)")
						.add("  dbm:tablexml,id,tablename -> Write the table in memory to the xml file, use * as tablename for all")
						.add("  dbm:tables,id -> Get info about the given id (tables etc)")
						.add("  dbm:fetch,id -> Read the tables from the database directly, not overwriting stored ones.")
						.add("  dbm:store,dbId,tableid -> Trigger a insert for the database and table given")
					.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addserver,id -> Adds a blank database server node to xml")
						.add("  dbm:addrollover,id,count,unit,pattern -> Add rollover to a SQLite database")
						.add("  dbm:alter,id,param:value -> Alter things like idle, flush and batch (still todo)")
						.add("  dbm:reload,id -> (Re)loads the database with the given id fe. after changing the xml")
						.add("  dbm:status -> Show the status of all managed database connections")
						.add("  st -> Show the current status of the databases (among other things)");
				return join.toString();	
			case "reload": 
				if( cmds.length<2)
					return "No id given";
				var dbr = dbManager.reloadDatabase(cmds[1]);
				if( dbr!=null){
					String error = dbr.getLastError();
					return error.isEmpty()?"Database reloaded":error;
				}
				return "No such database found";
			case "addserver":
					DatabaseManager.addBlankServerToXML( XMLfab.withRoot(settingsPath, "settings","databases"), "mysql", cmds.length>=2?cmds[1]:"" );
					return "Added blank database server node to the settings.xml";
			case "addmysql":
				var mysql = SQLDB.asMYSQL(address,dbName,user,pass);
				mysql.setID(id);
				if( mysql.connect(false) ){
					mysql.getCurrentTables(false);
					mysql.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
					dbManager.addSQLDB(id,mysql);
					return "Connected to MYSQL database and stored in xml as id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addmssql":
				var mssql = SQLDB.asMSSQL(address,dbName,user,pass);
				mssql.setID(id);
				if( mssql.connect(false) ){
					mssql.getCurrentTables(false);
					mssql.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
					dbManager.addSQLDB(id,mssql);
					return "Connected to MYSQL database and stored in xml as id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addmariadb":
				if( cmds.length<5)
					return "Not enough arguments: dbm:addmariadb,id,db name,ip:port,user:pass";
				var mariadb = SQLDB.asMARIADB(address,dbName,user,pass);
				mariadb.setID(id);
				if( mariadb.connect(false) ){
					mariadb.getCurrentTables(false);
					mariadb.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
					dbManager.addSQLDB(id,mariadb);
					return "Connected to MariaDB database and stored in xml with id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addpostgresql":
				if( cmds.length<5)
					return "Not enough arguments: dbm:addpostgresql,id,db name,ip:port,user:pass";
				var postgres = SQLDB.asPOSTGRESQL(address,dbName,user,pass);
				postgres.setID(id);
				if( postgres.connect(false) ){
					postgres.getCurrentTables(false);
					postgres.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
					dbManager.addSQLDB(id,postgres);
					return "Connected to PostgreSQL database and stored in xml with id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addsqlite":
				if( !dbName.contains(File.separator))
					dbName = "db"+File.separator+(dbName.isEmpty()?id:dbName);
					if(!dbName.endsWith(".sqlite"))
						dbName+=".sqlite";

				var sqlite = SQLiteDB.createDB(id,Path.of(dbName).isAbsolute()?"":workPath,Path.of(dbName));
				if( sqlite.connect(false) ){
					dbManager.addSQLiteDB(id,sqlite);
					sqlite.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases") );
					return "Created SQLite at "+dbName+" and wrote to settings.xml";
				}else{
					return "Failed to create SQLite";
				}
			case "tablexml":
				if( cmds.length<3)
					return "Not enough arguments: dbm:tablexml,dbid,tablename";
				var dbOpt = dbManager.getDatabase(cmds[1]);
				if( dbOpt == null)
					return "No such database "+cmds[1];
				// Select the correct server node
				var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","databases");
				if( fab.selectParent("server","id",cmds[1]).isEmpty())
					fab.selectParent("sqlite","id",cmds[1]);
				if( fab.hasChild("table","name",cmds[2]))
					return "Already present in xml, not adding";

				if( dbOpt instanceof SQLDB){
					int rs= ((SQLDB) dbOpt).writeTableToXml(fab,cmds[2]);
					return rs==0?"None added":"Added "+rs+" tables to xml";
				}else{
					return "Not a valid database target (it's an influx?)";
				}

			case "addrollover":
				if( cmds.length < 5 )
					return "Not enough arguments, needs to be dbm:addrollover,dbId,count,unit,pattern";
				var s= dbManager.getSQLiteDB(cmds[1]);
				if( s == null)
					return cmds[1] +" is not an SQLite";
				s.setRollOver(cmds[4],NumberUtils.createInteger(cmds[2]),cmds[3]);
				s.writeToXml(XMLfab.withRoot(settingsPath,"dcafs","settings","databases"));
				s.forceRollover();
				return "Rollover added";
			case "addinfluxdb": case "addinflux":
				var influx = new InfluxDB(address,dbName,user,pass);
				if( influx.connect(false)){
					dbManager.addInfluxDB(id,influx);
					influx.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","settings","databases") );
					return "Connected to InfluxDB and stored it in xml with id "+id;
				}else{
					return "Failed to connect to InfluxDB";
				}
			case "addtable":
				if( cmds.length < 4 )
					return "Not enough arguments, needs to be dbm:addtable,dbId,tableName,format";
				if( DatabaseManager.addBlankTableToXML( XMLfab.withRoot(settingsPath,"dcafs","settings","databases"), cmds[1], cmds[2], cmds[3] ) )
					return "Added a partially setup table to "+cmds[1]+" in the settings.xml, edit it to set column names etc";
				return "No such database found or influxDB.";
			case "fetch": 
				if( cmds.length < 2 )
					return "Not enough arguments, needs to be dbm:fetch,dbId";
				db = dbManager.getDatabase(cmds[1]);
				if( db==null)
					return "No such database";
				if( db.getCurrentTables(false) )
					return "Tables fetched, run dbm:tables,"+cmds[1]+ " to see result.";
				if( db.isValid(1) )
					return "Failed to get tables, but connection valid...";
				return "Failed to get tables because connection not active.";
			case "tables":
				if( cmds.length < 2 )
					return "Not enough arguments, needs to be dbm:tables,dbId";
				db = dbManager.getDatabase(cmds[1]);
				if( db==null)
					return "No such database";
				return db.getTableInfo(html?"<br":"\r\n");
			case "alter":
				return "Not yet implemented";
			case "status": case "list":
				return dbManager.getStatus();
			case "store":
				if( cmds.length < 3 )
					return "Not enough arguments, needs to be dbm:store,dbId,tableid";
				if( dbManager.buildInsert(cmds[1],cmds[2],dataProvider,"") )
					return "Wrote record";
				return "Failed to write record";
			default:
				return UNKNOWN_CMD+": "+request[0]+":"+request[1];
		}
	}
	public String doFileCollector( String[] request, Writable wr, boolean html ) {
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
				return das.getFileCollectorsList(", ");
			case "addrollover":
				if( cmds.length<6)
					return "Not enough arguments given: fc:addrollover,id,count,unit,format,zip?";

				fco = das.getFileCollector(cmds[1]);
				if( fco.isEmpty() )
					return "No such fc: "+cmds[1];

				if( fco.get().setRollOver(cmds[4],NumberUtils.toInt(cmds[2]),TimeTools.convertToRolloverUnit(cmds[3]),Tools.parseBool(cmds[5],false)) ) {
					XMLfab.withRoot(settingsPath, "dcafs", "collectors")
							.selectOrCreateParent("file", "id", cmds[1])
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
						.selectOrCreateParent("file", "id", cmds[1])
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
							.selectOrCreateParent("file", "id", cmds[1])
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
						.selectOrCreateParent("file", "id", cmds[1])
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
										.selectOrCreateParent("file", "id", cmds[1]);

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
				if( cmds.length<3)
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
