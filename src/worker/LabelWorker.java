package worker;

import com.stream.Readable;
import com.stream.Writable;
import das.BaseReq;
import das.RealtimeValues;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.DeadThreadListener;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed. 
 *
 * @author Michiel TJampens @vliz
 */
public class LabelWorker implements Runnable, Labeller {

	Map<String, Method> nmeaMetMap = null;
	Map<String, Method> regMetMap = null;

	Map<String, Long[]> lastUsage = new HashMap<>();
	Map<String, Generic> generics = new HashMap<>();
	Map<String, ValMap> mappers = new HashMap<>();
	Map<String, Writable> writables = new HashMap<>();
	Map<String, Readable> readables = new HashMap<>();

	protected BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();      // The queue holding raw data for processing
	private boolean goOn = true; // General process boolean, clean way of stopping thread

	protected BaseReq reqData;

	protected boolean debugMode = false;

	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();
	protected boolean printData = false;
	long waitingSince;
	protected RealtimeValues rtvals;

	protected DeadThreadListener listener;

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<Runnable>());

	ScheduledExecutorService debug = Executors.newSingleThreadScheduledExecutor();

	public enum FAILreason { // Used by the nmea processing to return the result
		NONE, PRIORITY, LENGTH, SYNTAX, INVALID, TODO, IGNORED, DISABLED, EMPTY
	}

	// Debug help
	int readCount=0;
	int oldReadCount=0;
	String lastOrigin="";
	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 *
	 * @param dQueue The queue to use
	 */
	public LabelWorker(BlockingQueue<Datagram> dQueue) {
		this();
		this.dQueue = dQueue;
		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(new SelfCheck(),5,30,TimeUnit.MINUTES);
	}

	/**
	 * Constructor to use its own queue
	 */
	public LabelWorker() {
		getMethodMapping(this.getClass());
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
	 * Set the BaseReq for this worker to use
	 *
	 * @param baseReq The default BaseReq or extended one
	 */
	public void setReqData(BaseReq baseReq) {
		this.reqData = baseReq;
	}

	/**
	 * Set the realtimevalues for the worker to use
	 *
	 * @param rtvals The default RealtimeValues or extended one
	 */
	public void setRealtimeValues(RealtimeValues rtvals) {
		this.rtvals = rtvals;
	}


	/* *************************** VALMAPS **********************************************/
	public ValMap addValMap(ValMap map) {
		mappers.put(map.getID(), map);
		Logger.info("Added generic " + map.getID());
		return map;
	}

	public void clearValMaps() {
		mappers.clear();
	}

	public ValMap addValMap(String id) {
		ValMap map = new ValMap("");
		mappers.put(id, map);
		return map;
	}

	public String getValMapsInfo() {
		StringJoiner join = new StringJoiner("\r\n", "Valmaps:\r\n", "\r\n");
		for (ValMap map : mappers.values()) {
			join.add(map.toString() + "\r\n");
		}
		return join.toString();
	}

	/* *************************** GENERICS **********************************************/
	public Generic addGeneric(Generic gen) {
		generics.put(gen.getID(), gen);
		Logger.info("Added generic " + gen.getID());
		return gen;
	}

	public void clearGenerics() {
		generics.clear();
	}

	public Generic addGeneric(String id) {
		Generic gen = new Generic("");
		generics.put(id, gen);
		return gen;
	}

	public String getGenericInfo() {
		StringJoiner join = new StringJoiner("\r\n", "Generics:\r\n", "\r\n");
		for (Generic gen : generics.values()) {
			join.add(gen.toString() + "\r\n");
		}
		return join.toString();
	}

	/* ******************************** Q U E U E S **********************************************/

	/**
	 * Get the queue for adding work for this worker
	 *
	 * @return The qeueu
	 */
	public BlockingQueue<Datagram> getQueue() {
		return dQueue;
	}

	/**
	 * Set the queue for adding work for this worker
	 *
	 * @param d The queue
	 */
	public void setQueue(BlockingQueue<Datagram> d) {
		this.dQueue = d;
	}

	/**
	 * Clear this workers queue
	 */
	public void clearQueue() {
		dQueue.clear();
	}
	/* ******************************** I N V O K E **********************************************/

	/**
	 * Get all the relevant methods available in this (extended) class
	 *
	 * @param baseworker THe class to look in
	 */
	public void getMethodMapping(Class<?> baseworker) {

		if (nmeaMetMap == null) {
			nmeaMetMap = new HashMap<>();
			regMetMap = new HashMap<>();

			ArrayList<Method> methods = new ArrayList<>(Arrays.asList(baseworker.getDeclaredMethods()));

			if (baseworker.getSuperclass() == LabelWorker.class) {
				methods.addAll(Arrays.asList(baseworker.getSuperclass().getDeclaredMethods()));
			}
			for (Method method : methods) {
				String com = method.getName();
				if (com.contains("NMEA")) {
					com = com.substring(com.indexOf("NMEA") + 4);
					if (com.equals("time")) {
						nmeaMetMap.put("me_", method);
					} else {
						nmeaMetMap.put(com, method);
					}
				} else if (com.length() >= 4 && com.startsWith("do")) {
					com = com.substring(2); //Remove the 'do'
					regMetMap.put(com.toLowerCase(), method);
				}
			}
			Logger.info("Found " + nmeaMetMap.size() + " doNMEA and " + regMetMap.size() + " other ones.");
		}
	}

	/* *******************************************************************************************/
	@Override
	public void run() {

		if (this.reqData == null) {
			Logger.error("Not starting without proper BaseReq");
			return;
		}
		while (goOn) {
			try {
				Datagram d = dQueue.take();
				readCount++;

				if (d == null) {
					Logger.error("Invalid datagram received");
					continue;
				}
				lastOrigin=d.originID;
				if (d.label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}
				d.label = d.label.toLowerCase();

				String readID = d.label.substring(d.label.indexOf(":")+1);

				if (d.label.startsWith("generic:")) {
					executor.execute(new ProcessGeneric(d));
				} else if (d.label.startsWith("nmea")) {
					executor.execute(new ProcessNMEA(d));
				} else if (d.label.startsWith("valmap")) {
					executor.execute(new ProcessValmap(d));
				} else if (d.label.startsWith("rtval:")) {
					executor.execute(() -> storeRtval(readID,d.getData(),d.getOriginID()));
				} else if (d.label.startsWith("rttext:")) {
					executor.execute(() -> rtvals.setRealtimeText(readID,d.data));
				} else if (d.label.startsWith("read:")) {
					if( d.getWritable()!=null){
						if (d.label.split(":").length >= 2) {

							var read = readables.get(readID);
							if( read != null && !read.isInvalid()){
								read.addTarget(d.getWritable());
								Logger.info("Added "+d.getWritable().getID()+ " to target list of "+read.getID());
							}else{
								Logger.error(d.getWritable().getID()+" asked for data from "+readID+" but doesn't exists (anymore)");
							}
							readables.entrySet().removeIf( entry -> entry.getValue().isInvalid());
						}else{
							Logger.error("No id given for the wanted readable from "+d.getWritable().getID());
						}
					}else{
						Logger.error("No valid writable in the datagram from "+d.getOriginID());
					}
				} else {
					switch (d.label) {
						case "readable":
							if(  d.getReadable()!=null)
								readables.put(d.getReadable().getID(),d.getReadable());
							readables.entrySet().removeIf( entry -> entry.getValue().isInvalid()); // cleanup
							break;
						case "test":
							executor.execute(() -> Logger.info(d.originID + "|" + d.label + " -> " + d.getData()));
							break;
						case "email":
							executor.execute(() -> reqData.emailResponse(d));
							break;
						case "system":
							executor.execute(() -> reqData.createResponse(d.getData(), d.getWritable(), false));
							break;
						case "void":
							break;
						default:
							executor.execute(new ProcessDatagram(d));
							break;
					}
				}
				int proc = procCount.get();
				if (proc >= 500000) {
					procCount.set(0);
					long millis = Instant.now().toEpochMilli() - procTime;
					procTime = Instant.now().toEpochMilli();
					Logger.info("Processed " + (proc / 1000) + "k lines in " + TimeTools.convertPeriodtoString(millis, TimeUnit.MILLISECONDS));
				}
			} catch (InterruptedException  e) {
				Logger.error(e);
			}catch( RejectedExecutionException e){
				Logger.error(e.getMessage());
			} catch( Exception e){
				Logger.error(e);

			}
		}
		listener.notifyCancelled("BaseWorker");
	}
	private void storeRtval( String param, String data, String origin ){
		try{
			var val = NumberUtils.toDouble(data,Double.NEGATIVE_INFINITY);
			if( val == Double.NEGATIVE_INFINITY && NumberUtils.isCreatable(data)){
				val = NumberUtils.createInteger(data);
			}
			if( val != Double.NEGATIVE_INFINITY){
				rtvals.setRealtimeValue(param,val);
			}else{
				Logger.warn("Tried to convert "+data+" from "+origin+" to an rtval...");
			}
		}catch( NumberFormatException e ){
			Logger.warn("Tried to convert "+data+" from "+origin+" to an rtval...");
		}
	}

	/**
	 * The main processing method for this class
	 */
	private class ProcessNMEA implements Runnable {
		Datagram d;

		public ProcessNMEA(Datagram d) {
			this.d = d;
		}

		public void run() {

			if (MathUtils.doNMEAChecksum(d.getData())) {// Either checksum was ok or test was skipped
				String[] split = d.getData().substring(0, d.getData().length() - 3).split(",");// remove the checksum and split the nmea string in its components
				FAILreason fail = FAILreason.IGNORED;

				try {
					String nmea = split[0].substring(3).toLowerCase();
					if (nmeaMetMap.get(nmea) != null) {
						/* Keep track of method usage *********************************************/
						Long[] times = lastUsage.get(nmea);
						if (times == null) {
							times = new Long[2];
							times[1] = Instant.now().toEpochMilli();
						}
						times[0] = times[1];
						times[1] = Instant.now().toEpochMilli();

						lastUsage.put(nmea, times);
						/* *************************************************************************/
						fail = (FAILreason) nmeaMetMap.get(nmea).invoke(LabelWorker.this, split, d.priority);
					} else {
						Logger.error("Can't process the message: " + d.getData());
					}
				} catch (IllegalAccessException | IllegalArgumentException e) {
					Logger.error("Something wrong in the processing method for: " + d.getData() + " -> " + e.getMessage());
				} catch (java.lang.NullPointerException np) {
					Logger.error("Nullpointer when processing: " + d.getData());
				} catch (InvocationTargetException e) {
					Throwable originalException = e.getTargetException();
					Logger.error("'" + originalException + "' at " + originalException.getStackTrace()[0].toString() + " when processing: " + d.getData());
				}

				switch (fail) {
					case NONE:
						break; // Most common result
					case SYNTAX:// The process failed because of a syntax error, eg. the string doesn't contain the right amount of parts
						Logger.warn("Bad NMEA String Syntax: " + d.getData());
						break;
					case LENGTH:// The string was longer or shorter then expected
						Logger.warn("Bad NMEA String Length: " + d.getData() + " (length:" + split.length + ") from " + d.getOriginID());
						break;
					case PRIORITY:
									/* The datagram didn't have the right priority to use it (highest priority = 1,
									   if no messages of 1 are received  it becomes 2 etc)
									   Logger.debug("SKIPPED (priority): " +  d.getMessage())*/
						break;
					case INVALID:
						Logger.warn("Invalid NMEA String: " + d.getData(), true);
						break;
					case TODO:// A NMEA string was received that hasn't got
						// any processing code yet.
						Logger.warn("TODO: " + d.priority + "\t" + d.getData() + "\t From:" + d.getOriginID());
						break;
					case IGNORED: // For some reason the string was ignored
					case DISABLED:
					case EMPTY: // Nothing to report
						break;
				}
			} else {
				if (d.getData().length() > 250)
					d.setData(d.getData().substring(0, 250));
				Logger.warn("NMEA message from " + d.getOriginID() + " failed checksum " + d.getData().replace("\r", "<cr>").replace("\n", "<lf>"));
			}
			procCount.incrementAndGet();
		}
	}

	private class ProcessDatagram implements Runnable {
		Datagram d;

		public ProcessDatagram(Datagram d) {
			this.d = d;
		}

		public void run() {
			try {
				String what = d.label.toLowerCase().split(":")[0];
				/* Keep track of method usage *********************************************/
				Long[] times = lastUsage.get(what);
				if (times == null) {
					times = new Long[2];
					times[1] = Instant.now().toEpochMilli();
				}
				times[0] = times[1];
				times[1] = Instant.now().toEpochMilli();
				lastUsage.put(what, times);
				/* *************************************************************************/
				Method m = regMetMap.get(what);
				if (m != null) {
					try {
						m.invoke(LabelWorker.this, d);
						if (debugMode)
							Logger.debug("Processing:" + d.getData() + " for " + d.label + " from " + d.getOriginID());
					} catch (IllegalAccessException | IllegalArgumentException e) {
						Logger.warn("Invoke Failed:'" + d.label + "'>" + d.getData() + "<");
						Logger.error(e);
					} catch (InvocationTargetException e) {
						Throwable originalException = e.getTargetException();
						Logger.error("'" + originalException + "' at " + originalException.getStackTrace()[0].toString() + " when processing: " + d.getData());
					} catch (Exception e) {
						Logger.error(e);
					}
				} else {
					Logger.warn("Not defined:" + d.getOriginID() + "|" + d.label + " >" + d.getData() + "< raw: " + Tools.fromBytesToHexString(d.raw));
				}
				// Debug information
				procCount.incrementAndGet();
			} catch (java.lang.ArrayIndexOutOfBoundsException f) {
				Logger.error("Interrupted because out ArrayIndexOut" + f + " while processing: " + d.getData() + " Label: " + d.label);
			} catch( Exception e){
				Logger.error(e);
			}
		}
	}

	public void setDebugging(boolean deb) {
		this.debugMode = deb;
	}

	public synchronized int getProcCount(int seconds) {
		double a = procCount.get();
		long passed = (Instant.now().toEpochMilli() - procTime) / 1000; //seconds since last check

		a /= (double) passed;
		a *= seconds;
		procCount.set(0);    // Clear the count
		procTime = Instant.now().toEpochMilli(); // Overwrite for next check
		return (int) Math.rint(a);
	}

	protected long dataAge() {
		return (Instant.now().toEpochMilli() - waitingSince) / 1000;
	}

	public String getMethodCallAge(String newline) {
		StringJoiner b = new StringJoiner(newline);
		ArrayList<String> items = new ArrayList<>();
		for (Entry<String, Long[]> la : lastUsage.entrySet()) {

			Long[] times = la.getValue();
			long time = System.currentTimeMillis() - times[1];
			double freq = (double) (times[1] - times[0]);
			freq = Tools.roundDouble(1000 / freq, freq > 900 ? 0 : 1);
			items.add(la.getKey() + " -> Age/Interval:" + TimeTools.convertPeriodtoString(time, TimeUnit.MILLISECONDS) + "/" + freq + "Hz");
		}
		Collections.sort(items);
		items.forEach(b::add);
		return b.toString();
	}

	/**
	 * Stop the worker thread
	 */
	public void stopWorker() {
		goOn = false;
	}
	/* ******************************* Labeller ********************************************************* */

	@Override
	public void addDatagram(Datagram d){
		dQueue.add(d);
	}
	/* ******************************* D E F A U L T   S T U F F **************************************** */

	public void doFILTER( Datagram d ){
		String[] filter = d.label.split(":");

		for( var f : filter[1].split(",")) {
			var wr = writables.get(filter[0]+":"+f);
			if (wr != null) {
				wr.writeLine(d.getData());
			} else {
				var filterOpt = reqData.getFilter(f);
				if (filterOpt.isPresent()) {
					writables.put(d.label, filterOpt.get().getWritable());
					filterOpt.get().getWritable().writeLine(d.getData());
				}
			}
		}
		procCount.incrementAndGet();
	}
	public void doTELNET(Datagram d) {
		Writable dt = d.getWritable();
		if (!d.getData().equals("status")) {
			String from = " for ";

			if (dt != null) {
				from += dt.getID();
			}
			if (!d.getData().isBlank())
				Logger.info("Executing telnet command [" + d.getData() + "]" + from);
		}
		String response = reqData.createResponse(d.getData(), dt, false);
		String[] split = d.label.split(":");
		if (dt != null) {
			if (!d.silent) {
				dt.writeLine(response);
				dt.writeString((split.length >= 2 ? "<" + split[1] : "") + ">");
			}
		} else {
			Logger.info(response);
			Logger.info((split.length >= 2 ? "<" + split[1] : "") + ">");
		}
		procCount.incrementAndGet();
	}
	/* ************************************** RUNNABLES ******************************************************/
	public int getWaitingQueueSize(){
		return executor.getQueue().size();
	}
	public class SelfCheck implements Runnable {

		public void run() {
			Logger.info("Read count now "+readCount+", old one "+oldReadCount+ " last message processed from "+lastOrigin+ " buffersize "+dQueue.size());
			Logger.info("Executioner: "+ executor.getCompletedTaskCount()+" completed, "+ executor.getTaskCount()+" submitted, "
							+ executor.getActiveCount()+"/"+ executor.getCorePoolSize()+"("+executor.getMaximumPoolSize()+")"+" active threads, "+ executor.getQueue().size()+" waiting to run");

			oldReadCount = readCount;
			readCount=0;
			lastOrigin="";
		}
	}
	public class ProcessValmap implements Runnable {
		Datagram d;

		public ProcessValmap(Datagram d) {
			this.d = d;
		}

		public void run() {
			try {
				String valMapIDs = d.label.split(":")[1];
				String mes = d.getData();

				if (mes.isBlank()) {
					Logger.warn(valMapIDs + " -> Ignoring blank line");
					return;
				}
				for (String valmapID : valMapIDs.split(",")) {
					var map = mappers.get(valmapID);
					if (map != null) {
						map.apply(mes, rtvals);
					}else{
						Logger.error("ValMap requested but unknown id: " + valmapID + " -> Message: " + d.getData());
					}
				}
			} catch (ArrayIndexOutOfBoundsException l) {
				Logger.error("Generic requested (" + d.label + ") but no valid id given.");
			} catch( Exception e){
				Logger.error(e);
			}
			procCount.incrementAndGet();
		}
	}
	private List<Generic> getGenerics( String id ){
		return generics.entrySet().stream().filter( set -> set.getKey().equalsIgnoreCase(id) ||set.getKey().matches(id))
				.map(Entry::getValue).collect(Collectors.toList());
	}
	public class ProcessGeneric implements Runnable{
		Datagram d;

		public ProcessGeneric(Datagram d){
			this.d=d;
		}

		public void run(){
			try{

				String mes = d.getData();
				if( mes.isBlank() ){
					Logger.warn( d.getOriginID() + " -> Ignoring blank line" );
					return;
				}

				var genericIDs = d.label.split(":")[1].split(",");
				for( String genericID : genericIDs ){
					getGenerics(genericID).stream().forEach(
							gen -> {
								if ( mes.startsWith(gen.getStartsWith()) ) {
									Object[] data = gen.apply(mes, rtvals);
									if (!gen.getTable().isEmpty() && gen.writesInDB()) {
										if (gen.isTableMatch()) {
											rtvals.provideRecord( gen.getDBID(), gen.getTable(), data);
										} else {
											if( !rtvals.writeRecord( gen.getDBID(), gen.getTable(), gen.macro) ){
												Logger.error("Failed to write record for "+gen.getTable());
											}
										}
									}
								} else {
									if (gen == null) {
										Logger.error("Generic requested but unknown id: " + genericID + " -> Message: " + d.getData());
									}
								}
							}
					);
				}
			}catch( ArrayIndexOutOfBoundsException e ){
				Logger.error("Generic requested ("+d.label+") but no valid id given.");
			} catch( Exception e){
				Logger.error("Caught an exception when processing "+d.getData()+" from "+d.getOriginID());
				Logger.error(e);
			}
			procCount.incrementAndGet();
		}
	}
}
