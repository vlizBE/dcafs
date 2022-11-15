package util.task;

import io.telnet.TelnetCodes;
import util.data.RealtimeValues;
import io.email.Email;
import io.email.EmailSending;
import io.stream.StreamManager;
import io.collector.CollectorFuture;
import das.CommandPool;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.task.Task.*;
import util.taskblocks.CheckBlock;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskManager implements CollectorFuture {

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);// scheduler for the request data action
	private final ArrayList<Task> tasks = new ArrayList<>(); 			// Storage of the tasks
	private final Map<String, TaskSet> tasksets = new HashMap<>(); 	// Storage of the tasksets
	private Path xmlPath = null; 								// Path to the xml file containing the tasks/tasksets
	private final ArrayList<CheckBlock> sharedChecks =new ArrayList<>();

	/* The different outputs */
	private EmailSending emailer = null;    // Reference to the email send, so emails can be send
	private StreamManager streams; 			// Reference to the streampool, so sensors can be talked to
	private final RealtimeValues rtvals;

	private CommandPool commandPool; // Source to get the data from nexus
	private String id;

	static final String TINY_TAG = "TASK";

	enum RUNTYPE {
		ONESHOT, STEP, NOT
	} // Current ways of going through a taskset, either oneshot (all at once) or step (task by task)
	enum FAILREASON {
		NOCON,NOREQ, NONE, ERROR
	}
	String workPath = "";

	boolean startOnLoad = true;
	ArrayList<String> waitForRestore = new ArrayList<>();

	/* ****************************** * C O N S T R U C T O R **************************************************/

	public TaskManager(String id, RealtimeValues rtvals, CommandPool commandPool) {
		this.commandPool = commandPool;
		this.rtvals = rtvals;
		this.id = id;
	}
	public TaskManager(String id, Path xml ){
		this(id,null,null);
		this.xmlPath=xml;
	}
	public void setScriptPath(Path p){
		xmlPath=p;
	}
	public void setId(String id) {
		this.id = id;
	}

	public void setWorkPath(String path) {
		this.workPath = path;
	}

	/* The different outputs */
	/**
	 * Adds the emailer to the manager enabling it can send emails
	 * @param emailer The implementation to send emails
	 */
	public void setEmailSending(EmailSending emailer) {
		this.emailer = emailer;
	}

	/**
	 * Add the Streampool to the TaskManager if it needs to interact with
	 * streams/channels
	 * 
	 * @param streampool The streampool to set
	 */
	public void setStreamPool(StreamManager streampool) {
		this.streams = streampool;
	}

	public void setCommandReq(CommandPool commandPool) {
		this.commandPool = commandPool;
	}

	public void disableStartOnLoad() {
		startOnLoad = false;
	}
	public Path getXMLPath(){
		return xmlPath;
	}
	
	/* ********************************* * S T A T E S ***************************************************/
	/*
	 * States are an extra trigger/check for a task. fe. the 'state' of the ship,
	 * this can be ship:dock, ship:harbour or ship:atsea For now a task can't change
	 * a state, just check it
	 */
	/**
	 * Altering an existing state
	 * 
	 * @param state The format needs to be identifier:state
	 * @return True if the state was altered, false if it's a new state
	 */
	public boolean changeState(String state) {
		String[] split = state.split(":");
		boolean altered = rtvals.getText(split[0],"").equalsIgnoreCase(split[1]);
		rtvals.setText(split[0],split[1]);
		return altered;
	}

	/**
	 * Check whether a state is active
	 * 
	 * @param id The state to check
	 * @return True if it's active
	 */
	public boolean checkState(String id) {
		if (id.isBlank() || id.equalsIgnoreCase("always"))
			return true;
		var state = id.split(":");
		return rtvals.getText(state[0],"").equalsIgnoreCase(state[1]);
	}

	/* ************************ * WAYS OF RETRIEVING TASKS ************************************************/
	/**
	 * Retrieve a task using its id
	 * 
	 * @param id The id of the task
	 * @return The found task or null if none
	 */
	public Task getTaskbyID(String id) {
		for (Task task : tasks) {
			if (task.id.equalsIgnoreCase(id))
				return task;
		}
		return null;
	}

	/* ************************* WAYS OF ADDING TASKS ********************************************************/
	/**
	 * General add task method that uses an element from an xml file to retrieve all
	 * the needed data
	 * 
	 * @param tsk The Element that contains the task info
	 */
	public void addTask(Element tsk) {
		Task task = new Task(tsk, rtvals, sharedChecks);

		if (startOnLoad && task.getTriggerType()!=TRIGGERTYPE.EXECUTE && task.isEnableOnStart()) {
			startTask(task);
		}
		tasks.add(task);
	}

	/**
	 * To add a task to an existing taskset
	 * 
	 * @param id	 The id of the taskset
	 * @param task      The Task to add to it
	 */
	public void addTaskToSet(String id, Task task) {
		TaskSet set = tasksets.get(id);
		if (set != null) {
			task.id = id+"_"+set.getTaskCount();
			set.addTask(task);
			Logger.tag(TINY_TAG).info("[" + this.id + "] Adding task to set:" + task);
		} else {
			Logger.tag(TINY_TAG).error("[" + this.id + "] Failed to ask task to set, no such set");
		}
	}

	/**
	 * To create a taskset
	 *
	 * @param id	    The id used to reference the taskset
	 * @param description      Descriptive name of the tasket
	 * @param run       How the taskset should be run either oneshot or step
	 * @param repeats   How often the task should be repeated, default is once
	 * @param failure   Which taskset (short) should be run if a task in the set
	 *                  fails
	 * @return The tasket if it was added or it if it already exists
	 */
	public TaskSet addTaskSet(String id, String description, RUNTYPE run, int repeats, String failure) {
		
		if (tasksets.get(id) == null) {
			var set = new TaskSet(id, description, run, repeats);
			set.setFailureTask(failure);
			set.setManagerID(this.id);
			tasksets.put(id, set);
			Logger.tag(TINY_TAG).info("[" + this.id + "] Created new Task set: " + id + " , " + description);
			return set;
		}
		Logger.error("[" + this.id + "] Already such Task set: " + id + " , " + description);
		return tasksets.get(id);
	}
	/* ***************************** WAYS OF ENABLING TASKS ***************************************************/
	/**
	 * Start a task that has the trigger based on a keyword
	 * 
	 * @param keyword The keyword to check for tasks to execute
	 */
	public void startKeywordTask(String keyword) {
		Logger.tag(TINY_TAG).info("[" + id + "] Checking " + tasks.size() + " tasks, for keyword triggers");
		for (Task task : tasks) {
			boolean ok = false;
			if (task.triggerType == TRIGGERTYPE.KEYWORD) {
				if (task.keyword.equals(keyword)) {
					ok = true;
					task.future = scheduler.schedule(new DelayedControl(task), 0, TimeUnit.SECONDS);
				}
				Logger.tag(TINY_TAG).info("[" + id + "] Checking if " + task.keyword + " corresponds with " + keyword
						+ " EXECUTED?" + ok);
			}
		}
	}

	/**
	 * Cancel a scheduled task
	 * @param id The id of the task to cancel
	 * @return True if it exists and was cancelled
	 */
	public boolean cancelTask(String id) {
		for (Task task : this.tasks) {
			if (task.getID().equals(id)&&!task.getFuture().isDone()) {
				task.getFuture().cancel(true);
				Logger.tag(TINY_TAG).info("[" + this.id + "] Task with id " + id + " cancelled.");
				return true;				
			}
		}
		return false;
	}

	/**
	 * Start a task with the given id
	 * @param id The id of the task to start
	 * @return True if the task was found and started
	 */
	public boolean startTask(String id) {
		for (Task task : this.tasks) {
			if (task.getID().equals(id)) {
				Logger.tag(TINY_TAG).info("[" + this.id + "] Task with id " + id + " started.");
				return startTask(task);//doTask(task);
			}
		}
		Logger.tag(TINY_TAG).warn("[" + this.id + "] Task with id '" + id + "' not found.");
		return false;
	}

	/**
	 * Check if this taskmanager has a taskset with the given id
	 * @param id The id to look for
	 * @return True if found
	 */
	public boolean hasTaskset(String id) {
		return  tasksets.get(id)!=null;
	}
	/**
	 * To start a taskset based on the short
	 * 
	 * @param id The reference of the taskset to start
	 * @return The result of the attempt
	 */
	public String startTaskset(String id) {
		TaskSet ts = tasksets.get(id);
		if (ts != null) {
			if (ts.getTaskCount() != 0) {
				switch( runCheck(ts.getReqIndex()) ){
					case 1:
						Logger.tag(TINY_TAG).debug("[" + this.id + "] Taskset started " + id);
						if (ts.getRunType() == RUNTYPE.ONESHOT) {
							startTasks(ts.getTasks());
							return "Taskset should be started: " + id;
						} else if (ts.getRunType() == RUNTYPE.STEP) {
							startTask(ts.getTasks().get(0));
							return "Started first task of taskset: " + id;
						} else {
							return "Didn't start anything...! Runtype=" + ts.getRunType();
						}
					case 0:
						Logger.warn("Check failed for "+ts.getID()+" : "+ts.getReqIndex() );
						return "Check failed for "+ts.getID()+" : "+ts.getReqIndex();
					default:
					case -1:
						Logger.error("Error during check for "+ts.getID()+" : "+ts.getReqIndex() );
						return "Check not run for "+ts.getID()+" : "+ts.getReqIndex();
				}
			} else {
				Logger.tag(TINY_TAG).info("[" + this.id + "] TaskSet " + ts.getDescription() + " is empty!");
				return "TaskSet is empty:" + id;
			}
		} else {
			Logger.tag(TINY_TAG).warn("[" + this.id + "] Taskset with " + id+" not found");
			return "No such taskset:" + id;
		}
	}
	public int runCheck( int index ){
		if( index == -1 )
			return 1;
		if( index >= sharedChecks.size()) {
			Logger.error( id+" (ts) -> Invalid index asked for check "+index);
			return -1;
		}
		return sharedChecks.get(index).start(null)?1:0;
	}
	public String getCheckInfo( int index ){
		if( index ==-1)
			return "";
		if( index >= sharedChecks.size()) {
			Logger.error( id+" (ts) -> Invalid index asked for check "+index);
			return "";
		}
		return sharedChecks.get(index).toString();
	}

	/**
	 * Check if the intervaltasks have been started. If not, do so.
	 */
	public void recheckIntervalTasks() {
		Logger.tag(TINY_TAG).info("[" + id + "] Running checks...");
		for (Task task : tasks) {
			checkIntervalTask(task);
		}
	}

	/**
	 * Check a task with triggertype interval or retry, if it shouldn't be running
	 * it's cancelled if it should and isn't it's started
	 * 
	 * @param task The task to check if it needs to be started or cancelled
	 */
	public void checkIntervalTask(Task task) {
		if (task.triggerType != TRIGGERTYPE.INTERVAL
				&& task.triggerType != TRIGGERTYPE.RETRY
				&& task.triggerType != TRIGGERTYPE.WHILE
				&& task.triggerType != TRIGGERTYPE.WAITFOR)
			return;

		if (checkState(task.when)) { // Check whether the state allows for the task to run
			if (task.future == null || task.future.isCancelled()) {
				Logger.tag(TINY_TAG).info("[" + id + "] Scheduling task: " + task + " with delay/interval/unit:"
						+ task.startDelay + ";" + task.interval + ";" + task.unit);
				try {
					long delay = task.startDelay;
					if(task.startDelay==-1){ // Figure out the delay
						delay = TimeTools.millisDelayToCleanTime(task.interval);
					}
					if( task.interval == 0){
						Logger.error(id+" -> Bad delay calculated from "+task.interval+ "for "+task.id+", so not starting.");
					}else {
						task.future = scheduler.scheduleAtFixedRate(new DelayedControl(task), delay,
								task.interval, task.unit);
						Logger.info(id + " -> Delay set to " + TimeTools.convertPeriodtoString(delay, TimeUnit.MILLISECONDS) + " for " + task.interval +" "+ task.unit + " interval");
					}
				} catch (IllegalArgumentException e) {
					Logger.tag(TINY_TAG).error("Illegal Argument: start=" + task.startDelay + " interval=" + task.interval + " unit="
							+ task.unit);
				}
			} else {
				if (task.future != null && task.future.isCancelled()) {
					Logger.tag(TINY_TAG).info("[" + id + "] Task future was cancelled " + task.id);
				}
				Logger.tag(TINY_TAG).info("[" + id + "] Task already running: " + task + " with delay/interval/unit:"
						+ task.startDelay + ";" + task.interval + ";" + task.unit);
			}
		} else { // If the check fails, cancel the running task
			if (task.future != null) {
				task.future.cancel(false);
				Logger.tag(TINY_TAG).info("[" + id + "] Cancelled task already running: " + task);
			} else {
				Logger.tag(TINY_TAG).error("[" + id + "] NOT Scheduling (checkState failed): " + task);
			}
		}
	}

	/**
	 * Start a list of tasks fe. the content of a taskset
	 * 
	 * @param tasks The list of tasks
	 */
	private void startTasks(List<Task> tasks) {
		for (Task task : tasks) {
			startTask(task);
		}
	}

	/**
	 * Try to start a single task.
	 * 
	 * @param task The task to start
	 */
	private boolean startTask(Task task) {
		if (task == null) {
			Logger.tag(TINY_TAG).error("[" + id + "] Trying to start a task that is still NULL (fe. end of taskset)");
			return false;
		}
		Logger.tag(TINY_TAG).debug("[" + id + "] Trying to start task of type: " + task.triggerType + "(" + task.value + ")");
		switch (task.triggerType) {
			case CLOCK:
				long min = calculateSeconds(task);
				if (min != -1) {
					task.future = scheduler.schedule(new DelayedControl(task), min, TimeUnit.SECONDS);
					Logger.tag(TINY_TAG).info("[" + id + "] Scheduled sending " + task.value + " in "
							+ TimeTools.convertPeriodtoString(min, TimeUnit.SECONDS) + ".");
				} else {
					Logger.tag(TINY_TAG).error("[" + id + "] Something went wrong with calculating minutes...");
				}
				break;
			case RETRY:
			case INTERVAL:
			case WAITFOR:
			case WHILE:
				checkIntervalTask(task);
				break;
			case DELAY:
				if (task.when == null) {
					Logger.tag(TINY_TAG).error("[" + id + "] WHEN IS NULL!?!?");
				}
				if (task.future != null) {
					task.future.cancel(true);
				}
				Logger.tag(TINY_TAG).debug("[" + id + "] Scheduled sending '" + task.value + "' in "
						+ TimeTools.convertPeriodtoString(task.startDelay, task.unit) + " and need "+(task.reply.isEmpty()?"no reply":("'"+task.reply+"' as reply")));
				task.future = scheduler.schedule(new DelayedControl(task), task.startDelay, task.unit);
				break;
			case EXECUTE:
				doTask(task);
				break;
			case KEYWORD: default:
				break;
		}
		return true;
	}

	public int stopTaskSet(String title) {
		for (TaskSet set : this.tasksets.values()) {
			if (set.getID().equals(title)) {
				return set.stop("manager command");
			}
		}
		return -1;
	}

	public int stopAll(String reason) {
		int stopped = 0;
		for (TaskSet set : this.tasksets.values()) {
			stopped += set.stop(reason);
		}
		for (Task task : this.tasks) {
			if (task.getFuture() != null && !task.getFuture().isCancelled()) {
				task.getFuture().cancel(true);
				stopped++;				
			}
		}
		return stopped;
	}

	/* ************************************ * WAYS OF EXECUTING TASKS **************************************/
	/**
	 * To execute tasks with a delay or fixed interval
	 */
	public class DelayedControl implements Runnable {
		Task task;

		public DelayedControl(Task c) {
			this.task = c;
		}

		@Override
		public void run() {

			FAILREASON executed;
			try {
				executed = doTask(task);
			} catch (Exception e) {
				Logger.tag(TINY_TAG).error("Null pointer in doTask " + e.getMessage());
				return;
			}

			TaskSet set = tasksets.get(task.getTaskset());

			if (executed==FAILREASON.NONE) { // if it was executed
				if (task.triggerType == TRIGGERTYPE.RETRY) {
					switch (checkRequirements(task, false)) { // If it meets the post requirements
						case 0: executed=FAILREASON.NOREQ;
						case 1:
							task.reset();
							Task t = set.getNextTask(task.getIndexInTaskset());
							if (t != null) {
								startTask(t);
							} else {
								Logger.tag(TINY_TAG).info("[" + id + "] Executed last task in the tasket [" + set.getDescription() + "]");
							}
							break;
						case -1:
							executed=FAILREASON.NOREQ;
							task.errorIncrement();
							break;
						default: Logger.error("Unexpected return from verify Requirement"); break;
					}
				} else if (task.triggerType == TRIGGERTYPE.WHILE || task.triggerType == TRIGGERTYPE.WAITFOR ) {
					task.attempts++;
					Logger.tag(TINY_TAG).info("Attempts "+task.attempts+" of runs "+task.runs);
					if (task.runs == task.attempts) {
						task.reset();
						Logger.info("Reset attempts to "+task.attempts+" and go to next task");
						Task t = set.getNextTask(task.getIndexInTaskset());
						if (t != null) {
							startTask(t);
						} else {
							Logger.tag(TINY_TAG).info("[" + id + "] Executed last task in the taskset [" + set.getDescription() + "]");
						}
					}
				} else if (task.triggerType == TRIGGERTYPE.INTERVAL) {
					task.runs = task.retries; //so reset
				}
			}
			if (executed!=FAILREASON.NONE) { // If it wasn't executed
				boolean failure = true;
				if (task.triggerType == TRIGGERTYPE.WHILE) {
					Logger.tag(TINY_TAG).info("[" + id + "] " + set.getDescription() + " failed, cancelling.");
					task.reset();
				}else if (task.triggerType == TRIGGERTYPE.WAITFOR) {
					failure = false;
					Logger.tag(TINY_TAG).info("Requirement not met, resetting counter failed "+getCheckInfo(task.getReqIndex()));
					task.attempts = 0; // reset the counter
				}else if (task.triggerType == TRIGGERTYPE.RETRY || (task.triggerType == TRIGGERTYPE.INTERVAL && executed!=FAILREASON.NOREQ)) {
					failure = false;
					if (task.runs == 0) {
						task.reset();
						task.runs=task.retries;
						Logger.tag(TINY_TAG).error("[" + id + "] Maximum retries executed, aborting!\t" + task.toString());
						if( task.out==OUTPUT.STREAM && !waitForRestore.contains(task.stream)) {
							waitForRestore.add(task.stream);
							if (waitForRestore.size()==1) // Only start this if it's not supposed to be active
								scheduler.schedule(new ChannelRestoreChecker(), 15, TimeUnit.SECONDS);
						}
						failure = true;
					} else if (task.runs > 0) {
						task.runs--;
						Logger.tag(TINY_TAG).error(
								"[" + id + "] Not executed, retries left:" + task.runs + " for " + task);
					}
				}
				if (failure) {
					if( set.getRunType()==RUNTYPE.STEP ) // If a step fails, then cancel the rest
						runFailure(set,"Task in the set "+set.getID()+" failed, running failure");
				}
			}
		}
	}
	private void runFailure( TaskSet set, String reason ){
		if( set==null) {
			Logger.tag(TINY_TAG).error(id + " -> Tried to run failure for invalid taskset");
			return;
		}
		set.stop(reason);
		String fs = set.getFailureTaskID();
		if( !fs.contains(":") )
			fs="taskset:"+fs;

		if (fs.startsWith("task:")) {
			Task t = getTaskbyID(fs);
			if (t != null) {
				startTask(t);
			}
		} else if (fs.startsWith("taskset:") ) {
			startTaskset(fs.substring(8));
		}
	}
	/**
	 * To execute a task
	 * 
	 * @param task The task to execute
	 * @return True if it was executed
	 */
	private synchronized FAILREASON doTask(Task task) {

		int ver = checkRequirements(task, true); // Verify the pre req's
		if( ver == -1 ){
			task.errorIncrement();
			return FAILREASON.ERROR;
		}
		boolean verify = ver==1;
		FAILREASON executed = verify?FAILREASON.ERROR:FAILREASON.NOREQ;

		if (task.skipExecutions == 0 // Check if the next execution shouldn't be skipped (related to the 'link' keyword)
				&& task.doToday // Check if the task is allowed to be executed today (related to the 'link' keyword)
				&& verify // Check if the earlier executed pre req checked out
				&& checkState(task.when)) { // Verify that the state is correct

			executed = FAILREASON.NONE; // First checks passed, so it will probably execute

			if (task.triggerType == TRIGGERTYPE.WHILE || task.triggerType == TRIGGERTYPE.WAITFOR) { // Doesn't do anything, just check
				// Logger.tag(TINY_TAG).info("Trigger for while fired and ok")
				return FAILREASON.NONE;
			}
			if (task.triggerType != TRIGGERTYPE.INTERVAL)
				Logger.tag(TINY_TAG).debug("[" + id + "] Requirements met, executing task with " + task.value);

			String response = "";
			String header = "DCAFS Message"; // Standard email header, might get overriden
			String fill = task.value; // The value of the task might need adjustments

			if (task.value.startsWith("taskset:") || task.value.startsWith("task:")) { // Incase the task starts a taskset
				String setid = task.value.split(":")[1];
				try {
					if (hasTaskset(setid)) {
						Logger.tag(TINY_TAG).info("[" + this.id + "] " + startTaskset(setid));
					} else if (!this.startTask(setid)) {
						Logger.tag(TINY_TAG).info("[" + this.id + "] No such task or taskset");
					}
				}catch( NullPointerException e){
					Logger.error("Nullpointer trying to run startTaskset "+setid+" -> "+e.getMessage());
					return FAILREASON.ERROR;
				}
			} else { // If not a supposed to run a taskset, check if any of the text needs to be
						// replaced
				if (task.value.contains("{")) { // Meaning there's an { somewhere
					fill = fillIn(task.value, task.value);
				}
				String[] splits = fill.split(";");

				if (splits.length == 2) {
					if (!splits[1].isBlank()) {
						header = splits[0];
					} else {
						splits[0] += ";";
					}
				}
				if (task.value.startsWith("cmd") || ( task.out != OUTPUT.MQTT && task.out != OUTPUT.STREAM && task.out != OUTPUT.MANAGER && task.out != OUTPUT.TELNET && task.out != OUTPUT.MATRIX)) {
					// This is not supposed to be used when the output is a channel or no reqdata defined

					if (commandPool == null) {
						Logger.tag(TINY_TAG).warn("[" + id + "] No BaseReq (or child) defined.");
						return FAILREASON.ERROR;
					}
					if (task.out == OUTPUT.SYSTEM || task.out == OUTPUT.STREAM ) {
						var d = Datagram.build(fill.replace("cmd:", "").replace("\r\n", ""));
						response = commandPool.createResponse( d, true);
					} else if( task.out == OUTPUT.I2C ){
						response = commandPool.createResponse( Datagram.build("i2c:"+task.value), true);
					}else if( task.out == OUTPUT.EMAIL ){
						if( splits.length==2){
							String it = splits[1]+(splits[1].contains(":")?"":"html");
							response = commandPool.createResponse( Datagram.build(it), true);
						}
					}else if( task.out != OUTPUT.LOG ){
						String it = splits[splits.length - 1]+(splits[splits.length - 1].contains(":")?"":"html");
						response = commandPool.createResponse( Datagram.build(it), true);
					}
					if (response.toLowerCase().startsWith("unknown")) {
						response = splits[splits.length - 1];
					}
				}

				switch (task.out) {
					case FILE: // Write the response to the specified file
						if( task.outputFile.isAbsolute()) {
							FileTools.appendToTxtFile(task.outputFile.toString(), response);
						}else{
							FileTools.appendToTxtFile(xmlPath.getParent().resolve(task.outputFile).toString(), response);
						}
						break;
					case EMAIL: // Send the response in an email
						if (emailer == null) {
							Logger.tag(TINY_TAG).error("[" + id + "] Task not executed because no valid EmailSending");
							return FAILREASON.ERROR;
						}
						response = "<html>" + response.replace("\r\n", "<br>") + "</html>";
						response = response.replace("<html><br>", "<html>");
						response = response.replace("\t", "      ");
						for (String item : task.outputRef.split(";")) {
							if( splits.length==1)
								response = "";
							emailer.sendEmail( Email.to(item).subject(header).content(response).attachment(task.attachment) );
						}
						break;
					case STREAM: // Send the value to a device
						if (streams == null) { // Can't send anything if the streampool is not defined
							Logger.tag(TINY_TAG).error("[" + id + "] Wanted to output to a stream (" + task.stream
									+ "), but no StreamManager defined.");
							return FAILREASON.ERROR;
						}

						if (!streams.isStreamOk(task.stream, true)) { // Can't send anything if the channel isn't
																			// alive
							Logger.tag(TINY_TAG).error("[" + id + "] Wanted to output to a stream (" + task.stream
									+ "), but channel not alive.");
							task.writable = null;
							return FAILREASON.NOCON;
						}
						boolean ok;
						if (task.triggerType != TRIGGERTYPE.INTERVAL) { // Interval tasks are executed differently
							if( !task.reply.isEmpty() ){
								ok= streams.writeWithReply(this, task.getTaskset(), task.stream, task.value, task.reply,task.replyInterval,task.replyRetries);
							}else{
								ok=!streams.writeToStream(task.stream, fill, task.reply).isEmpty();
							}
						} else {							
							if( !task.reply.isEmpty() ){
								ok= streams.writeWithReply(this, task.getTaskset(), task.stream, task.value, task.reply);
							}else{
								if( task.writable == null ){
									var res = streams.writeToStream(task.stream, fill, task.reply);
									if( res.isEmpty() ) {
										ok = false;
									}else{
										if( !task.value.contains("{"))
											task.value=res;
										ok = true;
									}
									streams.getWritable(task.stream).ifPresent( task::setWritable );
								}else{
									ok=task.writable.writeLine( fill );
								}
							}
						}
						if( !ok ){
							Logger.error("Something wrong (ok=false)...");
							return FAILREASON.ERROR;
						}
						break;
					case MATRIX:
						String resp;
						if( task.value.startsWith(""+'"')){
							resp = task.value.replace(""+'"',"");
						}else {
							resp = commandPool.createResponse(Datagram.system(task.value), false, true);
						}
						commandPool.createResponse(Datagram.system("matrix:say,"+task.stream+","+resp),false,true);
						break;
					case LOG: // Write the value in either status.log or error.log (and maybe send an email)
						String[] spl = task.value.split(";");
					
						String device = "none";
						String mess = fill;//task.value;

						if( spl.length == 2 ){
							device = spl[0];
							mess = spl[1];
						}
						switch (task.outputRef) {
							case "info" ->    // Just note the info
									Logger.tag(TINY_TAG).info("[" + id + "] " + device + "\t" + mess);
							case "warn" ->    // Note the warning
									Logger.tag(TINY_TAG).warn("TaskManager.reportIssue\t[" + id + "] " + device + "\t" + mess);
							case "error" -> {    // Note the error and send email
								Logger.tag(TINY_TAG).error("TaskManager.reportIssue\t[" + id + "] " + device + "\t" + mess);
								emailer.sendEmail(Email.toAdminAbout(device + " -> " + mess));
							}
							default -> Logger.error("Tried to use unknown outputref: " + task.outputRef);
						}
						break;
					case TELNET:
						var send = rtvals.parseRTline(task.value,"");
						commandPool.createResponse(Datagram.build("telnet:broadcast,"+task.outputRef+","+send),false);
						break;
					case MANAGER:
						String[] com = task.value.split(":");
						if( com.length != 2 ){
							break;
						}

						switch (com[0]) {
							case "raiseflag" -> rtvals.raiseFlag(com[1]);
							case "lowerflag" -> rtvals.lowerFlag(com[1]);
							case "start" -> startTaskset(com[1]);
							case "stop" -> {
								int a = stopTaskSet(com[1]);
								if (a == -1) {
									Logger.tag(TINY_TAG).warn("Failed to stop the taskset '" + com[1] + "' because not found");
								} else {
									Logger.tag(TINY_TAG).info("Stopped the tasket '" + com[1] + "' and " + a + " running tasks.");
								}
							}
							default -> {
								TaskSet set = this.tasksets.get(com[0].toLowerCase());
								if (set != null) {
									Optional<Task> t = set.getTaskByID(com[1].toLowerCase());
									if (t.isPresent() && this.startTask(t.get())) {
										Logger.tag(TINY_TAG).info("Started Managed task:" + com[1]);
									}
								} else {
									Logger.tag(TINY_TAG).warn("Failed to start managed taskset, not found: " + com[0]);
								}
							}
						}
						break;
					case I2C:
						break;
					case SYSTEM: // Same as log:info
					default:
						if( task.triggerType != TRIGGERTYPE.INTERVAL){
							if( response.endsWith("\r\n") )
								response = response.substring(0,response.length()-2);
							Logger.tag(TINY_TAG).debug("["+ id +"] " + response );
						}
						break;	
				}
			}
			if( task.linktype != LINKTYPE.NONE ) { //Meaning if there's a link with another task
				for( String linked : task.link.split(";") ) {
					Logger.tag(TINY_TAG).info( "["+ id +"] Looking for "+linked+ " (from "+task.id+")");
					for( Task t : tasks ) {
						if( t.id.equals(linked)) {
							Logger.tag(TINY_TAG).info( "["+ id +"] Found link! with "+linked);
							switch( task.linktype ) {
								case DISABLE_24H: // Linked task will be disabled for the next 24 hours
									t.doToday=false;
									Logger.tag(TINY_TAG).info( "["+ id +"] Linked task disabled for 24hours.");
									scheduler.schedule(new DoTodayResetter(t), 24, TimeUnit.HOURS);
									break;
								case NOT_TODAY: // Linked task won't be allowed to be executed again today
									t.doToday=false;
									Logger.tag(TINY_TAG).info( "["+ id +"] Linked task disabled till midnight.");
									scheduler.schedule(new DoTodayResetter(t), TimeTools.secondsToMidnight(), TimeUnit.SECONDS);
									break;
								case DO_NOW: 	 doTask(t);      break; // Execute the linked task now
								case SKIP_ONE:	 t.skipExecutions=1;  break; // Disallow one execution
								case NONE:break;
							}							
						}
					}
				}
			}		
		}else{
			if( !task.stopOnFailure() )
				executed=FAILREASON.NONE;

			if( !task.doToday ) {
				Logger.tag(TINY_TAG).info( "["+ id +"] Not executing "+task.value +" because not allowed today.");
			}
		}
		if( task.value.startsWith("taskset:") || task.value.startsWith("task:")) {
			String shortName = task.value.split(":")[1];
			Logger.tag(TINY_TAG).debug( "["+ id +"] "+shortName+" -> "+executed+"\r\n");
		}
		
		if( task.skipExecutions > 0)
			task.skipExecutions--;
		
		if( task.triggerType ==TRIGGERTYPE.CLOCK ){ //maybe replace this with interval with delay to time and then 24 hours interval?
			long next = calculateSeconds( task );  // Calculate seconds till the next execution, if it isn't the first time this should be 24 hours
			task.future.cancel(true);
			if( next > 0 ) {
				task.future = scheduler.schedule( new DelayedControl(task), next, TimeUnit.SECONDS );
				Logger.tag(TINY_TAG).info( "["+ id +"] Rescheduled to execute "+task.value +" in "+TimeTools.convertPeriodtoString(next, TimeUnit.SECONDS)+".");
			}else{
				Logger.tag(TINY_TAG).info( " Next is :"+next );
				if( emailer != null) {
					emailer.sendEmail( Email.to("admin").subject("Failed to reschedule task").content(task.toString()) );
				}else {
					Logger.tag(TINY_TAG).error( "["+ id +"] Failed to reschedule task "+ task);
				}
			}
		}
		if( task.hasNext() ) { // If the next task in the set could be executed
			TaskSet set = tasksets.get( task.getTaskset() );					
			if( set != null && set.getRunType() == RUNTYPE.STEP ) {	// If the next task in the set should be executed

				if( executed==FAILREASON.NONE && task.triggerType != TRIGGERTYPE.RETRY ){ // If the task was executed and isn't of the trigger:retry type
					if( task.reply.isEmpty() ) {
						Task t = set.getNextTask(task.getIndexInTaskset());
						if (t != null) {
							startTask(t);
						} else {
							Logger.tag(TINY_TAG).info("[" + id + "] Executed last task in the taskset [" + set.getDescription() + "]");
						}
					}else{
						set.setLastIndexRun(task.getIndexInTaskset());
					}
				}
			}
		}
		return executed;
	}

	/* **************************************** UTILITY *******************************************************/
	/**
	 * Get a listing of all the managed tasks
	 * @param eol Characters to use for End Of Line
	 * @return A string with the toString result of the tasks
	 */
	public String getTaskListing( String eol ){
		if( tasks.isEmpty() )
			return "Task list empty."+eol;
			
		StringJoiner b = new StringJoiner(eol,"-Runnable Tasks-"+eol,eol+eol);
		StringJoiner intr = new StringJoiner(eol,"-Interval Tasks-"+eol,eol+eol);
		StringJoiner clk = new StringJoiner(eol,"-Clock Tasks-"+eol,eol+eol);
		StringJoiner other = new StringJoiner(eol,"-Other Tasks-"+eol,eol+eol);

		Collections.sort(tasks);
		for( Task task : tasks){
			if(NumberUtils.isParsable(task.id) ){//Meaning the id is a randomly given number
				if( task.triggerType==TRIGGERTYPE.CLOCK ) {
					clk.add( task.toString() );
				}else if( task.triggerType==TRIGGERTYPE.INTERVAL ) {
					intr.add( task.toString() );
				}else{
					other.add(task.toString());
				}
			}else{
				b.add(task.id + " -> " + task);
			}
		}
		Logger.info("clk;"+clk.length());
		Logger.info("other;"+other.length());
		Logger.info("intr;"+intr.length());
		return b.toString()+ (clk.length()>19?clk:"") + intr + (other.length()>19?other:"");
	}
	/**
	 * Get a listing of all the managed tasksets
	 * @param eol Characters to use for End Of Line
	 * @return A string with the toString result of the taskset
	 */
	public String getTaskSetListing( String eol ){
		if( tasksets.isEmpty() ){
			return "No tasksets yet."+eol;
		}
		StringBuilder b = new StringBuilder("-Task sets-");
		b.append(eol);
		for( TaskSet set : tasksets.values()){
			b.append((char) 27).append("[31m").append(set.getID()).append(" ==> ").append(set.getDescription()).append("(")
					.append(set.getTasks().size()).append(" items)").append("Run type:").append(set.getRunType()).append(eol);
			for( Task s : set.getTasks() ) {
				b.append((char) 27).append("[33m").append("\t-> ").append( s.toString()).append(eol);
			}			
		}
		return b.toString();
	}
	/**
	 * Calculate the seconds till the next execution of the task
	 * @param task The task to calculate the time for
	 * @return The time in seconds till the next execution
	 */
	private long calculateSeconds( Task task ){

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);        
		if( !task.utc )
			now = LocalDateTime.now();

		LocalDateTime taskTime = now.with(task.time); // Use the time of the task
		taskTime=taskTime.plusNanos(now.getNano());

        if( taskTime.isBefore(now.plusNanos(100000)) ) // If already happened today
            taskTime=taskTime.plusDays(1);

        if( task.runNever() ){ // If the list of days is empty
            return -1;
        }    
        int x=0;
        while( !task.runOnDay(taskTime.getDayOfWeek()) ){
            taskTime=taskTime.plusDays(1);
            x++;
            if( x > 8 ) //shouldn't be possible, just to be sure
                return -1;
        }
        return Duration.between( now, taskTime ).getSeconds()+1;
	}
	/**
	 * This is mainly used to add a timestamp to the value of the task
	 * @param line The string in which to replace the keyword
	 * @param def If the line is empty, which string to return as default
	 * @return The line after any replacements
	 */
    public String fillIn( String line, String def ){
    	if( line.isBlank())
    		return def;
    	line = line.replace("{localtime}", TimeTools.formatNow("HH:mm"));
    	line = line.replace("{utcstamp}", TimeTools.formatUTCNow("dd/MM/YY HH:mm:ss"));
		line = line.replace("{utcdate}", TimeTools.formatUTCNow("yyMMdd"));
    	line = line.replace("{localstamp}", TimeTools.formatNow("dd/MM/YY HH:mm:ss"));
		line = line.replace("{utcsync}", TimeTools.formatNow("'DT'yyMMddHHmmssu"));
		line = line.replace("{rand6}", ""+(int)Math.rint(1+Math.random()*5));
		line = line.replace("{rand20}", ""+(int)Math.rint(1+Math.random()*19));
		line = line.replace("{rand100}", ""+(int)Math.rint(1+Math.random()*99));

		String to;
		int i = line.indexOf("{ipv4:");
		if( i !=-1 ){
			int end = line.substring(i).indexOf("}");
			to = Tools.getIP(line.substring(i+6,i+end),true);
			line = line.replace(line.substring(i,i+end+1),to);
		}
		i = line.indexOf("{ipv6:");
		if( i !=-1 ){
			int end = line.substring(i).indexOf("}");
			to = Tools.getIP(line.substring(i+6,i+end),false);
			line = line.replace(line.substring(i,i+end+1),to);
		}
		i = line.indexOf("{mac:");
		if( i !=-1 ){
			int end = line.substring(i).indexOf("}");
			to = Tools.getMAC( line.substring(i+5,i+end) );
			line = line.replace(line.substring(i,i+end+1),to);
		}
		line = rtvals.parseRTline(line,"");
    	line = line.replace("[EOL]", "\r\n");
    	return line;
    }
    /* *******************************************  V E R I F Y ***************************************************** */
	/**
	 * RtvalCheck all the requirements in a task
	 * @param task The task to check
	 * @param pre True if checking the pre checks and false for the post
	 * @return 1=ok,0=nok,-1=error
	 */
	private int checkRequirements(Task task, boolean pre ) {
		if( sharedChecks.isEmpty()) // If no checks are stored, return ok
			return 1;
		return runCheck( pre?task.getReqIndex():task.getCheckIndex() );
	}

	/* *******************************************************************************************************/
	/**
	 * If a task is prohibited of execution for a set amount of time, this is scheduled after that time.
	 * This restores execution.
	 */
	public static class DoTodayResetter implements Runnable {
		Task task;		
		
		public DoTodayResetter(Task c){
			this.task=c;
		}
		@Override
		public void run() {		
			task.doToday=true;		
		}
	}
	/* ********************************* L O A D &  U N L O A D ***********************************************/
	/**
	 * Shut down all running tasks and clear the lists
	 */
	public void shutdownAndClearAll(){

		// Make sure the future's are cancelled
		for( var set : tasksets.values()){
			for( var task : set.getTasks()){
				if(task.future!=null)
					task.future.cancel(true);
			}
		}
		// Clear the sets
		tasksets.clear();

		// Make sure the futures are cancelled
		for( var task:tasks ){
			if(task.future!=null)
				task.future.cancel(true);
		}
		// clear the tasks
		tasks.clear();
	}
	/**
	 * Reload the tasks previously loaded.
	 * @return The result,true if successful or false if something went wrong
	 */
	public boolean reloadTasks() {
		return loadTasks(xmlPath,true,false);
	}
		/**
	 * Reload the tasks previously loaded.
	 * @return The result,true if successful or false if something went wrong
	 */
	public boolean forceReloadTasks() {
		return loadTasks(xmlPath,true,true);
	}
	/**
	 * Load an xml file containing tasks and tasksets, if clear is true all tasks and tasksets already loaded will be removed
	 * @param path The path to the XML file
	 * @param clear Whether to remove the existing tasks/tasksets
	 * @param force Whether to force reload (meaning ignore active/uninterruptable)
	 * @return The result,true if successful or false if something went wrong
	 */
    public boolean loadTasks(Path path, boolean clear, boolean force){				

		if( !force ){
			for( TaskSet set : tasksets.values() ){
				if( set.isActive() && !set.isInterruptable() ){
					Logger.tag("TASKS").info("Tried to reload sets while an interruptable was active. Reload denied.");
					return false;
				}
			}
		}
		if( clear )
			shutdownAndClearAll();

		int sets=0;
		int ts=0;
    	if( Files.exists(path) ) {
    		xmlPath = path;
			Document doc = XMLtools.readXML( path );
			
			String ref = path.getFileName().toString().replace(".xml", "");
			
			if( doc == null ) {
				Logger.tag(TINY_TAG).error("["+ id +"] Issue trying to read the "+ref
														+".xml file, check for typo's or fe. missing < or >");
				return false;
			}else{
				Logger.tag(TINY_TAG).info("["+ id +"] Tasks File ["+ref+"] found!");
			}

			rtvals.readFromXML(XMLfab.withRoot(path,"tasklist","rtvals"));

			var ssOpt = XMLtools.getFirstElementByTag( doc, "tasklist" );
			if(ssOpt.isEmpty()) {
				Logger.error("No valid taskmanager script, need the node tasklist");
				return false;
			}
			for( Element el : XMLtools.getChildElements( XMLtools.getFirstChildByTag( ssOpt.get(),"tasksets" ), "taskset" )){
				var description = XMLtools.getStringAttribute(el,"name","");
				if( description.isEmpty() )
					description = XMLtools.getStringAttribute(el,"info","");

				String tasksetID = el.getAttribute("id");
				String req = XMLtools.getStringAttribute(el,"req","");
				int reqIndex=-1;
				if( !req.isEmpty() ){
					for( int a=0;a<sharedChecks.size();a++ ){
						if( sharedChecks.get(a).matchesOri(req)){
							reqIndex=a;
						}
					}
					if( reqIndex==-1){
						var cb = CheckBlock.prepBlock(rtvals,req);
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
				String failure = el.getAttribute("failure");						
				int repeats = XMLtools.getIntAttribute(el, "repeat", 0);

				RUNTYPE run ;
				switch (el.getAttribute("run")) {
					case "step" -> run = RUNTYPE.STEP;
					case "no", "not" -> {
						run = RUNTYPE.NOT;
						repeats = 0;
					}
					default -> { // oneshot is the default
						run = RUNTYPE.ONESHOT;
						repeats = 0;
					}
				}
				TaskSet set = addTaskSet(tasksetID, description,  run, repeats, failure);
				set.setReqIndex( reqIndex );
				set.interruptable = XMLtools.getBooleanAttribute(el, "interruptable", true);

				sets++;
				for( Element ll : XMLtools.getChildElements( el )){
					addTaskToSet( tasksetID, new Task(ll, rtvals, sharedChecks) );
				}
			 }
			
			 for( Element tasksEntry : XMLtools.getChildElements( ssOpt.get(), "tasks" )){ //Can be multiple collections of tasks.
				for( Element ll : XMLtools.getChildElements( tasksEntry, "task" )){
					addTask(ll);
				 	ts++;
				}
			 }
			 Logger.tag(TINY_TAG).info("["+ id +"] Loaded "+sets+ " tasksets and "+ts +" individual tasks.");
			 return true;
    	}else {
				Logger.tag(TINY_TAG).error("["+ id +"] File ["+ path +"] not found.");
    		return false;
    	}
	}

	/**
	 * Add a blank TaskSet to this TaskManager
	 * @param id The id of the taskset
	 * @return True if successful
	 */
	public boolean addBlankTaskset( String id ){
		var fab = XMLfab.withRoot(xmlPath,"tasklist","tasksets");
		fab.addParentToRoot("taskset").attr("id",id).attr("run","oneshot").attr("name","More descriptive info");
		fab.addChild("task","Hello").attr("output","log:info").attr("trigger","delay:0s");
		return fab.build();
	}
	/* *********************************  C O M M A N D A B L E ***************************************************** */

	/**
	 * Reply to a question asked
	 * 
	 * @param request The question asked
	 * @param html Determines if EOL should be br or crlf
	 * @return The reply or unknown command if wrong question
	 */
	public String replyToCommand(String request, boolean html ){
		String[] parts = request.split(",");

		String cyan = html?"": TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		switch( parts[0] ){
			case "?":
				StringJoiner join = new StringJoiner("\r\n");
				join.add(green+"reload"+reg+" -> Reloads this TaskManager");
				join.add(green+"forcereload "+reg+"-> Reloads this TaskManager while ignoring interruptable");
				join.add(green+"listtasks/tasks "+reg+"-> Returns a listing of all the loaded tasks");
				join.add(green+"listsets/sets "+reg+"-> Returns a listing of all the loaded sets");
				join.add(green+"states/flags "+reg+"-> Returns a listing of all the current states & flags");
				join.add(green+"stop "+reg+"-> Stop all running task(set)s");
				join.add(green+"run,x "+reg+"-> Run taskset with the id x");
				return join.toString();
			case "reload":      return reloadTasks()?"Reloaded tasks...":"Reload Failed";
			case "forcereload": return forceReloadTasks()?"Reloaded tasks":"Reload failed";
			case "listtasks": case "tasks": return getTaskListing(html?"<br>":"\r\n");
			case "listsets": case "sets":  return getTaskSetListing(html?"<br>":"\r\n");
			case "setstate":
				if( parts.length!=2)
					return "To few arguments, need setstate,id:state";
				return changeState(parts[1])?"State changed":"state altered";
			case "stop":	  return "Cancelled "+stopAll("doTaskManager")+ " futures.";
			case "run":		
				if( parts.length==2){
					if( parts[1].startsWith("task:")){
						return startTask(parts[1].substring(5))?"Task started":"Failed to start task";
					}else{
						return startTaskset(parts[1]);
					}

				}else{
					return "not enough parameters";
				}
			default:
				return "unknown command: "+request;
		} 
	}
	/* ******************************************************************************************************* */
	public class ChannelRestoreChecker implements Runnable{
		@Override
		public void run() {
			if( !waitForRestore.isEmpty() ){				
				for( String channel : waitForRestore ){					
					if( streams.isStreamOk(channel) ){
						waitForRestore.remove(channel);						
						Logger.tag(TINY_TAG).info("'"+channel+"' restored, checking interval tasks");
						recheckIntervalTasks();
					}else{
						Logger.tag(TINY_TAG).info("'"+channel+"' not restored");
					}
				}
				if( !waitForRestore.isEmpty() ){
					scheduler.schedule(new ChannelRestoreChecker(), 10, TimeUnit.SECONDS);
				}
			}
		}
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		boolean res= (boolean) result;
		id = id.split(":")[1];
		var set = tasksets.get(id.substring(0,id.indexOf("_")));
		if( !res ){
			Logger.tag(TINY_TAG).error("Reply send failed (tasksetid_streamid) -> "+id);
			// somehow cancel the taskset?
			runFailure(tasksets.get(id.substring(0,id.indexOf("_"))),"confirmfailed");
		}else{
			Logger.tag(TINY_TAG).info("["+ this.id +"] Collector '"+id+"' finished fine");
			if( set.getRunType()==RUNTYPE.STEP)
				startTask( set.getNextTask( set.getLastIndexRun() ));
			// do the next step?
		}
		if(streams !=null){
			streams.removeConfirm(id);
		}
	}
}
