package util.task;

import com.email.EmailSending;
import com.stream.StreamPool;
import com.stream.collector.CollectorFuture;
import das.CommandReq;
import das.RealtimeValues;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.task.Task.*;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskManager implements CollectorFuture {

	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);// scheduler for the request data action
	ArrayList<Task> tasks = new ArrayList<>(); // Storage of the tasks
	Map<String, TaskSet> tasksets = new HashMap<>(); // Storage of the tasksets
	ArrayList<String> states = new ArrayList<>(); // Storage of the states/keywords
	Path xmlPath = null; // Path to the xml file containing the tasks/tasksets

	/* Queues for the different outputs */
	EmailSending emailQueue = null; // Reference to the email send, so emails can be send
	BlockingQueue<String[]> smsQueue = null; // Reference to the sms queue, so sms's can be send	
	StreamPool streampool; // Reference to the streampool, so sensors can be talked to
	RealtimeValues rtvals;

	CommandReq commandReq; // Source to get the data from nexus
	String id;

	static final String TINY_TAG = "TASK";

	enum RUNTYPE {
		ONESHOT, STEP, NOT
	} // Current ways of going through a taskset, either oneshot (all at once) or step
		// (task by task)

	String workPath = "";

	boolean startOnLoad = true;
	ArrayList<String> waitForRestore = new ArrayList<>();

	/* ****************************** * C O N S T R U C T O R **************************************************/

	public TaskManager(String id, RealtimeValues rtvals, CommandReq commandReq) {
		this.commandReq = commandReq;
		this.rtvals = rtvals;
		this.id = id;
	}
	public TaskManager(String id, Path xml ){
		this(id,null,null);
		this.xmlPath=xml;
	}
	public void setId(String id) {
		this.id = id;
	}

	public void setXMLPath(Path xml) {
		this.xmlPath = xml;
	}

	public void setWorkPath(String path) {
		this.workPath = path;
	}

	/* The different outputs */
	/**
	 * Adds the emailQueue to the manager so it can send emails
	 * @param emailQueue The queue of the EmailWorker
	 */
	public void setEmailSending(EmailSending emailQueue) {
		this.emailQueue = emailQueue;
	}

	/**
	 * Add the SMS queue to the TaskManager so SMS can be send
	 * 
	 * @param smsQueue The queue that the SMSWorker checks
	 */
	public void setSMSQueue(BlockingQueue<String[]> smsQueue) {
		this.smsQueue = smsQueue;
	}

	/**
	 * Add the Streampool to the TaskManager if it needs to interact with
	 * streams/channels
	 * 
	 * @param streampool The streampool to set
	 */
	public void setStreamPool(StreamPool streampool) {
		this.streampool = streampool;
	}

	public void setCommandReq(CommandReq commandReq) {
		this.commandReq = commandReq;
		this.rtvals = commandReq.getRealtimeValues();
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
		for (int index = 0; index < states.size(); index++) {
			if (states.get(index).startsWith(split[0] + ":")) {
				Logger.tag(TINY_TAG).info("[" + id + "] Changing state: " + states.get(index) + " to " + state);
				states.set(index, state);
				// State changed so we need to check if this affects anything?

				return true;
			}
		}
		Logger.tag(TINY_TAG).info("[" + id + "] New state added: " + state);
		states.add(state);
		states.trimToSize();
		return false;
	}

	/**
	 * Remove a state from the pool
	 * 
	 * @param id The identifier of the state
	 * @return True if it was actually removed
	 */
	public boolean removeState(String id) {

		id = id.split(":")[0]; // If it doesn't contain a ':' nothing wil change (meaning id will stay the
								// same)

		for (int a = 0; a < states.size(); a++) {
			if (states.get(a).startsWith(id + ":")) {
				states.remove(a);
				Logger.tag(TINY_TAG).info("[" + this.id + "] Removing state: " + id);
				states.trimToSize();
				return true;
			}
		}
		return false;
	}

	/**
	 * Check whether or not a state is active
	 * 
	 * @param id The state to check
	 * @return True if it's active
	 */
	public boolean checkState(String id) {
		if (id.isBlank() || id.equalsIgnoreCase("always"))
			return true;
		return states.contains(id);
	}

	/**
	 * Get a ; delimited list of all the current states/flags
	 * @return List of the states/flags
	 */
	public String getStatesListing() {
		StringJoiner b = new StringJoiner("; ");
		b.setEmptyValue("No states/flags yet");
		states.forEach(b::add);
		return b.toString();
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
	 * @return True if the addition was ok
	 */
	public Task addTask(Element tsk) {
		Task task = new Task(tsk);

		if (startOnLoad && task.getTriggerType()!=TRIGGERTYPE.EXECUTE) {
			startTask(task);
		}
		tasks.add(task);
		return task;
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
	 * @return The tasket if it was added or null if this failed because it already exists
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
		return null;
	}
	/* **************************** * WAYS OF ENABLING TASKS ***************************************************/
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

	public boolean startTask(String id) {
		for (Task task : this.tasks) {
			if (task.getID().equals(id)) {
				Logger.tag(TINY_TAG).info("[" + this.id + "] Task with id " + id + " started.");
				return startTask(task);//doTask(task);
			}
		}
		Logger.tag(TINY_TAG).info("[" + this.id + "] Task with id '" + id + "' not found.");
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
				Logger.tag(TINY_TAG).info("[" + this.id + "] Taskset started " + id);
				if (ts.getRunType() == RUNTYPE.ONESHOT) {
					startTasks(ts.getTasks());
					return "Taskset should be started: " + id;
				} else if (ts.getRunType() == RUNTYPE.STEP) {
					startTask(ts.getTasks().get(0));
					return "Started first task of taskset: " + id;
				} else {
					return "Didn't start anything...! Runtype=" + ts.getRunType();
				}
			} else {
				Logger.tag(TINY_TAG).info("[" + this.id + "] TaskSet " + ts.getDescription() + " is empty!");
				return "TaskSet is empty:" + id;
			}
		} else {
			Logger.tag(TINY_TAG).info("[" + this.id + "] Taskset with " + id+" not found");
			return "No such taskset:" + id;
		}
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
					task.future = scheduler.scheduleAtFixedRate(new DelayedControl(task), task.startDelay,
							task.interval, task.unit);
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
		Logger.tag(TINY_TAG).info("[" + id + "] Trying to start task of type: " + task.triggerType + "(" + task.value + ")");
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
				Logger.tag(TINY_TAG).info("[" + id + "] Scheduled sending '" + task.value + "' in "
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

			boolean executed = false;
			try {
				executed = doTask(task);
			} catch (Exception e) {
				Logger.tag(TINY_TAG).error("Nullpointer in doTask " + e);
			}

			TaskSet set = tasksets.get(task.getTaskset());

			if (executed) { // if it was executed
				if (task.triggerType == TRIGGERTYPE.RETRY) {
					switch (verifyRequirements(task, false)) { // If it meets the post requirements
						case 0: executed=false;
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
							executed=false;
							task.errorIncrement();
							break;
						default: Logger.error("Unexpected return from verify Requirement"); break;
					}
				} else if (task.triggerType == TRIGGERTYPE.WHILE || task.triggerType == TRIGGERTYPE.WAITFOR ) {
					task.attempts++;
					Logger.info("Attempts "+task.attempts+" of runs "+task.runs);
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
			if (!executed) { // If it wasn't executed
				boolean failure = true;
				if (task.triggerType == TRIGGERTYPE.WHILE) {
					Logger.tag(TINY_TAG).info("[" + id + "] " + set.getDescription() + " failed, cancelling.");
					task.reset();
				}else if (task.triggerType == TRIGGERTYPE.WAITFOR) {
					failure = false;
					Logger.info("Requirement not met, resetting counter");
					task.attempts = 0; // reset the counter
				}else if (task.triggerType == TRIGGERTYPE.RETRY || task.triggerType == TRIGGERTYPE.INTERVAL) {
					failure = false;
					if (task.runs == 0) {
						task.reset();
						task.runs=task.retries;
						Logger.tag(TINY_TAG).error("[" + id + "] Maximum retries executed, aborting!\t" + task.toString());
						if (!waitForRestore.contains(task.stream))
							waitForRestore.add(task.stream);

						scheduler.schedule(new ChannelRestoreChecker(), 15, TimeUnit.SECONDS);
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
		if( set==null)
			Logger.tag(TINY_TAG).error(id+" -> Tried to run failure for invalid taskset");
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
	private synchronized boolean doTask(Task task) {

		int ver = verifyRequirements(task, true); // Verify the pre req's
		if( ver == -1 ){
			task.errorIncrement();
			return false;
		}
		boolean verify = ver==1;
		boolean executed = false;
		
		if (task.skipExecutions == 0 // Check if the next execution shouldn't be skipped (related to the 'link'
										// keyword)
				&& task.doToday // Check if the task is allowed to be executed today (related to the 'link'
								// keyword)
				&& verify // Check if the earlier executed pre req checked out
				&& checkState(task.when)) { // Verify that the state is correct

			executed = true; // First checks passed, so it will probably execute

			if (task.triggerType == TRIGGERTYPE.WHILE || task.triggerType == TRIGGERTYPE.WAITFOR) { // Doesn't do anything, just check
				// Logger.tag(TINY_TAG).info("Trigger for while fired and ok")
				return true;
			}
			if (task.triggerType != TRIGGERTYPE.INTERVAL)
				Logger.tag(TINY_TAG).debug("[" + id + "] Requirements met, executing task with " + task.value);

			String response = "";
			String header = "DCAFS Message"; // Standard email header, might get overriden
			String fill = task.value; // The value of the task might need adjustments

			if (task.value.startsWith("taskset:") || task.value.startsWith("task:")) { // Incase the task starts a taskset
				String setid = task.value.split(":")[1];
				if (hasTaskset(setid)) {
					Logger.tag(TINY_TAG).info("[" + this.id + "] " + startTaskset(setid));
				} else if (!this.startTask(setid)) {
					Logger.tag(TINY_TAG).info("[" + this.id + "] No such task or taskset");
				}
			} else { // If not a supposed to run a taskset, check if any of the text needs to be
						// replaced
				if (task.value.contains("@")) { // Meaning there's an @ somewhere
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
				if (task.value.startsWith("cmd") || ( task.out != OUTPUT.MQTT && task.out != OUTPUT.STREAM && task.out != OUTPUT.MANAGER)) {
					// This is not supposed to be used when the output is a channel or no reqdata defined

					if (commandReq == null) {
						Logger.tag(TINY_TAG).warn("[" + id + "] No BaseReq (or child) defined.");
						return false;
					}
					if (task.out == OUTPUT.SYSTEM || task.out == OUTPUT.STREAM ) {
						response = commandReq.createResponse(fill.replace("cmd:", "").replace("\r\n", ""), null, true);
					} else if( task.out == OUTPUT.I2C ){
						response = commandReq.createResponse( "i2c:"+task.value, null, true);
					}else if( task.out == OUTPUT.EMAIL ){
						if( splits.length==2){
							String it = splits[1]+(splits[1].contains(":")?"":"html");
							response = commandReq.createResponse( it, null, true);
						}
					}else if( task.out != OUTPUT.LOG ){
						String it = splits[splits.length - 1]+(splits[splits.length - 1].contains(":")?"":"html");
						response = commandReq.createResponse( it, null, true);
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
					case SMS: // Send the response in an SMS
						if (smsQueue == null) {
							Logger.tag(TINY_TAG).error("[" + id + "] Task not executed because no valid smsQueue");
							return false;
						}
						String sms = splits[splits.length - 1];
						smsQueue.add(new String[] { task.outputRef, sms.length() > 150 ? sms.substring(0, 150) : sms });
						break;
					case EMAIL: // Send the response in an email
						if (emailQueue == null) {
							Logger.tag(TINY_TAG).error("[" + id + "] Task not executed because no valid emailQueue");
							return false;
						}
						response = "<html>" + response.replace("\r\n", "<br>") + "</html>";
						response = response.replace("<html><br>", "<html>");
						response = response.replace("\t", "      ");
						for (String item : task.outputRef.split(";")) {
							if( splits.length==1)
								response = "";
							emailQueue.sendEmail(item,header,response,task.attachment,false);
						}
						break;
					case STREAM: // Send the value to a device
						if (streampool == null) { // Can't send anything if the streampool is not defined
							Logger.tag(TINY_TAG).error("[" + id + "] Wanted to output to a stream (" + task.stream
									+ "), but no StreamPool defined.");
							return false;
						}

						if (!streampool.isStreamOk(task.stream, true)) { // Can't send anything if the channel isn't
																			// alive
							Logger.tag(TINY_TAG).error("[" + id + "] Wanted to output to a stream (" + task.stream
									+ "), but channel not alive.");
							task.writable = null;
							return false;
						}
						boolean ok;
						if (task.triggerType != TRIGGERTYPE.INTERVAL) { // Interval tasks are executed differently
							if( !task.reply.isEmpty() ){
								ok=streampool.writeWithReply(this, task.getTaskset(), task.stream, task.value, task.reply);
							}else{
								ok=!streampool.writeToStream(task.stream, fill, task.reply).isEmpty();
							}
						} else {							
							if( !task.reply.isEmpty() ){
								ok=streampool.writeWithReply(this, task.getTaskset(), task.stream, task.value, task.reply);
							}else{
								if( task.writable == null ){
									var res =streampool.writeToStream(task.stream, fill, task.reply);
									if( res.isEmpty() ) {
										ok = false;
									}else{
										if( !task.value.contains("@"))
											task.value=res;
										ok = true;
									}
									streampool.getWritable(task.stream).ifPresent( task::setWritable );
								}else{
									ok=task.writable.writeLine( fill );
								}
							}
						}
						if( !ok ){
							Logger.error("Something wrong (ok=false)...");
							return false;
						}
						break;
					case MQTT:	
					/*					
						MqttWorker mqtt = reqData.das.mqttWorkers.get( task.outputRef );
						String[] tosend = task.txt.split(";");											
						if (tosend.length == 2){
							if( NumberUtils.isCreatable(tosend[1])){
								mqtt.addWork( new MqttWork( tosend[0],tosend[1] ) );
							}else{
								double val = rtvals.getRealtimeValue(tosend[1],Double.NaN);
								if( !Double.isNaN(val)){
									mqtt.addWork( new MqttWork( tosend[0],val ) );
								}else{
									Logger.tag(TINY_TAG).error("Non existing rtvals requested: "+tosend[1]);
								}
							}
						}
						*/
						break;
					case LOG: // Write the value in either status.log or error.log (and maybe send an email)
						String[] spl = task.value.split(";");
					
						String device = "none";
						String mess = task.value;

						if( spl.length == 2 ){
							device = spl[0];
							mess = spl[1];
						}
						switch( task.outputRef ){
							case "info":	// Just note the info
								Logger.tag(TINY_TAG).info("["+ id +"] "+device+"\t"+mess);
							break;
							case "warn":	// Note the warning
								Logger.tag(TINY_TAG).warn("TaskManager.reportIssue\t["+ id +"] "+device+"\t"+mess);
							break;
							case "error":	// Note the error and send email								
								Logger.tag(TINY_TAG).error("TaskManager.reportIssue\t["+ id +"] "+device+"\t"+mess);
								emailQueue.sendEmail( "admin", device+" -> "+mess, "", "", false);
							break;
							default: Logger.error("Tried to use unknown outputref: "+task.outputRef); break;
						}
						break;
					case MANAGER:
						String[] com = task.value.split(":");
						if( com.length != 2 ){
							break;
						}

						switch( com[0] ){
							case "raiseflag": changeState( com[1]+":1" ); break;
							case "lowerflag": removeState( com[1] );break;
							case "start": startTaskset(com[1]); break;
							case "stop": 
								int a = stopTaskSet( com[1] );
								if( a == -1 ){
									Logger.tag(TINY_TAG).warn("Failed to stop the taskset '"+com[1]+"' because not found");
								}else{
									Logger.tag(TINY_TAG).info("Stopped the tasket '"+com[1]+"' and "+a+" running tasks.");
								}
								break;
							default:
								TaskSet set = this.tasksets.get(com[0].toLowerCase());
								if( set != null){
									Optional<Task> t = set.getTaskByID(com[1].toLowerCase());							
									if( t.isPresent() && this.startTask( t.get() ) ){
										Logger.tag(TINY_TAG).info("Started Managed task:"+com[1]);
									}
								}else{
									Logger.tag(TINY_TAG).warn("Failed to start managed taskset, not found: "+com[0]);
								}
							break;
						}
						break;
					case I2C:
						break;
					case SYSTEM: // Same as log:info
					default:
						if( task.triggerType != TRIGGERTYPE.INTERVAL){
							if( response.endsWith("\r\n") )
								response = response.substring(0,response.length()-2);
							Logger.tag(TINY_TAG).info("["+ id +"] " + response );
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
									scheduler.schedule(new Cleanup(t), 24, TimeUnit.HOURS);
									break;
								case NOT_TODAY: // Linked task won't be allowed to be executed again today
									t.doToday=false;
									Logger.tag(TINY_TAG).info( "["+ id +"] Linked task disabled till midnight.");
									scheduler.schedule(new Cleanup(t), TimeTools.secondsToMidnight(), TimeUnit.SECONDS);
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
			if( !task.doToday ) {
				Logger.tag(TINY_TAG).info( "["+ id +"] Not executing "+task.value +" because not allowed today.");
			}
			if( task.triggerType != TRIGGERTYPE.INTERVAL && task.triggerType != TRIGGERTYPE.RETRY){
				// UNCOMMENT FOR DEBUG PURPOSES
				if( !verify ) {
					switch( task.verifyType) {
						case AND:
						case OR:
							Logger.tag(TINY_TAG).warn(""+task.id+"/"+task.value+" => not executed, requirement not met.");
							Logger.tag(TINY_TAG).warn( printVerify(task.preReq1) );
							Logger.tag(TINY_TAG).warn( task.verifyType);
							Logger.tag(TINY_TAG).warn( printVerify(task.preReq2) );
							break;
						case SINGLE:
							Logger.tag(TINY_TAG).warn("["+id+"] "+task.id+"/"+task.value+" => not executed, requirement not met.");
							Logger.tag(TINY_TAG).warn( "["+id+"] Reason: "+printVerify(task.preReq1) );
							break;
						case NONE:
						default:
							break;
					}
				}else {	
					Logger.tag(TINY_TAG).info( "["+ id +"] Requirement not met...? Skip Execs: " + (task.skipExecutions==0) );
					if( task.skipExecutions!=0)
						Logger.tag(TINY_TAG).info( "["+ id +"] Not executed because execution needed to be skipped");
					if( !checkState(task.when) )
						Logger.tag(TINY_TAG).info( "["+ id +"] Not executed because incorrect 'when'" );
				}
			}
		}
		if( task.value.startsWith("taskset:") || task.value.startsWith("task:")) {
			String shortName = task.value.split(":")[1];
			Logger.tag(TINY_TAG).info( "["+ id +"] "+shortName+" -> "+executed+"\r\n");
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
				if( emailQueue != null) {
					emailQueue.sendEmail("admin",  "Failed to reschedule task", task.toString() );
				}else {
					Logger.tag(TINY_TAG).error( "["+ id +"] Failed to reschedule task "+ task);
				}
			}
		}
		if( task.hasNext() ) { // If the next task in the set could be executed
			TaskSet set = tasksets.get( task.getTaskset() );					
			if( set != null && set.getRunType() == RUNTYPE.STEP ) {	// If the next task in the set should be executed
				if( executed && task.triggerType != TRIGGERTYPE.RETRY){ // If the task was executed and isn't of the trigger:retry type
					Task t = set.getNextTask( task.getIndexInTaskset() );	
					if( t != null ){
						startTask( t );
					}else{
						Logger.tag(TINY_TAG).info( "["+ id +"] Executed last task in the taskset ["+set.getDescription()+"]");
					}
				}
			}
		}
		return executed;
	}

	/* **************************************** UTILITY *******************************************************/
	/**
	 * Checks if a taskset with the given shortname exists
	 * @param shortname Shortname to look for
	 * @return TRue if it exists
	 */
	public boolean tasksetExists( String shortname ){
		return this.tasksets.get(shortname)!=null;
	}

	/**
	 * Get a listing of all the managed tasks
	 * @param newline Characters to use for EOL
	 * @return A string with the toString result of the tasks
	 */
	public String getTaskListing( String newline ){
		if( tasks.isEmpty() )
			return "Task list empty."+newline;
			
		StringJoiner b = new StringJoiner(newline,"-Runnable Tasks-"+newline,newline+newline);
		StringJoiner intr = new StringJoiner(newline,"-Interval Tasks-"+newline,newline+newline);
		StringJoiner clk = new StringJoiner(newline,"-Clock Tasks-"+newline,newline+newline);
		StringJoiner other = new StringJoiner(newline,"-Other Tasks-"+newline,newline+newline);

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
	 * @param newline Characters to use for EOL
	 * @return A string with the toString result of the taskset
	 */
	public String getTaskSetListing( String newline ){
		if( tasksets.isEmpty() ){
			return "No tasksets yet."+newline;
		}
		StringBuilder b = new StringBuilder("-Task sets-");
		b.append(newline);
		for( TaskSet set : tasksets.values()){
			b.append((char) 27).append("[31m").append(set.getID()).append(" ==> ").append(set.getDescription()).append("(")
					.append(set.getTasks().size()).append(" items)").append("Run type:").append(set.getRunType()).append(newline);
			for( Task s : set.getTasks() ) {
				b.append((char) 27).append("[33m").append("\t-> ").append( s.toString()).append(newline);
			}			
		}
		return b.toString();
	}
	/**
	 * Calculate the seconds till the next execution of the task
	 * @param c The task to calculate the time for
	 * @return The time in seconds till the next execution
	 */
	private long calculateSeconds( Task c ){		

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);        
		if( !c.utc )
			now = LocalDateTime.now();

		LocalDateTime taskTime = now.with(c.time); // Use the time of the task
		taskTime=taskTime.plusNanos(now.getNano());

		//Logger.tag(TINY_TAG).info( "Comparing:" +taskTime.toString())
		//Logger.tag(TINY_TAG).info( "Now:"+now.toString())

        if( taskTime.isBefore(now.plusNanos(100000)) ) // If already happened today
            taskTime=taskTime.plusDays(1);

        if( c.runNever() ){ // If the list of days is empty
            return -1;
        }    
        int x=0;
        while( !c.runOnDay(taskTime.getDayOfWeek()) ){
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

		String[] ipmac = line.split("@");
		for( int a=1;a<ipmac.length;a++){
			String result="";
			if( ipmac[a].startsWith("mac:")){				
				result = Tools.getMAC( ipmac[a].substring(4) );
			}else if( ipmac[a].startsWith("ipv4:")){
				result = Tools.getIP(ipmac[a].substring(5),false);				
			}else if( ipmac[a].startsWith("ipv6:")){
				result = Tools.getIP(ipmac[a].substring(5),false);
			}
			if( result.isBlank() ){
				Logger.tag("TASKS").error("Couldn't find result for "+ipmac[a]);
			}else{
				line = line.replace(ipmac[a],result);
			}
		}		
    	line = line.replace("[EOL]", "\r\n");
    	return line;
    }
    /* **********************************  V E R I F Y * *****************************************************/
	/**
	 * Check all the requirement in a task
	 * @param task The task to check
	 * @param pre True if checking the pre checks and false for the post
	 * @return 1=ok,0=nok,-1=error
	 */
	private int verifyRequirements( Task task, boolean pre ) {
		Verify first = task.preReq1;
		Verify second = task.preReq2;
		VERIFYTYPE verify = task.verifyType;
		if( !pre ){
			first = task.postReq1;
			second = task.postReq2;
			verify=task.postReqType;
		}
		int ver1 = verifyRequirement(first);
		int ver2 = verifyRequirement(second);
		
		if(ver1==-1){
			task.badReq=first.reqRef[0]+" "+first.reqRef[2];
			return -1;
		}
		if( ver2==-1){
			task.badReq=second.reqRef[0]+" "+second.reqRef[2];
			return -1;
		}
			
		switch(verify) {
			case AND: return (ver1 + ver2==2)?1:0;			
			case OR:  return (ver1 + ver2>0)?1:0;
			case SINGLE: return ver1;
			case NONE: 
			default:
				return 1;		
		}
	}
	/**
	 * Single verify check
	 * @param verify The verify to check
	 * @return 1=ok,0=nok,-1=error
	 */
	private int verifyRequirement( Verify verify ) {
		if( rtvals == null ){ // If the verify can't be checked, return false
			Logger.tag(TINY_TAG).info("["+ id +"] Couldn't check because no RealtimeValues defined.");
			return 0;
		}
		if( verify==null )
			return 0;

		if( verify.reqtype==REQTYPE.NONE) // If there isn't an actual verify, return true
			return 1;
		
		double[] val = {0,0,0};
		
		if(verify.mathtype==MATHTYPE.NONE) {
			for( int a=0;a<2;a++) {
				// Check if a ref is given, if not it's a value if so get the value associated
				val[a] = getVerifyValue( verify.reqRef[a], verify.reqValue[a],-1234.4321);
				if( val[a] == -1234.4321 ) {
					return -1;
				}
			}
			switch( verify.reqtype ) {
				case EQUAL:				return val[0] == val[1]?1:0;
				case LARGER:			return val[0] >  val[1]?1:0;
				case LARGER_OR_EQUAL: 	return val[0] >= val[1]?1:0;
				case SMALLER:			return val[0] <  val[1]?1:0;
				case SMALLER_OR_EQUAL:	return val[0] <= val[1]?1:0;
				case NONE:
				default:	return 0;		
			}
		}else{
			for( int a=0;a<3;a++) {
				val[a] =verify.reqRef[a].isBlank()?verify.reqValue[a]:rtvals.getRealtimeValue(verify.reqRef[a],-1234.4321);
				if( val[a] == -1234.4321 ) {
					return -1;
				}
			}
			double result=0;	
			switch(verify.mathtype) {
				case DIFF: result = Math.abs(val[0]-val[1]);break;
				case MINUS:result = val[0]-val[1];break;
				case PLUS: result = val[0]+val[1];break;
				default:
					break;
			}
			switch( verify.reqtype ) {
				case EQUAL:				return result == val[2]?1:0;
				case LARGER:			return result >  val[2]?1:0;
				case LARGER_OR_EQUAL: 	return result >= val[2]?1:0;
				case SMALLER:			return result <  val[2]?1:0;
				case SMALLER_OR_EQUAL:	return result <= val[2]?1:0;
				case NONE:
				default:	return 0;		
			}
		}
	}
	/**
	 * Create a readable string based on the verify
	 * @param verify The verify to make the string from
	 * @return The string representation of the verify
	 */
	public String printVerify(Verify verify) {
		if( rtvals == null )
			return "No ReqData defined!";

		StringJoiner join = new StringJoiner(" ");
		if( verify.mathtype== MATHTYPE.NONE) {
			join.add( verify.reqRef[0]+" -> "+getVerifyValue(verify.reqRef[0],verify.reqValue[0],-1234.4321));
			join.add( ""+verify.reqtype);
			if( verify.reqRef[1].isBlank() ) {
				join.add( ""+verify.reqValue[1]);
			}else {
				join.add( verify.reqRef[1]+"->"+getVerifyValue(verify.reqRef[1],verify.reqValue[1],-1234.4321));
			}
			join.add("=> "+this.verifyRequirement(verify));
		}else {
			for( int a=0;a<3;a++) {
				if( verify.reqValue[a] ==-999) {
					join.add( verify.reqRef[a]+" ("+getVerifyValue(verify.reqRef[a],verify.reqValue[a],-1234.4321)+")");
				}else {
					join.add( ""+verify.reqValue[a]);
				}
				switch(a) {
					case 0: join.add( ""+verify.mathtype); break;
					case 1: join.add( ""+verify.reqtype); break;
					case 2: join.add("=> "+this.verifyRequirement(verify)); break;
					default:break;
				}				
			}
		}
		return join.toString();
	}
	private double getVerifyValue( String ref, double value, double bad ){
		if( ref.isBlank() ){
			return value;
		}else if( ref.startsWith("flag") ){
			String flag = ref.split(":")[1];
			return this.checkState(flag+":1")?1:0;
		}else if( ref.startsWith("issue") ){
			String issue = ref.split(":")[1];
			return commandReq.checkIssue(issue)?1:0;
		}else{
			return rtvals.getRealtimeValue(ref,bad);
		}
	}
	/* *******************************************************************************************************/
	/**
	 * If a task is prohibited of execution for a set amount of time, this is scheduled after that time.
	 * This restores execution.
	 */
	public static class Cleanup implements Runnable {
		Task task;		
		
		public Cleanup( Task c){
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
	public void clearAll(){
		//Cancel all running tasks...
		scheduler.shutdownNow();
		tasksets.clear();
		tasks.clear();
		scheduler = Executors.newSingleThreadScheduledExecutor();
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
	 * @param clear Whether or not to remove the existing tasks/tasksets
	 * @param force Whether or not to force reload (meaning ignore active/uninterruptable)
	 * @return The result,true if successful or false if something went wrong
	 */
    public boolean loadTasks(Path path, boolean clear, boolean force){				

		if( !force ){
			for( TaskSet set : tasksets.values() ){
				if( set.isActive() && !set.isInterruptable() ){
					Logger.tag("TASKS").info("Tried to reload sets while an interuptable was active. Reload denied.");
					return false;
				}
			}
		}
		if( clear )
			this.clearAll();

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

			Element ss = XMLtools.getFirstElementByTag( doc, "tasklist" );
			if( ss==null) {
				Logger.error("No valid taskmanager script, need the node tasklist");
				return false;
			}
			for( Element el : XMLtools.getChildElements( XMLtools.getFirstChildByTag( ss,"tasksets" ), "taskset" )){
				var description = XMLtools.getStringAttribute(el,"name","");
				if( description.isEmpty() )
					description = XMLtools.getStringAttribute(el,"info","");

				String tasksetID = el.getAttribute("id");
				String failure = el.getAttribute("failure");						
				int repeats = XMLtools.getIntAttribute(el, "repeat", 0);

				RUNTYPE run ;				
				switch( el.getAttribute("run") ) {
					case "step": 	
						run = RUNTYPE.STEP;
						break;
					case "no":case "not":
						run=RUNTYPE.NOT;
						repeats=0;
						break;
					default: 
					case "oneshot":
						run = RUNTYPE.ONESHOT;	
						repeats=0;	
						break;
				}
				TaskSet set = addTaskSet(tasksetID, description,  run, repeats, failure);
				set.interruptable = XMLtools.getBooleanAttribute(el, "interruptable", true);

				sets++;
				for( Element ll : XMLtools.getChildElements( el )){
					addTaskToSet( tasksetID, new Task(ll) );
				}
			 }
			
			 for( Element tasksEntry : XMLtools.getChildElements( ss, "tasks" )){ //Can be multiple collections of tasks.
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
	public boolean addBlankTaskset( String id ){
		var fab = XMLfab.withRoot(xmlPath,"tasklist","tasksets");
		fab.addParent("taskset").attr("id",id).attr("run","oneshot").attr("name","More descriptive info");
		fab.addChild("task","Hello").attr("output","log:info").attr("trigger","delay:0s");
		return fab.build()!=null;
	}
	/* *********************************  R E Q U E S T  R E P L Y  ********************************************/

	/**
	 * Reply to a question asked
	 * 
	 * @param request The question asked
	 * @param html Determines if EOL should be br or crlf
	 * @return The reply or unknown command if wrong question
	 */
	public String replyToCmd(String request, boolean html ){
		String[] parts = request.split(",");

		switch( parts[0] ){
			case "reload":case "reloadtasks":     return this.reloadTasks()?"Reloaded tasks...":"Reload Failed";
			case "forcereload": return this.forceReloadTasks()?"Reloaded tasks":"Reload failed";
			case "listtasks": case "tasks": return getTaskListing(html?"<br>":"\r\n");
			case "listsets": case "sets":  return getTaskSetListing(html?"<br>":"\r\n");
			case "states": case "flags":    return getStatesListing();
			case "stop":	  return "Cancelled "+this.stopAll("doTaskManager")+ " futures.";
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
			case "?": 
				StringJoiner join = new StringJoiner("\r\n");
				join.add("reload/reloadtasks -> Reloads this TaskManager");
				join.add("forcereload -> Reloads this TaskManager while ignoring interruptable");
				join.add("listtasks/tasks -> Returns a listing of all the loaded tasks");
				join.add("listsets/sets -> Returns a listing of all the loaded sets");
				join.add("states/flags -> Returns a listing of all the current states & flags");
				join.add("stop -> Stop all running tasks and tasksets");
				join.add("run,x -> Run taskset with the id x");
				return join.toString();
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
					if( streampool.isStreamOk(channel) ){
						waitForRestore.remove(channel);						
						Logger.tag(TINY_TAG).info("'"+channel+"' restored, checking interval tasks");
						recheckIntervalTasks();
					}else{
						Logger.tag(TINY_TAG).info("'"+channel+"' not restored");
					}
				}
				if( !waitForRestore.isEmpty() ){
					scheduler.schedule(new ChannelRestoreChecker(), 10, TimeUnit.SECONDS);
					//Logger.tag(TINY_TAG).info("Rescheduled restore check.")
				}
			}
		}
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		// Ok isn't implemented because the manager doesn't care if all went well
		boolean res= (boolean) result;
		id = id.split(":")[1];
		if( !res ){
			Logger.tag(TINY_TAG).error("Reply send failed (tasksetid_streamid) -> "+id);
			// somehow cancel the taskset?
			runFailure(tasksets.get(id.substring(0,id.indexOf("_"))),"confirmfailed");
		}else{
			Logger.tag(TINY_TAG).info("["+ this.id +"] Collector '"+id+"' finished fine");
		}
		if(streampool!=null){
			streampool.removeConfirm(id);
		}
	}
}
