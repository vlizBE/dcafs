package util.task;

import io.Writable;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.DataProviding;
import util.taskblocks.CheckBlock;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Task implements Comparable<Task>{

	String value = "";					// Value of the task, the text that something will be done with if checks succeed
	byte[] bytes;
	String when;
	
	String id;							// The identifier of this task
	int errorOccurred=0;
	String badReq="";

	/* Interval */
	long interval = 0;					// If it's an interval task, this is where the interval is stored
	long startDelay = 0;				// If it's an interval task, this is where the delay before first execution is stored
	TimeUnit unit = TimeUnit.SECONDS;	// The time unit of the interval and start delay
	boolean enableOnStart=true;			// If the task should be started on startup

	/* Retry and also channel in a way*/
	int retries=-1;				// How many retries if allowed for a retry task
	int runs = -1;
	int attempts=0;

	ScheduledFuture<?> future;	// The future if the task is scheduled, this way it can be cancelled
	boolean enabled = false;	// Whether or not the task is currently enabled
	String keyword="";
	
	/* output:channel */
	String reply ;				// The reply the task want's to receive as response to the send txt
	long replyInterval=3;
	int replyRetries=5;

	Writable writable;
	String stream="";			// The channel name for the output (as read from the xml)
	
	/* output:file */
	Path outputFile;			// To store the path to the file is the output is file

	/* output:email */
	String attachment="";		// Link to the file to attach to an email

	/* trigger:Clock */
	LocalTime time;				// The time at which the task is supposed to be executed
	ArrayList<DayOfWeek> taskDays;   // On which days the task is to be executed

	boolean utc = false;											// If the time is in UTC or not

	/* Output */
	enum OUTPUT { SYSTEM, MANAGER, LOG, FILE, EMAIL, SMS, STREAM, MQTT, I2C, TELNET, MATRIX }    // The different options for the output, see manual for further explanation
	OUTPUT out = OUTPUT.SYSTEM;											// The output of the task, default is system
	String outputRef="";												// The actual output ie. channel, email address, filename, sms number etc

	/* Link */ 
	enum LINKTYPE { NONE , DISABLE_24H, NOT_TODAY, DO_NOW, SKIP_ONE} // The options for the linking of a task
	String link ;						// If it has a link with a task (can be itself)
	LINKTYPE linktype=LINKTYPE.NONE;	// The type of link
	boolean doToday=true;				// If the task is allowed to be executed today
	int skipExecutions=0;				// How many executions should be skipped

	public enum TRIGGERTYPE {KEYWORD,CLOCK,INTERVAL,DELAY,EXECUTE,RETRY,WHILE,WAITFOR} // The trigger possibilities
	TRIGGERTYPE triggerType = TRIGGERTYPE.EXECUTE;								  		// Default trigger type is execute (no trigger)

	int reqIndex=-1;
	int checkIndex=-1;

	/* Taskset */ 
	private String taskset="";			// The taskset this task is part of
	private int tasksetIndex=-1;		// The index the task has in the taskset it's part of
	private boolean stopOnFail=true;	// Whether or not this task should stop the taskset on failure
	/* *************************************  C O N S T R U C T O R S ***********************************************/
	/**
	 * Constructor that parses an Element to get all the info
	 * @param tsk The element for a task
	 */
	public Task(Element tsk, DataProviding dp, ArrayList<CheckBlock> sharedChecks){

		when  = XMLtools.getStringAttribute( tsk, "state", "always"); //The state that determines if it's done or not

		if( tsk.getTagName().equalsIgnoreCase("while")||tsk.getTagName().equalsIgnoreCase("waitfor")){
			Pair<Long,TimeUnit> period = TimeTools.parsePeriodString(XMLtools.getStringAttribute(tsk,"interval",""));
			interval = period.getKey();
			unit = period.getValue();
			runs = XMLtools.getIntAttribute(tsk,"checks",1);

			switch (tsk.getTagName()) {
				//case "retry": triggerType =TRIGGERTYPE.RETRY; break;
				case "while" -> triggerType = TRIGGERTYPE.WHILE;
				case "waitfor" -> triggerType = TRIGGERTYPE.WAITFOR;
			}
			var check = XMLtools.getStringAttribute(tsk,"check","");
			if( check.isEmpty() )
				check = tsk.getTextContent();
			if( !check.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(check)){
						reqIndex=a;
						break;
					}
				}
				if( checkIndex==-1){
					var cb = CheckBlock.prepBlock(dp,check);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( cb.build() ){
						sharedChecks.add(cb);
						reqIndex = sharedChecks.size()-1;
					}else{
						Logger.error("Failed to parse "+check);
					}
				}
			}
		}else{
			id = XMLtools.getStringAttribute( tsk, "id", ""+new Random().nextLong()).toLowerCase();

			reply = XMLtools.getStringAttribute( tsk, "reply", "");
			var wind = XMLtools.getStringAttribute( tsk, "replywindow", "");
			if( !wind.isEmpty()){
				replyInterval=TimeTools.parsePeriodStringToSeconds(wind);
				replyRetries=1;
			}

			link = XMLtools.getStringAttribute( tsk, "link", "");
			stopOnFail = XMLtools.getBooleanAttribute(tsk,"stoponfail",true);
			enableOnStart = XMLtools.getBooleanAttribute(tsk,"atstartup",true);

			String req = XMLtools.getStringAttribute( tsk, "req", "");
			if( !req.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(req)){
						reqIndex=a;
						break;
					}
				}
				if( reqIndex==-1){
					var cb = CheckBlock.prepBlock(dp,req);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( cb.build() ) {
						sharedChecks.add(cb);
						reqIndex = sharedChecks.size() - 1;
					}else{
						Logger.error("Failed to parse "+req);
					}
				}
			}
			String check = XMLtools.getStringAttribute( tsk, "check", "");
			if( !check.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(check)){
						checkIndex=a;
						break;
					}
				}
				if( checkIndex==-1){
					var cb = CheckBlock.prepBlock(dp,check);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( cb.build() ){
						sharedChecks.add(cb);
						checkIndex = sharedChecks.size()-1;
					}else{
						Logger.error("Failed to parse "+check);
					}
				}
			}

			convertOUT( XMLtools.getStringAttribute( tsk, "output", "system") );
			convertTrigger( XMLtools.getStringAttribute( tsk, "trigger", "") );

			if( tsk.getFirstChild() != null ){
				value = tsk.getFirstChild().getTextContent(); // The control command to execute
				if( value.startsWith("\\h(") ){
					bytes=Tools.fromHexStringToBytes( value.substring(3, value.lastIndexOf(")") ) );
				}else if( value.startsWith("\\d(") ){
					bytes = Tools.fromDecStringToBytes( value.substring(3, value.indexOf(")")) );
				}else{
					bytes=value.getBytes();
				}
			}else{
				Logger.tag("TASK").info("["+(taskset.isEmpty()?"noset":taskset)+"] Task of type "+ triggerType +" without value.");
			}
			/* Actions to take depending on the kind of output, meaning elements that are only present for certain outputs */
			if( out == OUTPUT.EMAIL)
				attachment = XMLtools.getStringAttribute( tsk, "attachment", "");

			/* Link related items */
			if(!link.isBlank()) { // If link is actually mentioned
				String[] linking = link.toLowerCase().split(":");
				switch (linking[0]) {
					case "disable24h" -> linktype = LINKTYPE.DISABLE_24H;// Disable for 24hours
					case "nottoday" -> linktype = LINKTYPE.NOT_TODAY;// Disable for the rest of the day (UTC)
					case "donow" -> linktype = LINKTYPE.DO_NOW;// Execute the linked task now
					case "skipone" -> linktype = LINKTYPE.SKIP_ONE;// Skip one execution of the linked task
				}
				link = linking[1];
			}
		}
	}
	public int getReqIndex( ){
		return reqIndex;
	}
	public int getCheckIndex(){
		return checkIndex;
	}
	public boolean isEnableOnStart(){
		return enableOnStart;
	}
	public boolean errorIncrement(){
		errorOccurred++;
		if( errorOccurred > 10 ){
			Logger.error("Task caused to many failed rtval issues when looking for "+badReq+", cancelling.");
			cancelFuture(false);
			return true;
		}
		return false;
	}
	public void cancelFuture( boolean mayInterruptIfRunning){
		if( future != null)
			future.cancel(mayInterruptIfRunning);
	}
	public boolean stopOnFailure(){
		return stopOnFail;
	}
	public ScheduledFuture<?> getFuture(){
		return this.future;
	}
	public String getID(  ){
		return this.id;
	}
	public void reset(){
		attempts=0;
		future.cancel( false );
		Logger.tag("TASK").info("Reset executed for task in "+this.taskset);
	}
	/* ************************************  T R I G G E R  **********************************************************/
	/**
	 * Retrieve the kind of trigger this task uses
	 * @return String representation of the trigger (uppercase)
	 */
	public TRIGGERTYPE getTriggerType() {
		return triggerType;
	}
	/**
	 * Retrieve the time associated with trigger:clock
	 * @return The readable time
	 */
	public LocalTime getTime(){
		return time;
	}

	private void convertTrigger( String trigger ){
		if( !trigger.isBlank()){

			this.startDelay = 0;

			trigger = trigger.replace(";", ",").toLowerCase();
			trigger = trigger.replace("=",":");
			String cmd = trigger.substring(0, trigger.indexOf(":"));
			if( trigger.equals(cmd) ){
				Logger.tag("TASK").error("Not enough arguments in trigger: "+trigger);
				return;
			}
			String[] items = trigger.substring(trigger.indexOf(":")+1).split(",");
    		switch( cmd ){	
    			case "time":  /* time:07:15 or time:07:15,thursday */
    			case "utctime":
				case "localtime":
					if( !cmd.startsWith("local"))
						utc=true;
					time = LocalTime.parse( items[0],DateTimeFormatter.ISO_LOCAL_TIME );
					taskDays = TimeTools.convertDAY(items.length==2?items[1]:"");
    				triggerType =TRIGGERTYPE.CLOCK;
    				break;
				case "retry":	/* retry:10s,-1 */
				case "while":   /* while:10s,2 */
				case "waitfor": /* waitfor:10s,1 */
					Pair<Long,TimeUnit> period = TimeTools.parsePeriodString(items[0]); 
					interval = period.getKey();
					unit = period.getValue();
					
    				if( items.length > 1 ) {
						runs = Tools.parseInt(items[1], -1);
					}
					switch (cmd) {
						case "retry" -> triggerType = TRIGGERTYPE.RETRY;
						case "while" -> triggerType = TRIGGERTYPE.WHILE;
						case "waitfor" -> triggerType = TRIGGERTYPE.WAITFOR;
					}
					break;
				case "delay":	/* delay:5m3s */
					startDelay = TimeTools.parsePeriodStringToMillis(items[0]);
					unit=TimeUnit.MILLISECONDS;
    				triggerType = TRIGGERTYPE.DELAY;
					break;
    			case "interval": /* interval:5m3s or interval:10s,5m3s*/
					retries=5;
					runs=5;

					if( items.length == 1 ){//Just interval
						interval = TimeTools.parsePeriodStringToMillis(items[0]);
						unit = TimeUnit.MILLISECONDS;
						startDelay = -1;	// So first occurrence is not at 0!
					}else{//Delay and interval
						startDelay = TimeTools.parsePeriodStringToMillis(items[0]);
						interval = TimeTools.parsePeriodStringToMillis(items[1]);
						unit = TimeUnit.MILLISECONDS;
					}
    				triggerType =TRIGGERTYPE.INTERVAL;
    				break;
    			default:
    				this.keyword = trigger; 
    				triggerType =TRIGGERTYPE.KEYWORD;
    				break;
			}
    	}else{
			triggerType = TRIGGERTYPE.EXECUTE;
		}
	}
	/* ***************************************************************************************************/
	/**
	 * If the task is scheduled, this sets the future object
	 * @param future The future to set
	 */
	public void setFuture(java.util.concurrent.ScheduledFuture<?> future){
		this.future=future;
	}
	/**
	 * Verify that the current state corresponds to the needed state
	 * @param state The current state
	 * @return True if the states match
	 */
	public boolean checkState( String state ){
		if( this.when.equalsIgnoreCase(state) || this.when.isBlank() ){
			enabled = true;
			return true;			
		}else{
			future.cancel(false);
			enabled = false;
			return false;
		}
	}
	/* ****************************************  O U T P U T ************************************************************/
	/**
	 * Convert the string representation of the output to usable objects
	 * @param output The string output
	 */
	private void convertOUT( String output ){		
		if( !output.isBlank() ){
			
			String[] o = output.split(":");
			if( o.length>=2)
				outputRef=o[1];
			switch (o[0].toLowerCase()) {
				case "file" -> {
					out = OUTPUT.FILE;
					outputFile = Path.of(o[1]);
				}
				case "email" -> out = OUTPUT.EMAIL;
				case "sms" -> out = OUTPUT.SMS;
				case "channel", "stream" -> {
					out = OUTPUT.STREAM;
					stream = o[1].toLowerCase();
				}
				case "matrix" -> {
					out = OUTPUT.MATRIX;
					stream = o[1].toLowerCase();
				}
				case "log" -> out = OUTPUT.LOG;
				case "manager" -> out = OUTPUT.MANAGER;
				case "mqtt" -> {
					out = OUTPUT.MQTT;
					stream = o[1].toLowerCase();
				}
				case "i2c" -> {
					out = OUTPUT.I2C;
					stream = o.length == 2 ? o[1].toLowerCase() : "";
				}
				case "telnet" -> out = OUTPUT.TELNET;
				default -> out = OUTPUT.SYSTEM;
			}
		}
	}	

	public void setWritable( Writable writable ){
		this.writable=writable;
	}

	/* *******************************************  L I N K **********************************************************/
	/**
	 * Check if the task should run on a specifick day of the week
	 * @param day The day of the week to check
	 * @return True if it should run
	 */
	public boolean runOnDay( DayOfWeek day ){
		return taskDays.contains(day);
	}
	public boolean runNever(  ){
		return taskDays.isEmpty();
	}

	/* ************************************  F O L L O W  U P ********************************************************/
	/**
	 * Check if the task is part of a taskset that step's through tasks
	 * @return True if the task is part of a taskset
	 */
	public boolean hasNext() {
		return tasksetIndex !=-1;
	}
	/**
	 * Get the short name of the taskset this task is part of
	 * @return The short name of the taskset
	 */
	public String getTaskset( ){
		return taskset;
	}
	/**
	 * Get the index of this task in the taskset it is part of
	 * @return The index of this task in the set
	 */
	public int getIndexInTaskset(){
		return tasksetIndex;
	}
	/**
	 * Set the taskset this task is part of and the index in the set
	 * @param taskset The short name of the taskset
	 * @param index The index in the taskset
	 */
	public void setTaskset( String taskset, int index ){
		this.taskset = taskset;
		this.tasksetIndex = index;
	}
	/* **************************************  U T I L I T Y *******************************************************/
	/**
	 * Compare this task to another task in respect to execution, which task is supposed to be executed earlier.
	 */
	@Override
	public int compareTo(Task to) {
		if( to.future != null ) {
			long timeTo = to.future.getDelay(TimeUnit.SECONDS);
			if( future != null ) {
				long thisOne = future.getDelay(TimeUnit.SECONDS);
				return (int) (thisOne - timeTo);
			}
		}
		return 0;
	}
	/**
	 * 
	 */
	public String toString(){
		String suffix="";
		switch(triggerType) {
			case CLOCK:
				if( future !=null ){
					suffix = " scheduled at "+this.time+(utc?" [UTC]":"")+" next occurrence in "+ TimeTools.convertPeriodtoString(future.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS);
					if( future.getDelay(TimeUnit.SECONDS) <0 )
						suffix=".";
				}
				break;
			case DELAY:
				suffix = " after "+TimeTools.convertPeriodtoString(startDelay, unit);
				break;
			case INTERVAL:
				suffix = " every "+ TimeTools.convertPeriodtoString(interval, unit) + (startDelay==0?".":" after initial delay "+TimeTools.convertPeriodtoString(startDelay, unit))
							+ (future==null?".":(" next occurrence in "+ TimeTools.convertPeriodtoString(future.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS)));
				break;
			case KEYWORD:
				suffix = " if "+keyword;
				break;
			default:
				break;
		}		
		if( !when.equals("always")&&!when.isBlank()) {
			suffix += " if state is "+when;
		}
		//if( preReq != null) {
		//	suffix += " "+preReq.toString();
		//}else{
			suffix +=".";
		//}
		return switch (out) {
			case STREAM -> "Sending '" + value.replace("\r", "").replace("\n", "") + "' to " + stream + suffix;
			case EMAIL -> "Emailing '" + value + "' to " + outputRef + suffix;
			case FILE -> "Writing '" + value + "' in " + outputFile + suffix;
			case SMS -> "SMS " + value + " to " + outputRef + suffix;
			case LOG -> "Logging: '" + value + "' to " + outputRef + suffix;
			case MANAGER -> "Executing manager command: '" + value + "'  " + suffix;
			case MQTT -> "Executing mqtt command: '" + value + "'  " + suffix;
			case I2C -> "Sending " + value + " to I2C device " + suffix;
			case TELNET -> "Sending " + value + " to telnet sessions at level " + outputRef + suffix;
			default -> "Executing '" + value + "'" + suffix;
		};
	}
}