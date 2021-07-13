package das;

import io.mqtt.MqttWork;
import io.mqtt.MqttWorker;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.MathCollector;
import io.telnet.TelnetCodes;
import org.influxdb.dto.Point;
import org.tinylog.Logger;
import util.database.QueryWriting;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A storage class that holds: - The data processed by DataWorker.java - The
 * current priority level for several values
 * 
 * @author Michiel T'Jampens
 */
public class RealtimeValues implements CollectorFuture {

	protected IssuePool issues;

	protected static final String DEFAULT_TEXT_COLOR = TelnetCodes.TEXT_YELLOW;
	protected int maxDevices = 2; // This is the maximum priority level allowed atm
	protected static final String DECIMAL_SYMBOL = ",";

	/* Formats */
	protected static final DateTimeFormatter longFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	protected static final DateTimeFormatter sqlFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	protected static final DateTimeFormatter minuteFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	protected static final DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("yyMMdd");

	/* Other */
	protected Map<String, Integer> descriptorID = new HashMap<>();
	protected ConcurrentHashMap<String, DoubleVal> rtvals = new ConcurrentHashMap<>();
	protected ConcurrentHashMap<String, String> rttext = new ConcurrentHashMap<>();
	protected HashMap<String, List<Writable>> rtvalRequest = new HashMap<>();
	protected HashMap<String, List<Writable>> rttextRequest = new HashMap<>();
	protected HashMap<Writable, List<ScheduledFuture<?>>> calcRequest = new HashMap<>();

	/* Databases */
	protected QueryWriting queryWriting;

	/* MQTT */
	Map<String, MqttWorker> mqttWorkers = new HashMap<>();

	/* Some status variables */
	protected int procPerSec = 0;
	protected int queryPer10s = 0;
	protected int queryDBPer10s = 0;
	protected int maxProcPerSec = 0;

	protected String workPath = "";

	/* Variables for during debug mode because no longer realtime */
	protected long passed;

	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private final HashMap<String, MathCollector> mathCollectors = new HashMap<>();

	/* **************************************  C O N S T R U C T O R **************************************************/
	/**
	 * Standard constructor requiring an IssueCollector
	 * 
	 * @param issuePool The IssuePool
	 */
	public RealtimeValues(IssuePool issuePool) {
		this.issues = issuePool;
	}

	/**
	 * Blank constructor
	 */
	public RealtimeValues() {

	}

	public void copySetup(RealtimeValues to) {
		// Copy mqtt
		mqttWorkers.forEach(to::addMQTTworker);
	}

	public void addMQTTworker(String id, MqttWorker mqttWorker) {
		this.mqttWorkers.put(id, mqttWorker);
	}
	public void addQueryWriting( QueryWriting queryWriting ){
		this.queryWriting=queryWriting;
	}
	/* **************************************************************************************************************/

	/**
	 * Add a query to the buffer of the database with reference id
	 * 
	 * @param id    The reference to the database
	 * @param query The query to run
	 * @return True if ok
	 */
	public boolean writeQuery(String id, String query) {
		return queryWriting.addQuery(id,query);
	}
	public void provideRecord(String[] ids, String table, Object[] data) {
		for ( String id : ids ) {
			queryWriting.doDirectInsert(id,table,data);
		}
	}
	/**
	 * This tries to add an insert based on values found in the realtimevalues
	 * hashmap
	 * 
	 * @param ids   The reference to the database or multiple delimited with ,
	 * @param table The table in the database
	 * @return True if ok
	 */
	public boolean writeRecord(String[] ids, String table, String macro) {
		int wrote = 0;
		for ( String id : ids) {
			wrote +=queryWriting.buildInsert(id,table,rtvals, rttext, macro)?1:0;
		}
		return wrote!=0;
	}
	public boolean writeRecord(String[] ids, String table) {
		return writeRecord(ids, table, "");
	}

	public boolean sendToMqtt(String id, String device, String param) {
		MqttWorker worker = mqttWorkers.get(id);
		if (worker != null) {
			double val = this.getRealtimeValue(param, -999);
			if (val != -999) {
				return worker.addWork(new MqttWork(device, param, val));
			}
		}
		return false;
	}
	/* ********************************** I N F L U X D B *********************************************************** */
	public boolean sendToInflux( String id, Point p){
		return queryWriting.writeInfluxPoint(id,p);
	}
	/* ************************************** * D E S C R I P T O R S *************************************************/
	/**
	 * Add a descriptor to the collection
	 * 
	 * @param id         The ID of the variable
	 * @param descriptor The name of the variable
	 */
	public void addDescriptorID(int id, String descriptor) {
		descriptorID.put(descriptor.toLowerCase(), id);
	}

	/**
	 * Get the ID corresponding to the variable name
	 * 
	 * @param descriptor The name of the variable
	 * @return The ID corresponding or null if not found
	 */
	public int getDescriptorID(String descriptor) {
		return descriptorID.get(descriptor);
	}

	/* ************************** O T H E R *************************************************************
	 */
	/**
	 * Debug method to check rtvals work
	 * 
	 * @param value The value to set debug to
	 */
	public void setDebugValue(int value) {
		setRealtimeValue("debug", (double) value,true);
	}

	/* ***************************************************************************************************/
	/**
	 * Get the value of a parameter
	 * 
	 * @param parameter The parameter to get the value of
	 * @param bad       The value to return of the parameter wasn't found
	 * @return The value found or the bad value
	 */
	public double getRealtimeValue(String parameter, double bad) {
		return getRealtimeValue(parameter,bad,false);
	}
	public double getRealtimeValue(String parameter, double defVal, boolean createIfNew) {

		DoubleVal d = rtvals.get(parameter.toLowerCase());
		if (d == null) {
			if( createIfNew ){
				Logger.warn("Parameter "+parameter+" doesn't exist, creating it with value "+defVal);
				setRealtimeValue(parameter,defVal,true);
			}else{
				Logger.error("No such parameter: " + parameter);
			}
			return defVal;
		}
		if (Double.isNaN(d.getValue())) {
			Logger.error("Parameter: " + parameter + " is NaN.");
			return defVal;
		}
		return d.getValue();
	}
	public boolean removeRealtimeValue( String parameter ){
		return rtvals.remove(parameter)!=null;
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * 
	 * @param param The parameter name
	 * @param value     The value of the parameter
	 */
	public void setRealtimeValue(String param, double value, boolean createIfNew) {

		if( param.isEmpty()) {
			Logger.error("Empty param given");
			return;
		}
		var d = rtvals.get(param);
		if( d==null ) {
			if( createIfNew ) {
				var par = param.split("_");
				if (par.length == 2) {
					rtvals.put(param, DoubleVal.newVal(par[0], par[1]).setValue(value) );
				} else {
					rtvals.put(param, DoubleVal.newVal("", par[0]).setValue(value));
				}
			}else{
				Logger.error("No such rtval "+param+" yet, use create:"+param+","+value+" to create it first");
			}
		}else{
			d.setValue(value);
		}

		if( !rtvalRequest.isEmpty()){
			var res = rtvalRequest.get(param);
			if( res != null)
				res.forEach( wr -> wr.writeLine(param + " : " + value));
		}
	}
	public DoubleVal getDoubleVal( String param ){
		return rtvals.get(param);
	}

	/**
	 * Retrieves the param or add's it if it doesn't exist yet
	 * @param param The group_name or just name of the val
	 * @return The object if found or made or null if something went wrong
	 */
	public DoubleVal getOrAddDoubleVal( String param ){
		if( param.isEmpty())
			return null;

		var val = rtvals.get(param);
		if( val==null){
			var spl = param.split("_");
			rtvals.put(param,DoubleVal.newVal(spl[0],spl[1]));
		}
		return rtvals.get(param);
	}
	public void setRealtimeText(String parameter, String value) {
		final String param=parameter.toLowerCase();

		if( param.isEmpty()) {
			Logger.error("Empty param given");
			return;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		rttext.put(parameter, value);

		if( !rtvalRequest.isEmpty()){
			var res = rtvalRequest.get(param);
			if( res != null)
				res.forEach( wr -> wr.writeLine(param + " : " + value));
		}
	}

	public String getRealtimeText(String parameter, String def) {
		String result = rttext.get(parameter);
		return result == null ? def : result;
	}

	/**
	 * Get a listing of all the parameters-value pairs currently stored
	 * 
	 * @return Readable listing of the parameters
	 */
	public List<String> getRealtimeValuePairs() {
		ArrayList<String> params = new ArrayList<>();
		rtvals.forEach((param, value) -> params.add(param + " : " + value));
		Collections.sort(params);
		return params;
	}
	public List<String> getRealtimeTextPairs() {
		ArrayList<String> params = new ArrayList<>();
		rttext.forEach((param, value) -> params.add(param + " : " + value));
		Collections.sort(params);
		return params;
	}
	public List<String> getRealtimeValueParameters() {
		ArrayList<String> params = new ArrayList<>();
		rtvals.forEach((param, value) -> params.add(param));
		Collections.sort(params);
		return params;
	}
	public List<String> getRealtimeTextParameters() {
		ArrayList<String> params = new ArrayList<>();
		rttext.forEach((param, value) -> params.add(param));
		Collections.sort(params);
		return params;
	}
	/**
	 * Get a listing of double parameter : value pairs currently stored that meet the param
	 * request
	 * 
	 * @return Readable listing of the parameters
	 */
	public String getFilteredRTVals(String param, String eol) {

		Stream<Entry<String, DoubleVal>> stream;
		if (param.endsWith("*") && param.startsWith("*")) {
			stream = rtvals.entrySet().stream()
					.filter(e -> e.getKey().contains(param.substring(1, param.length() - 1)));
		} else if (param.endsWith("*")) {
			stream = rtvals.entrySet().stream()
					.filter(e -> e.getKey().startsWith(param.substring(0, param.length() - 1)));
		} else if (param.startsWith("*")) {
			stream = rtvals.entrySet().stream().filter(e -> e.getKey().endsWith(param.substring(1)));
		} else if (param.isEmpty() || param.equalsIgnoreCase("groups")) {
			stream = rtvals.entrySet().stream();
		} else {
			stream = rtvals.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(param));
		}
		// Stream contains all of it...
		if( param.equalsIgnoreCase("groups")) {
			var sorted = stream.sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + " : " + e.getValue().toString()).collect(Collectors.toList());
			StringJoiner join = new StringJoiner(eol);
			String header = "";
			for (var line : sorted) {
				var split = line.split("_");
				if (split.length >= 2) {
					if (header.isEmpty() || !header.equalsIgnoreCase(split[0])) {
						if( !header.isEmpty())
							join.add("<<"+eol); // empty lines between groups
						header = split[0];

						join.add(">> " + header );
					}
					join.add(" -> "+line.substring(line.indexOf("_"+1)));
				} else {
					if( !header.isEmpty()) {
						join.add("<<" + eol);
						header="";
					}
					join.add(line);
				}
			}
			return join.toString();
		}
		return stream.sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + " : " + e.getValue().toString()).collect(Collectors.joining(eol));
	}
	/**
	 * Get a listing of text parameter : value pairs currently stored that meet the param
	 * request
	 * 
	 * @return Readable listing of the parameters
	 */
	public String getFilteredRTTexts(String param, String eol) {
		Stream<Entry<String, String>> stream;
		if (param.endsWith("*") && param.startsWith("*")) {
			stream = rttext.entrySet().stream()
					.filter(e -> e.getKey().contains(param.substring(1, param.length() - 1)));
		} else if (param.endsWith("*")) {
			stream = rttext.entrySet().stream()
					.filter(e -> e.getKey().startsWith(param.substring(0, param.length() - 1)));
		} else if (param.startsWith("*")) {
			stream = rttext.entrySet().stream().filter(e -> e.getKey().endsWith(param.substring(1)));
		} else if (param.isEmpty()) {
			stream = rttext.entrySet().stream();
		} else {
			stream = rttext.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(param));
		}
		return stream.sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + " : " + e.getValue()).collect(Collectors.joining(eol));
	}

	/* ******************************************************************************************************/
	/**
	 * Get the current timestamp in db approved format, this should be overriden if
	 * a gps is present
	 * 
	 * @return The timestamp in a sql valid format yyyy-MM-dd HH:mm:ss.SSS
	 */
	public synchronized String getTimeStamp() {
		return LocalDateTime.now(ZoneOffset.UTC).format(longFormat);
	}

	/* ******************************************************************************************************/
	/**
	 * Get a predetermined combination/calculation of parameters
	 * 
	 * @param what The reference used for the calculation/forming
	 * @return Result in string format
	 */
	public String getCalcValue(String what) {
		return what.equalsIgnoreCase("clock")?getTimeStamp():getExtraCalcValue(what);
	}

	/**
	 * The list of extra calc options, to allow override to add some
	 * 
	 * @param what The reference used for the calculation/forming
	 * @return Result in string format
	 */
	protected String getExtraCalcValue(String what) {
		return "";
	}

	/* ******************************************************************************************************/
	/**
	 * Method to override that return the status
	 * 
	 * @param html Whether or not this to use html EOL
	 * @return Status information
	 */
	public String getStatus(boolean html) {
		return "";
	}

	public void removeRequest(Writable writable ) {

		rtvalRequest.forEach( (key,list) -> list.remove(writable));
		rttextRequest.forEach( (key,list) -> list.remove(writable));
		calcRequest.forEach( (key,futures) ->
		{
			if( key==writable){
				futures.forEach(f->f.cancel(true));
			}
		});
		if( calcRequest.remove(writable)!=null)
			Logger.info("Removed atleast a single element from calc requests");
	}
	public String getRequestList( String request ){
		String[] req = request.split(":");
		StringJoiner join = new StringJoiner("\r\n");
		join.setEmptyValue("None yet");
		switch (req[0]) {
			case "rtval":
				rtvalRequest.forEach((rq,list) -> join.add(rq +" -> "+list.size()+" requesters"));
				break;
			case "rttext":
				rttextRequest.forEach((rq,list) -> join.add(rq +" -> "+list.size()+" requesters"));
				break;
			case "calc":
				calcRequest.forEach((key,list)->join.add(key.getID()+" -> "+list.size()+" calc request(s)"));
				break;
		}
		return join.toString();
	}
	public boolean addRequest(Writable writable, String request) {
		String[] req = request.split(":");
		switch (req[0]) {
			case "rtval":
				var r = rtvalRequest.get(req[1]);
				if( r == null) {
					rtvalRequest.put(req[1], new ArrayList<>());
					Logger.info("Created new request for: " + req[1]);
				}else{
					Logger.info("Appended existing request to: " + r + "," + req[1]);
				}
				if( !rtvalRequest.get(req[1]).contains(writable)) {
					rtvalRequest.get(req[1]).add(writable);
					return true;
				}
				break;
			case "calc":
				calcRequest.computeIfAbsent(writable, k -> new ArrayList<>());
				calcRequest.get(writable).add(scheduler.scheduleWithFixedDelay(new CalcRequest(writable, req[1]), 0, 1,
						TimeUnit.SECONDS));
				break;
			case "rttext":
				var t = rttextRequest.get(req[1]);
				if( t == null) {
					rttextRequest.put(req[1], new ArrayList<>());
					Logger.info("Created new request for: " + req[1]);
				}else{
					Logger.info("Appended existing request to: " + t + "," + req[1]);
				}
				if( !rttextRequest.get(req[1]).contains(writable)) {
					rttextRequest.get(req[1]).add(writable);
					return true;
				}
				break;
			default:
				Logger.warn("Requested unknown type: "+req[0]);
				break;
		}
		return false;
	}
	/* **************************** MATH COLLECTOR ********************************************** */
	public void addMathCollector( MathCollector mc ){
		mc.addListener(this);
		mathCollectors.put( mc.getID(),mc);
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		if(ids[0].equalsIgnoreCase("math")){
			setRealtimeValue(message,(double)result,false);
		}
	}
	/* ********************************************************************************************** */
	public class CalcRequest implements Runnable{
		String request;
		Writable dt; 

		public CalcRequest(Writable dt, String request){
			this.request=request;
			this.dt=dt;
		}
		@Override
		public void run() {
			dt.writeLine( getCalcValue(request) );

			if( !dt.isConnectionValid()) {
				Logger.info(dt.getID() + " -> Writable no longer valid, removing calc request");
				removeRequest(dt);
			}
		}
	}
}