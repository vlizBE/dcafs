package com.email;

import com.stream.Writable;
import com.stream.collector.BufferCollector;
import com.stream.collector.CollectorFuture;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.DeadThreadListener;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import javax.activation.*;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.search.FlagTerm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class EmailWorker implements CollectorFuture, EmailSending {

	static double megaByte = 1024.0 * 1024.0;

	ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	// Email sending settings
	double doZipFromSizeMB = 0.5; // From which attachment size the attachment should be zipped (in MB)
	double maxSizeMB = 1; // Maximum size of attachments to send (in MB)

	/* Queues that hold new emails and retries */
	private final ArrayList<Email> retryQueue = new ArrayList<>(); // Queue holding emails to be
																		// send/retried

	private final Map<String, String> emailBook = new HashMap<>(); // Hashmap containing the reference to email address
																	// 'translation'
	private final ArrayList<Permit> permits = new ArrayList<>();
	/* Outbox */
	MailBox outbox = new MailBox();

	boolean outboxAuth = false; // Whether or not to authenticate

	/* Inbox */
	MailBox inbox = new MailBox();
	Session inboxSession;

	int errorCount = 0; // Amount of errors encountered
	boolean sendEmails = true; // True if sending emails is allowed

	/* Standard SMTP properties */
	Properties props = System.getProperties(); // Properties of the connection
	static final String MAIL_SMTP_CONNECTIONTIMEOUT = "mail.smtp.connectiontimeout";
	static final String MAIL_SOCKET_TIMEOUT = "60000";
	static final String MAIL_SMTP_TIMEOUT = "mail.smtp.timeout";
	static final String MAIL_SMTP_WRITETIMEOUT = "mail.smtp.writetimeout";


	boolean busy = false; // Indicate that an email is being send to the server
	java.util.concurrent.ScheduledFuture<?> retryFuture; // Future of the retry checking thread

	private final BlockingQueue<Datagram> dQueue; // Used to pass commands received via email to the dataworker for
														// processing

	/* Reading emails */
	java.util.concurrent.ScheduledFuture<?> checker; // Future of the inbox checking thread

	int maxQuickChecks = 0; // Counter for counting down how many quick checks for incoming emails are done
							// before slowing down again
	int checkIntervalSeconds = 300; // Email check interval, every 5 minutes (default) the inbox is checked for new
									// emails
	int maxEmailAgeInHours=-1;
	String allowedDomain = ""; // From which domain are emails accepted

	Path xml; // Link to the setting xml
	boolean deleteReceivedZip = true;

	Session mailSession = null;

	long lastInboxConnect = -1; // Keep track of the last timestamp that a inbox connection was made

	protected DeadThreadListener listener;	// a way to notify anything that the thread died somehow

	HashMap<String, DataRequest> buffered = new HashMap<>();	// Store the requests made for data via email


	static final String XML_PARENT_TAG = "email";
	static final String TIMEOUT_MILLIS="10000";

	/**
	 * Constructor for this class
	 * 
	 * @param xml       the xml document containing the settings
	 * @param dQueue the queue processed by a @see BaseWorker
	 */
	public EmailWorker(Document xml, BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
		this.readFromXML(xml);
		init();
	}

	/**
	 * Add a listener to be notified of the event the thread fails.
	 * 
	 * @param listener the listener
	 */
	public void setEventListener(DeadThreadListener listener) {
		this.listener = listener;
	}

	/**
	 * Initialises the worker by enabling the retry task and setting the defaults
	 * settings, if reading emails is enabled this starts the thread for this.
	 */
	public void init() {
		setOutboxProps();

		if (dQueue != null) { // No need to check if nothing can be done with it
			if (!inbox.server.isBlank() && !inbox.user.equals("user@email.com")) {
				// Check the inbox every x minutes
				checker = scheduler.scheduleAtFixedRate(new Check(), checkIntervalSeconds,checkIntervalSeconds, TimeUnit.SECONDS);

			}
			inboxSession = Session.getDefaultInstance(new Properties());
			alterInboxProps(inboxSession.getProperties());
		}
		MailcapCommandMap mc = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
		mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
		mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml");
		mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
		mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
		mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
		CommandMap.setDefaultCommandMap(mc);	
	}

	/**
	 * Request the queue in which emails are place for sending
	 * 
	 * @return The BlockingQueue to hold the emails to send
	 */
	public EmailSending getSender(){
		return this;
	}
	/**
	 * Get the amount of emails in the retry queue, meaning those that need to be send again.
	 * @return Amount of backlog emails
	 */
	public int getRetryQueueSize(){
		return retryQueue.size();
	}
	/**
	 * Checks whether or not EmailWorker info can be found in the settings file.
	 * 
	 * @param xml The XML document maybe containing the settings
	 * @return True if settings are present
	 */
	public static boolean inXML( Document xml){
		return XMLtools.hasElementByTag( xml, XML_PARENT_TAG);	
	}
	/**
	 * Read the settings from the XML file and apply them
	 * 
	 * @param xmlDoc The XML document containing the settings
	 */
	public void readFromXML(Document xmlDoc ){

		xml = XMLtools.getDocPath(xmlDoc);

		Element email = XMLtools.getFirstElementByTag( xmlDoc, XML_PARENT_TAG);	// Get the element containing the settings and references

		// Sending
		Element outboxElement = XMLtools.getFirstChildByTag(email, "outbox");
		if( outboxElement != null ){
			Element server = XMLtools.getFirstChildByTag(outboxElement, "server");
			if( server == null){
				Logger.error("No server defined for the outbox");
			}else{
				outbox.setServer( server.getTextContent(), XMLtools.getIntAttribute(server,"port",25) );			// The SMTP server
				outbox.setLogin( XMLtools.getStringAttribute(server, "user", ""), XMLtools.getStringAttribute( server, "pass", "" ));
				outbox.hasSSL = XMLtools.getBooleanAttribute( server, "ssl",  false);
				outbox.from = XMLtools.getChildValueByTag( outboxElement, "from", "das@email.com" );	// From emailaddress

				doZipFromSizeMB = XMLtools.getChildDoubleValueByTag( outboxElement, "zip_from_size_mb", 10);		// Max unzipped filesize
				deleteReceivedZip = XMLtools.getChildBooleanValueByTag( outboxElement, "delete_rec_zip", true);	// Delete received zip files after unzipping
				maxSizeMB = XMLtools.getChildDoubleValueByTag( outboxElement, "max_size_mb", 15.0);				// Max zipped filesize to send
			}
		}
		
		Element inboxElement = XMLtools.getFirstChildByTag(email, "inbox");
		if( inboxElement != null ){
			Element server = XMLtools.getFirstChildByTag(inboxElement, "server");
			if( server == null){
				Logger.error("No server defined for the inbox");
			}else {
				inbox.setServer( server.getTextContent(), XMLtools.getIntAttribute(server,"port",993) );
				inbox.setLogin( XMLtools.getStringAttribute(server, "user", ""), XMLtools.getStringAttribute(server, "pass", ""));
				inbox.hasSSL = XMLtools.getBooleanAttribute( server, "ssl",  false);

				String interval = XMLtools.getChildValueByTag(email, "checkinterval", "5m");    // Interval to check for new emails (in minutes)
				checkIntervalSeconds = TimeTools.parsePeriodStringToSeconds(interval);
				allowedDomain = XMLtools.getChildValueByTag(email, "allowed", "");
			}
		}			
		
		/*
		*  Now figure out the various references used, linking keywords to e-mail addresses
		**/
		readEmailBook(email);
		readPermits(email);
	}

	/**
	 * Reads the content of the xml element containing the id -> email references
	 * @param email The element containing the email info
	 */
	private void readEmailBook( Element email ){
		emailBook.clear();  // Clear previous references
		Element book = XMLtools.getFirstChildByTag(email, "book");
		for( Element entry : XMLtools.getChildElements( book, "entry" ) ){
			String addresses = entry.getTextContent();
			String ref = XMLtools.getStringAttribute(entry, "ref", "");
			if( !ref.isBlank() && !addresses.isBlank() ){
				addTo(ref, addresses);
			}else{
				Logger.warn("email book entry has empty ref or address");
			}			
		}
	}
	private void readPermits( Element email){
		permits.clear(); // clear previous permits
		Element permit = XMLtools.getFirstChildByTag(email,"permits");
		for( var perm : XMLtools.getChildElements(permit)){
			boolean denies = perm.getTagName().equals("deny");
			String ref = XMLtools.getStringAttribute(perm,"ref","");
			if(ref.isEmpty()){
				Logger.warn("Empty permit ref!");
				continue;
			}
			String val = perm.getTextContent();
			if( val.isEmpty() ){
				Logger.warn("Empty permit value!");
				continue;
			}

			boolean regex = XMLtools.getBooleanAttribute(perm,"regex",false);
			this.permits.add(new Permit(denies, ref, val, regex));
		}
	}
	/**
	 * This creates the barebones settings in the xml
	 * 
	 * @param fab An XMLfab to build upon
	 * @param sendEmails Whether or not to include sending emails
	 * @param receiveEmails Whether or not to include checking for emails
	 * @return True if changes were written to the xml
	 */
	public static boolean addBlankEmailToXML( XMLfab fab, boolean sendEmails, boolean receiveEmails ){
		
		if( fab.getChild("email").isPresent() ) // Don't overwrite if already exists?
			return false;

		fab.digRoot(XML_PARENT_TAG);

		if( sendEmails ){
			fab.addParent("outbox","Settings related to sending")
					 .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","993")
					  .addChild("from","das@email.com")
					  .addChild("zip_from_size_mb","3")
					  .addChild("delete_rec_zip","yes")
					  .addChild("max_size_mb","10");
		}
		if( receiveEmails ){	
			fab.addParent("inbox","Settings for receiving emails")
					   .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","465")
					   .addChild("checkinterval","5m");
		}
		fab.addParent("book","Add entries to the emailbook below")
				.addChild("entry","admin@email.com").attr("ref","admin");

		fab.build();
		return true;
	}
	/**
	 * Alter the settings.xml based on the current settings
	 *
	 * @return True if no errors occurred
	 */
	public boolean writeToXML( ){

		XMLfab fab = XMLfab.withRoot(xml,"dcafs","settings");
		fab.digRoot("email");
		if( outbox != null ) {
			fab.selectOrCreateParent("outbox")
					.alterChild("server", outbox.server)
						.attr("user",outbox.user)
						.attr("pass",outbox.pass)
						.attr("ssl", outbox.hasSSL?"yes":"no")
						.attr("port", outbox.port)
					.alterChild("from",outbox.from )
					.alterChild("zip_from_size_mb", ""+doZipFromSizeMB)
					.alterChild("delete_rec_zip", deleteReceivedZip?"yes":"no")
					.alterChild("max_size_mb", ""+maxSizeMB);
		}
		if( inbox != null ){
			fab.selectOrCreateParent("inbox")
					.alterChild("server", inbox.server)
						.attr("user",inbox.user)
						.attr("pass",inbox.pass)
						.attr("ssl",inbox.hasSSL?"yes":"no")
						.attr("port",inbox.port)
					.alterChild("checkinterval",TimeTools.convertPeriodtoString(checkIntervalSeconds,TimeUnit.SECONDS));
		}
		if ( fab.build()!=null ){
			return writePermits();
		}
		return false;
	}
	private boolean writePermits(){
		if( xml != null ){
			var fab = XMLfab.withRoot(xml,"dcafs","settings","email");
			fab.selectOrCreateParent("permits");
			fab.clearChildren();
			for( var permit : permits ){
				fab.addChild(permit.denies?"deny":"allow",permit.value).attr("ref",permit.ref);
				if(permit.regex)
					fab.attr("regex","yes");
			}
			return fab.build()!=null;
		}else{
			Logger.error("Tried to write permits but no valid xml yet");
			return false;
		}
	}
	/**
	 * Add an email address to an id
	 * 
	 * @param id The id to add address to
	 * @param email The email address to add to the id
	 */
	public void addTo(String id, String email){
		String old = emailBook.get(id);				// Get any emailadresses already linked with the id, if any
		email = email.replace(";", ",");		// Multiple emailaddresses should be separated with a colon, so alter any semi-colons
		if( old != null && !old.isBlank()){	// If an emailadres was already linked, add the new one(s) to it
			emailBook.put(id, old+","+email);
		}else{									// If not, put the new one
			emailBook.put(id, email);
		}		
		Logger.info( "Set "+id+" to "+ emailBook.get(id)); // Add this addition to the status log
	}
	/**
	 * Request the content of the 'emailbook'
	 * @return Listing of all the emails and references in the emailbook
	 */
	public String getEmailBook( ){
		StringJoiner b = new StringJoiner( "\r\n", "-Emailbook-\r\n", "");		
		for( Map.Entry<String,String> ele : emailBook.entrySet()){
			b.add(ele.getKey()+" -> "+ele.getValue() );
		}
		return b.toString();
	}
	/**
	 * Gets the settings used by the EmailWorker
	 * 
	 * @return Listing of all the current settings as a String
	 */
	public String getSettings(){
		StringJoiner b = new StringJoiner( "\r\n","--Email settings--\r\n","\r\n");
		b.add("-Sending-");
		b.add("Server: "+outbox.server+":"+outbox.port);
		b.add("SSL: "+outbox.hasSSL);
		b.add("From (send replies): "+outbox.from);
		b.add("Attachments zip size:"+doZipFromSizeMB);
		b.add("Maximum attachment size:"+maxSizeMB);
	
		b.add("").add("-Receiving-");
		b.add("Inbox: "+inbox.server+":"+inbox.port);
		if( checker == null || checker.isCancelled() || checker.isDone()){
			b.add( "Next inbox check in: Never,checker stopped");
		}else{
			b.add("Inbox check in: "
					+TimeTools.convertPeriodtoString(checker.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS)
					+"/"
					+TimeTools.convertPeriodtoString(checkIntervalSeconds, TimeUnit.SECONDS)
					+" Last ok: "+getTimeSincelastInboxConnection());
		}
		
		b.add("User: "+inbox.user);
		b.add("SSL: "+inbox.hasSSL);
		b.add("Allowed: " + allowedDomain );
		return b.toString();
	}
	public String getTimeSincelastInboxConnection(){
		if( lastInboxConnect == -1 )
			return "never";
		return TimeTools.convertPeriodtoString( Instant.now().toEpochMilli() - lastInboxConnect, TimeUnit.MILLISECONDS)+" ago";	
	}
	/**
	 * Set the properties for sending emails
	 */
	private void setOutboxProps(){
		if( !outbox.server.equals("")){
			props.setProperty("mail.host", outbox.server);
			props.setProperty("mail.transport.protocol", "smtp");

			props.put("mail.smtp.port", outbox.port );

			if( outbox.hasSSL ){
				props.put("mail.smtp.ssl.enable", "true");				
			}
			
			if( !outbox.pass.isBlank() || !outbox.user.isBlank() ){
				props.put("mail.smtp.auth", "true");
				props.put("mail.smtp.user", outbox.user);
				outboxAuth = true;
			}

			// Set a fixed timeout of 60s for all operations the default timeout is "infinite"
			props.put(MAIL_SMTP_CONNECTIONTIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_TIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_WRITETIMEOUT, MAIL_SOCKET_TIMEOUT);
		}
	}
	/**
	 * Checks the emailbook to see to which reference the emailadres is linked
	 * 
	 * @param email The email address to look for
	 * @return Semi-colon (;) separated string with the found refs
	 */
	public String getEmailRefs( String email ){
		StringJoiner b = new StringJoiner( ";");

		emailBook.entrySet().stream().filter(set -> set.getValue().contains(email)) // only retain the refs that have the email
							  .forEach( set -> b.add(set.getKey()));
		
		return b.toString();
	}
	/**
	 * Checks if a certain email address is part of the list associated with a certain ref
	 * @param ref The reference to check
	 * @param address The emailaddress to look for
	 * @return True if found
	 */
	public boolean isAddressInRef( String ref, String address ){
		String list = emailBook.get(ref);
		return list.contains(address);
	}
	/**
	 * Method to have the worker act on commands/requests
	 * 
	 * @param req The command/request to execute
	 * @param html Whether or not the response should be html formatted
	 * @return The response to the command/request
	 */
	public String replyToSingleRequest( String req, boolean html ){

        String[] parts = req.split(",");
        
        switch(parts[0]){
			case "?":
				StringJoiner b = new StringJoiner(html?"<br>":"\r\n");
				b.add("email:reload -> Reload the settings found in te XML.");
				b.add("email:refs -> Get a list of refs and emailadresses.");
				b.add("email:send,to,subject,content -> Send an email using to with subject and content");
				b.add("email:setup -> Get a listing of all the settings.");
				b.add("email:checknow -> Checks the inbox for new emails");
				b.add("email:addallow,from,cmd(,isRegex) -> Adds permit allow node, default no regex");
				b.add("email:adddeny,from,cmd(,isRegex) -> Adds permit deny node, default no regex");
				b.add("email:interval,x -> Change the inbox check interval to x");
				return b.toString();
			case "reload": 
				if( xml == null )
					return "No xml defined yet...";
				readFromXML(XMLtools.readXML(xml));
				return "Settings reloaded";
			case "refs": return this.getEmailBook();
			case "setup":case "status": return this.getSettings();
			case "send":
				if( parts.length !=4 )
					return "Not enough arguments send,ref/email,subject,content";
				sendEmail( Email.to(parts[1]).subject(parts[2]).content(parts[3]) );
				return "Tried to send email";
			case "checknow":
				checker.cancel(false);
				checker = scheduler.schedule( new Check(), 1, TimeUnit.SECONDS);
				return "Will check emails asap.";
			case "interval":
				if( parts.length==2){
					this.checkIntervalSeconds = TimeTools.parsePeriodStringToSeconds(parts[1]);
					return "Interval changed to "+this.checkIntervalSeconds+" seconds (todo:save to settings.xml)";
				}else{
					return "Invalid number of parameters";
				}
			case "addallow":case "adddeny":
				if( parts.length <3 ){
					return "Not enough arguments email:"+parts[0]+",from,cmd(,isRegex)";
				}
				boolean regex = parts.length == 4 && Tools.parseBool(parts[3], false);
				permits.add(new Permit(parts[0].equals("adddeny"), parts[1], parts[2], regex));
				return writePermits()?"Permit added":"Failed to write to xml";
			default	:
				return "unknown command";
		}
	}
	/**
	 * Send an email
	 * @param email The email to send
	 */
	public void sendEmail( Email email ){
		if( !sendEmails ){
			Logger.warn("Sending emails disabled!");
			return;
		}
		if( email.isValid()) {
			scheduler.execute(new Sender(email, false));
		}else{
			Logger.error("Tried to send an invalid email");
		}
	}
	public void setSending( boolean send ){
		sendEmails=send;
	}
	/* *********************************  W O R K E R S ******************************************************* */
	/**
	 * Main worker thread that sends emails
	 */
	private class Sender implements Runnable {
		Email email;
		boolean retry;

		public Sender(Email email, boolean retry) {
			this.email = email;
			this.retry = retry;
		}
		@Override
		public void run() {

			try {
				String attach = "";

				if (mailSession == null) {
					if (outboxAuth) {
						mailSession = Session.getInstance(props, new javax.mail.Authenticator() {
							@Override
							protected PasswordAuthentication getPasswordAuthentication() {
								return new PasswordAuthentication(outbox.user, outbox.pass);
							}
						});
					} else {
						mailSession = javax.mail.Session.getInstance(props, null);
					}
					//mailSession.setDebug(true); 	// No need for extra feedback
				}

				Message message = new MimeMessage(mailSession);

				String subject = email.subject;
				if (email.subject.endsWith(" at.")) { //macro to get the local time added
					subject = email.subject.replace(" at.", " at " + TimeTools.formatNow("HH:mm") + ".");
				}
				message.setSubject(subject);
				if (!email.hasAttachment()) { // If there's no attachment, this changes the content type
					message.setContent(email.content, "text/html");
				} else {
					int a = email.attachment.indexOf("[");
					// If a [ ... ] is present this means that a datetime format is enclosed and then [...] will be replaced
					// with format replaced with actual current datetime
					// eg [HH:mm] -> 16:00
					if (a != -1) {
						int b = email.attachment.indexOf("]");
						String dt = email.attachment.substring(a + 1, b);
						dt = TimeTools.formatUTCNow(dt); //replace the format with datetime
						attach = email.attachment.substring(0, a) + dt + email.attachment.substring(b + 1); // replace it in the attachment
						Logger.info("Changed " + email.attachment + " to " + attach);
					} else { // Nothing special happens
						attach = email.attachment;
					}

					try {
						Path path = Path.of(attach);
						if (Files.notExists(path)) { // If the attachment doesn't exist
							email.attachment = "";
							message.setContent(email.content, "text/html");
							message.setSubject(subject + " [attachment not found!]"); // Notify the receiver that is should have had an attachment
						} else if (Files.size(path) > doZipFromSizeMB * megaByte) { // If the attachment is larger than the zip limit
							FileTools.zipFile(path); // zip it
							attach += ".zip"; // rename attachment
							Logger.info("File zipped because of size larger than " + doZipFromSizeMB + "MB. Zipped size:" + Files.size(path) / megaByte + "MB");
							path = Path.of(attach);// Changed the file to archive, zo replace file
							if (Files.size(path) > maxSizeMB * megaByte) { // If the zip file it to large to send, maybe figure out way to split?
								email.attachment = "";
								message.setContent(email.content, "text/html");
								message.setSubject(subject + " [ATTACHMENT REMOVED because size constraint!]");
								Logger.info("Removed attachment because to big (>" + maxSizeMB + "MB)");
							}
						}
					} catch (IOException e) {
						Logger.error(e);
					}
				}
				String from = email.from.isEmpty()?(outbox.getFromStart()+"<" + outbox.from + ">"):email.from;
				message.setFrom(new InternetAddress(  from ));
				for (String single : email.toRaw.split(",")) {
					try {
						message.addRecipient(Message.RecipientType.TO, new InternetAddress(single.split("\\|")[0]));
					} catch (AddressException e) {
						Logger.warn("Issue trying to convert: " + single.split("\\|")[0] + "\t" + e.getMessage());
						Logger.error(e.getMessage());
					}
				}
				//Add attachment
				if (email.hasAttachment()) {
					// Create the message part
					BodyPart messageBodyPart = new MimeBodyPart();

					// Fill the message
					messageBodyPart.setContent(email.content, "text/html");

					// Create a multipar message
					Multipart multipart = new MimeMultipart();

					// Set text message part
					multipart.addBodyPart(messageBodyPart);

					// Part two is attachment
					messageBodyPart = new MimeBodyPart();
					DataSource source = new FileDataSource(attach);
					messageBodyPart.setDataHandler(new DataHandler(source));
					messageBodyPart.setFileName(Path.of(attach).getFileName().toString());
					multipart.addBodyPart(messageBodyPart);

					message.setContent(multipart);
				}
				// Send the complete message parts
				Logger.debug("Trying to send email to " + email.toRaw + " through " + outbox.server + "!");
				busy = true;
				Transport.send(message);
				busy = false;

				if (attach.endsWith(".zip")) { // If a zip was made, remove it afterwards
					try {
						Files.deleteIfExists(Path.of(attach));
					} catch (IOException e) {
						Logger.error(e);
					}
				}

				if (email.deleteOnSend()) {
					try {
						Files.deleteIfExists(Path.of(email.attachment));
					} catch (IOException e) {
						Logger.error(e);
					}
				}

				errorCount = 0;
				if( !retryQueue.isEmpty() ){
					retryFuture.cancel(true); // stop the retry attempt
					//Only got one thread so we can submit before clearing without worrying about concurrency

					retryQueue.forEach( email -> {
						Logger.info("Retrying email to "+email.toRaw);
						scheduler.execute( new Sender(email,false));
					} );
					retryQueue.clear();
				}
			} catch (MessagingException ex) {
				Logger.error("Failed to send email: " + ex);
				email.addAttempt();
				if( !retry ){
					if( retryQueue.isEmpty() || retryFuture == null || retryFuture.isDone() ) {
						Logger.info("Scheduling a retry after 10 seconds");
						retryFuture = scheduler.schedule( new Sender(email,true), 10, TimeUnit.SECONDS);
					}
					Logger.info("Adding email to " + email.toRaw + " about " + email.subject + " to resend queue. Error count: " + errorCount);
					retryQueue.add(email);
				}else{
					if( email.isFresh(maxEmailAgeInHours) ) { // If the email is younger than the preset age
						Logger.info("Scheduling a successive retry after " + TimeTools.convertPeriodtoString(Math.min(30 * email.getAttempts(), 300), TimeUnit.SECONDS) + " with "
								+ retryQueue.size() + " emails in retry queue");

						retryFuture = scheduler.schedule(new Sender(email, true), Math.min(30 * email.getAttempts(), 300), TimeUnit.SECONDS);
					}else{// If the email is older than the preset age
						retryQueue.removeIf(email -> !email.isFresh(maxEmailAgeInHours)); // Remove all old emails
						if( !retryQueue.isEmpty()){ // Check if any emails are left, and if so send the first one
							retryFuture = scheduler.schedule(new Sender(retryQueue.get(0), true), 300, TimeUnit.SECONDS);
						}
					}
				}
				errorCount++;
			}

		}
	}
	/**
	 * Set the properties for sending emails
	 */
	private void alterInboxProps( Properties props ){
		// Set a fixed timeout of 10s for all operations the default timeout is "infinite"		
		props.put( "mail.imap.connectiontimeout", TIMEOUT_MILLIS); //10s timeout on connection
		props.put( "mail.imap.timeout", TIMEOUT_MILLIS);
		props.put( "mail.imap.writetimeout", TIMEOUT_MILLIS);
		props.put( MAIL_SMTP_TIMEOUT, TIMEOUT_MILLIS);    
		props.put( MAIL_SMTP_CONNECTIONTIMEOUT, TIMEOUT_MILLIS);
	}
	public List<String> findTo( String from ){
		if( from.startsWith(inbox.user)){
			var ar = new ArrayList<String>();
			ar.add("echo");
			return ar;
		}
		return emailBook.entrySet().stream().filter(e -> e.getValue().contains(from)).map(Map.Entry::getKey).collect(Collectors.toList());
	}

	/**
	 * Check if the email cmd from a certain sender is denied to run, admin commands are only allow for admins unless
	 * explicit permission was given
	 * @param to List of ref's the from belongs to
	 * @param from The sender
	 * @param subject The command to execute
	 * @return True if this sender hasn't got the permission
	 */
	private boolean isDenied(List<String> to, String from, String subject){
		boolean deny=false;

		if( subject.contains("admin")
				|| subject.startsWith("sd") || subject.startsWith("shutdown")
				|| subject.startsWith("sleep")
				|| subject.startsWith("update")
				|| subject.startsWith("retrieve:set")){
			if( to.contains("admin") ){ // by default allow admins to issue admin commands...
				return false;
			}
			deny=true; // change to default deny for admin commands
		}
		if( from.startsWith(inbox.user+"@") )
			return false;

		if( !permits.isEmpty() ){
			boolean match=false;
			for( Permit d : permits){
				if (d.ref.contains("@")) {
					if (d.ref.equals(from)) {
						match = d.regex ? subject.matches(d.value) : subject.equals(d.value);
					}
				} else if (to.contains(d.ref)) {
					match = d.regex ? subject.matches(d.value) : subject.equals(d.value);
				}
				if ( match ){
					return d.denies;
				}
			}
		}
		return deny;
	}
	/**
	 * Class that checks for emails at a set interval.
	 */
	public class Check implements Runnable {

		@Override
		public void run() {
			ClassLoader tcl = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(javax.mail.Session.class.getClassLoader());
			boolean ok = false;

			try( Store inboxStore = inboxSession.getStore("imaps")) {	// Store implements autoCloseable

				inboxStore.connect( inbox.server, inbox.port, inbox.user, inbox.pass );
				
			    Folder inbox = inboxStore.getFolder( "INBOX" );
			    inbox.open( Folder.READ_WRITE );				
			    // Fetch unseen messages from inbox folder
			    Message[] messages = inbox.search(
			        new FlagTerm(new Flags(Flags.Flag.SEEN), false));

			    if( messages.length >0 ){
					Logger.info("Messages found:"+messages.length);
				}else{
			    	return;
				}

			    for ( Message message : messages ) {

					boolean delete=true;
					String from = message.getFrom()[0].toString();
					from = from.substring(from.indexOf("<")+1, from.length()-1);
					String cmd = message.getSubject();

					var tos = findTo(from);
					if( tos.isEmpty()){
						sendEmail( Email.to(from).subject("My admin doesn't allow me to talk to strangers...") );
						sendEmail( Email.toAdminAbout("Got spam? ").content("From: "+from+" "+cmd) );
						Logger.warn("Received spam from: "+from);
						message.setFlag(Flags.Flag.DELETED, true); // delete spam
						continue;
					}

					if( isDenied(tos, from, cmd) ){
						sendEmail( Email.to(from).subject("Not allowed to use "+cmd).content("Try asking an admin for permission?") );
						sendEmail( Email.toAdminAbout("Permission issue?").content("From: "+from+" -> "+cmd) );
						Logger.warn(from+" tried using "+cmd+" without permission");
						message.setFlag(Flags.Flag.DELETED, true); // delete things without permission
						continue;
					}

					String to = message.getRecipients(Message.RecipientType.TO)[0].toString();
					String body = getTextFromMessage(message);

					if( cmd.contains(" for ")) { // meaning for multiple client checking this inbox
						Logger.info("Checking if meant for me... "+outbox.getFromStart()+" in "+cmd);
						if (!cmd.contains( outbox.getFromStart() )) { // the subject doesn't contain the id
							message.setFlag(Flags.Flag.SEEN, false);// rever the flag to unseen
							Logger.info("Email read but meant for another instance...");
							continue; // go to next message
						}else{
							String newSub = cmd.replaceFirst(",?"+outbox.getFromStart(),"");
							Logger.info( "Altered subject: "+newSub+"<");
							if( !newSub.endsWith("for ")){ // meaning NOT everyone read it
								message.setFlag(Flags.Flag.SEEN, false);
								Logger.info( "Not yet read by "+newSub.substring(newSub.indexOf(" for ")+5));

								Logger.info("Someone else wants this...");
								sendEmail( Email.to(to).from(from).subject(newSub).content(body) ); // make sure the other can get it
							}else{
								Logger.info( "Only one/Last one read it");
							}
						}
						cmd = cmd.substring(0,cmd.indexOf(" for")); // remove everything not related to the command
					}
					maxQuickChecks = 5;
					Logger.info("Command: " + cmd + " from: " + from );

					if ( message.getContentType()!=null && message.getContentType().contains("multipart") ) {
						try {
							Object objRef = message.getContent();
							if(objRef instanceof Multipart){

								Multipart multiPart = (Multipart) message.getContent();
								if( multiPart != null ){
									for (int i = 0; i < multiPart.getCount(); i++) {
										MimeBodyPart part = (MimeBodyPart) multiPart.getBodyPart(i);

										if ( part != null && Part.ATTACHMENT.equalsIgnoreCase( part.getDisposition() )) {
											Logger.info("Attachment found:"+part.getFileName());
											Path p = Path.of("attachments",part.getFileName());
											Files.createDirectories(p.getParent());

											part.saveFile(p.toFile());
											
											if( p.getFileName().toString().endsWith(".zip")){
												Logger.info("Attachment zipped!");
												FileTools.unzipFile( p.toString(), "attachments" );
												Logger.info("Attachment unzipped!");
												if( deleteReceivedZip )
													Files.deleteIfExists(p);
											}
										}
									}
								}
							}else{
								Logger.error("Can't work with this thing: "+message.getContentType());
							}
						} catch (Exception e) {
							Logger.error("Failed to read attachment");
							Logger.error(e);
						}
					}

					if( cmd.startsWith("label:")&&cmd.length()>7){ // email acts as data received from sensor, no clue on the use case yet
						for( String line : body.split("\r\n") ) {
							if( line.isEmpty()){
								break;
							}
							dQueue.add( Datagram.build(line).label(cmd.split(":")[1]).origin(from) );
						}
					}else{
						// Retrieve asks files to be emailed, if this command is without email append from address
						if( cmd.startsWith("retrieve:") && !cmd.contains(",")){
							cmd += ","+from;
						}
						//Datagram d = new Datagram( cmd, 1, "email");
						var d = Datagram.build(cmd).label("email").origin(from);
						if( cmd.contains(":")) { // only relevant for commands that contain :
							DataRequest req = new DataRequest(from, cmd);
							d.writable(req.getWritable());
							buffered.put(req.getID(), req);
						}
						d.origin(from);
						dQueue.add( d );
					}
					if(delete)
						message.setFlag(Flags.Flag.DELETED, true);
				}
				ok=true;
				lastInboxConnect = Instant.now().toEpochMilli();							    		
			}catch(com.sun.mail.util.MailConnectException  f ){	
				Logger.error("Couldn't connect to host: "+inbox.server);
			} catch ( MessagingException | IOException e1) {
				Logger.error(e1.getMessage());
			}catch(RuntimeException e){
				Logger.error("Runtime caught");
				Logger.error(e);
			}finally{
				Thread.currentThread().setContextClassLoader(tcl);						
			}

			if( !ok ){ // If no connection could be made schedule a sooner retry
				Logger.warn("Failed to connect to inbox, retry scheduled. (last ok: "+getTimeSincelastInboxConnection()+")");
				scheduler.schedule( new Check(), 60, TimeUnit.SECONDS);
			}else if( maxQuickChecks > 0){
				// If an email was received, schedule an earlier check. This way follow ups are responded to quicker
				maxQuickChecks--;
				Logger.info("Still got "+maxQuickChecks+" to go...");
				scheduler.schedule( new Check(), Math.min(checkIntervalSeconds/3, 30), TimeUnit.SECONDS);
			}
	   }
	}
	/**
	 * Retrieve the text content of an email message
	 * @param message The message to get content from
	 * @return The text content if any, delimited with \r\n
	 * @throws MessagingException
	 * @throws IOException
	 */
	private String getTextFromMessage(Message message) throws MessagingException, IOException {
		String result = "";
		if (message.isMimeType("text/plain")) {
			result = message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
			result = getTextFromMimeMultipart(mimeMultipart);
		}
		return result;
	}

	/**
	 * Retrieve the text from a mimemultipart
	 * @param mimeMultipart The part to get the text from
	 * @return The text found
	 * @throws MessagingException
	 * @throws IOException
	 */
	private String getTextFromMimeMultipart(
			MimeMultipart mimeMultipart)  throws MessagingException, IOException{

		StringJoiner result = new StringJoiner("\r\n");
		int count = mimeMultipart.getCount();
		for (int i = 0; i < count; i++) {
			BodyPart bodyPart = mimeMultipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.add(""+bodyPart.getContent());
				break; // without break same text appears twice in my tests
			} else if (bodyPart.isMimeType("text/html")) {
				String html = (String) bodyPart.getContent();
				result.add(html); // maybe at some point do actual parsing?
			} else if (bodyPart.getContent() instanceof MimeMultipart){
				result.add( getTextFromMimeMultipart((MimeMultipart)bodyPart.getContent()));
			}
		}
		return result.toString();
	}
	@Override
	public void collectorFinished( String id, String message, Object result) {

		DataRequest req = buffered.remove(id.split(":")[1]);
		if( req == null ){
			Logger.error("No such DataRequest: "+id);
			return;
		}

		if( req.getBuffer().isEmpty() ){
			Logger.error("Buffer returned without info");
		}else{
			Logger.info("Buffer returned with info");
			sendEmail( Email.to(req.to).subject("Buffered response to "+req.about).content(String.join("<br>", req.getBuffer())) );
		}
	}

	/**
	 * Test if the BufferCollector construction works
	 */
	public void testCollector(){

		DataRequest req = new DataRequest("admin","calc:clock");

		buffered.put(req.getID(), req);
		dQueue.add( Datagram.build("calc:clock").label("email").writable(req.getWritable()).origin("admin") );
	}
	public class DataRequest{
		BufferCollector bwr;
		String to;
		String about;

		public DataRequest( String to, String about ){
			bwr = BufferCollector.timeLimited(""+Instant.now().toEpochMilli(), "60s", scheduler);
			bwr.addListener(EmailWorker.this);
			this.to=to;
			this.about=about;
		}
		public String getID(){
			return bwr.getID();
		}
		public List<String> getBuffer(){
			return bwr.getBuffer();
		}
		public Writable getWritable(){
			return bwr.getWritable();
		}
	}
	private static class MailBox{
		String server = ""; // Server to send emails with
		int port = -1; // Port to contact the server on
		boolean hasSSL = false; // Whether or not the outbox uses ssl
		String user = ""; // User for the outbox
		String pass = ""; // Password for the outbox user
		boolean auth = false; // Whether or not to authenticate
		String from = "dcafs"; // The email address to use as from address

		public void setServer(String server, int port){
			this.port = port;
			this.server=server;
		}
		public void setLogin( String user, String pass ){
			this.user=user;
			this.pass=pass;
		}
		public String getFromStart(){
			return from.substring(0,from.indexOf("@"));
		}
	}
	private static class Permit {
		boolean denies;
		boolean regex;
		String value;
		String ref;

		public Permit(boolean denies,String ref,String value,boolean regex){
			this.denies=denies;
			this.ref=ref;
			this.value=value;
			this.regex=regex;
		}
	}
}
