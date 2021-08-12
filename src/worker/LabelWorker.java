package worker;

import util.data.DataProviding;
import io.Readable;
import io.Writable;
import das.CommandPool;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.DeadThreadListener;
import util.database.QueryWriting;
import util.tools.TimeTools;
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

	Map<String, Generic> generics = new HashMap<>();
	Map<String, ValMap> mappers = new HashMap<>();
	Map<String, Writable> writables = new HashMap<>();
	Map<String, Readable> readables = new HashMap<>();

	private BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();      // The queue holding raw data for processing
	private DataProviding dp;
	private QueryWriting queryWriting;
	private MqttWriting mqtt;

	private boolean goOn = true; // General process boolean, clean way of stopping thread

	protected CommandPool reqData;

	protected boolean debugMode = false;

	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();
	long waitingSince;

	protected DeadThreadListener listener;

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<Runnable>());

	ScheduledExecutorService debug = Executors.newSingleThreadScheduledExecutor();

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
	public LabelWorker(BlockingQueue<Datagram> dQueue, DataProviding dp, QueryWriting queryWriting) {
		this.dQueue = dQueue;
		this.dp=dp;
		this.queryWriting=queryWriting;

		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(new SelfCheck(),5,30,TimeUnit.MINUTES);
	}
	@Override
	public void addDatagram(Datagram d){
		dQueue.add(d);
	}

	public void setMqttWriter( MqttWriting mqtt){
		this.mqtt=mqtt;
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
	 * @param commandPool The default BaseReq or extended one
	 */
	public void setCommandReq(CommandPool commandPool) {
		this.reqData = commandPool;
	}

	/**
	 * Set the DataProvider for the worker to use
	 *
	 * @param dp The implementation of the DataProviding interface
	 */
	public void setDataProviding(DataProviding dp) {
		this.dp = dp;
	}

	/**
	 * Set or remove debugmode flag
	 * @param deb New state for the debugmode
	 */
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

	/**
	 * Stop the worker thread
	 */
	public void stopWorker() {
		goOn = false;
	}

	/* ****************************************** V A L M A P S *************************************************** */
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
	public int getWaitingQueueSize(){
		return executor.getQueue().size();
	}
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
				lastOrigin=d.getOriginID();
				String label = d.getLabel();

				if (label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}

				if( d.label.contains(":") ){
					String readID = label.substring(label.indexOf(":")+1);
					switch(d.label.split(":")[0]){
						case "generic": executor.execute(new ProcessGeneric(d)); break;
						case "double":  executor.execute(() -> storeInDoubleVal(readID,d.getData(),d.getOriginID())); break;
						case "valmap":  executor.execute(new ProcessValmap(d)); break;
						case "text":    executor.execute(() -> dp.setText(readID, d.data)); break;
						case "read":    executor.execute( ()-> checkRead(d.getOriginID(),d.getWritable(),readID) );break;
						case "telnet":  executor.execute(()-> checkTelnet(d)); break;
						case "log":
							switch(d.label.split(":")[1]){
								case "info":Logger.info(d.getData()); break;
								case "warn":Logger.warn(d.getData()); break;
								case "error":Logger.error(d.getData()); break;
							}
							break;
						default:
							Logger.error("Unknown label: "+label);
							break;
					}
				}else {
					switch (label) {
						case "system":
							executor.execute(() -> reqData.createResponse(d.getData(), d.getWritable(), false));
							break;
						case "void":
							break;
						case "readable":
							if(  d.getReadable()!=null)
								readables.put(d.getReadable().getID(),d.getReadable());
							readables.entrySet().removeIf( entry -> entry.getValue().isInvalid()); // cleanup
							break;
						case "test":
							executor.execute(() -> Logger.info(d.getOriginID() + "|" + label + " -> " + d.getData()));
							break;
						case "email":
							executor.execute(() -> reqData.emailResponse(d));
							break;
						case "telnet":
							executor.execute(() -> checkTelnet(d) );
						default:
							Logger.error("Unknown label: "+label);
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

	/* ******************************* D E F A U L T   S T U F F **************************************** */
	private void storeInDoubleVal(String param, String data, String origin ){
		try{
			var val = NumberUtils.toDouble(data,Double.NaN);
			if( Double.isNaN(val) && NumberUtils.isCreatable(data)){
				val = NumberUtils.createInteger(data);
			}
			if( !Double.isNaN(val) ){
				dp.setDouble(param,val);
			}else{
				Logger.warn("Tried to convert "+data+" from "+origin+" to a double...");
			}
		}catch( NumberFormatException e ){
			Logger.warn("Tried to convert "+data+" from "+origin+" to a double...");
		}
	}
	private void checkRead( String from, Writable wr, String id){
		if(wr!=null){
			var ids = id.split(",");
			var read = readables.get(ids[0]);
			if( read != null && !read.isInvalid()){

				read.addTarget(wr,ids.length==2?ids[1]:"*");
				Logger.info("Added "+wr.getID()+ " to target list of "+read.getID());
			}else{
				Logger.error(wr.getID()+" asked for data from "+id+" but doesn't exists (anymore)");
			}
			readables.entrySet().removeIf( entry -> entry.getValue().isInvalid());
		}else{
			Logger.error("No valid writable in the datagram from "+from);
		}
	}
	public void checkTelnet(Datagram d) {
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
		String[] split = d.getLabel().split(":");
		if (dt != null) {
			if (!d.isSilent()) {
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
						map.apply(mes, dp);
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
					var generics = getGenerics(genericID);
					if( generics==null || generics.isEmpty() ) {
						Logger.error("No such generic " + genericID + " for " + d.getOriginID());
						continue;
					}
					generics.stream().forEach(
							gen -> {
								if ( mes.startsWith(gen.getStartsWith()) ) {
									Object[] data = gen.apply( mes, dp, queryWriting,mqtt );
									if (!gen.getTable().isEmpty() && gen.writesInDB()) {
										if (gen.isTableMatch()) {
											for( String id : gen.getDBID() )
												queryWriting.doDirectInsert( id, gen.getTable(), data);
										} else {
											for( String id : gen.getDBID() ){
												if (!queryWriting.buildInsert(id, gen.getTable(), dp, gen.macro)) {
													Logger.error("Failed to write record for " + gen.getTable());
												}
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
