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
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A storage class
 *
 */
public class RealtimeValues implements CollectorFuture, DataProviding {

	/* Other */
	protected ConcurrentHashMap<String, DoubleVal> doubleVals = new ConcurrentHashMap<>();
	protected HashMap<String, List<Writable>> rtvalRequest = new HashMap<>();

	protected ConcurrentHashMap<String, String> rttext = new ConcurrentHashMap<>();
	protected HashMap<String, List<Writable>> rttextRequest = new HashMap<>();

	protected ConcurrentHashMap<String, Boolean> flags = new ConcurrentHashMap<>();

	/* MQTT */
	Map<String, MqttWorker> mqttWorkers = new HashMap<>();

	protected String workPath = "";

	/* Variables for during debug mode because no longer realtime */
	protected long passed;

	private final HashMap<String, MathCollector> mathCollectors = new HashMap<>();

	/* Patterns */
	Pattern rtvalPattern=null;
	Pattern rttextPattern=null;
	Pattern words = Pattern.compile("[a-zA-Z]+[_0-9]+[a-zA-Z]+\\d*"); // find references to doublevals etc


	/**
	 * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps
	 * @param line The line to parse
	 * @param error The line to return on an error or 'ignore' if errors should be ignored
	 * @return The (possibly) altered line
	 */
	public String simpleParseRT( String line,String error ){

		var found = words.matcher(line).results().map(MatchResult::group).collect(Collectors.toList());

		for( var word : found ){
			var d = getRealtimeValue(word, Double.NaN);
			if (!Double.isNaN(d)) {
				line = line.replace(word,""+d);
			}else{
				var t = getRealtimeText(word,"");
				if( !t.isEmpty()) {
					line = line.replace(word, t);
				}else if( hasFlag(word)){
					line = line.replace(word, isFlagUp(word)?"1":"0");
				}else if( error.equalsIgnoreCase("create")){
					getOrAddDoubleVal(word).setValue(0);
					Logger.warn("Created doubleval "+word+" with value 0");
					line = line.replace(word, "0");
				}else if( !error.equalsIgnoreCase("ignore")){
					Logger.error("Couldn't process "+word+" found in "+line);
					return error;
				}
			}
		}
		return line;
	}

	/**
	 * Stricter version to parse a realtime line, must contain the references within {double:... } or {text:...}.
	 * This also checks for {utc}/{utclong},{utcshort} to insert current timestamp
	 * @param line The original line to parse/alter
	 * @param error Value to put if the reference isn't found
	 * @return The (possibly) altered line
	 */
	public String parseRTline( String line, String error ){

		if( rtvalPattern==null) {
			rtvalPattern = Pattern.compile("\\{double:.*}");
			rttextPattern = Pattern.compile("\\{text:.*}");
		}
		if( !line.contains("{"))
			return line;

		var pairs = Tools.parseKeyValue(line);
		for( var p : pairs ){
			if(p.length==2) {
				if (p[0].equals("double")) {
					var d = getRealtimeValue(p[1], Double.NaN);
					if (Double.isNaN(d)) {
						line = line.replace("{double:" + p[1] + "}", error);
					} else {
						line = line.replace("{double:" + p[1] + "}", "" + d);
					}
				} else if (p[0].equals("text")) {
					line = line.replace("{text:" + p[1] + "}", getRealtimeText(p[1], error));
				}
			}else{
				switch(p[0]){
					case "utc": line = line.replace("{utc}", TimeTools.formatLongUTCNow());break;
					case "utclong": line = line.replace("{utclong}", TimeTools.formatLongUTCNow());
					case "utcshort": line = line.replace("{utcshort}", TimeTools.formatShortUTCNow());
				}
			}
		}
		if( line.contains("{")){
			Logger.error("Found a {, this means couldn't parse a section of "+line);
		}
		return line;
	}
	/* ************************************ D O U B L E V A L ***************************************************** */

	/**
	 * Retrieve a DoubleVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested DoubleVal or null if not found
	 */
	public DoubleVal getDoubleVal( String id ){
		if( doubleVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing doubleval "+id);
		return doubleVals.get(id);
	}

	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param id The group_name or just name of the val
	 * @return The object if found or made or null if something went wrong
	 */
	public DoubleVal getOrAddDoubleVal( String id ){
		if( id.isEmpty())
			return null;

		var val = doubleVals.get(id);
		if( val==null){
			doubleVals.put(id,DoubleVal.newVal(id));
		}
		return doubleVals.get(id);
	}

	/**
	 * Removes the doubleval with the given id from the hashmap
	 * @param id The id to remove
	 * @return True if deleted
	 */
	public boolean removeDoubleVal( String id ){
		return doubleVals.remove(id)!=null;
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @param createIfNew Whether to create a new object if none was found
	 */
	public boolean setRealtimeValue(String id, double value, boolean createIfNew) {
		boolean ok = false;
		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return ok;
		}
		var d = doubleVals.get(id);
		if( d==null ) {
			if( createIfNew ) {
				var par = id.split("_");
				if (par.length == 2) {
					doubleVals.put(id, DoubleVal.newVal(par[0], par[1]).setValue(value) );
				} else {
					doubleVals.put(id, DoubleVal.newVal("", par[0]).setValue(value));
				}
				ok=true;
			}else{
				Logger.error("No such rtval "+id+" yet, use create:"+id+","+value+" to create it first");
			}
		}else{
			d.setValue(value);
		}

		if( !rtvalRequest.isEmpty()){
			var res = rtvalRequest.get(id);
			if( res != null)
				res.forEach( wr -> wr.writeLine(id + " : " + value));
		}
		return ok;
	}
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

		DoubleVal d = doubleVals.get(parameter.toLowerCase());
		if (d == null) {
			if( createIfNew ){
				Logger.warn("Parameter "+parameter+" doesn't exist, creating it with value "+defVal);
				setRealtimeValue(parameter,defVal,true);
			}else{
				Logger.debug("No such parameter: " + parameter);
			}
			return defVal;
		}
		if (Double.isNaN(d.getValue())) {
			Logger.error("Parameter: " + parameter + " is NaN.");
			return defVal;
		}
		return d.getValue();
	}

	/* *********************************** RT TEXT ************************************************************* */
	public boolean setRealtimeText(String parameter, String value) {
		final String param=parameter.toLowerCase();

		if( param.isEmpty()) {
			Logger.error("Empty param given");
			return false;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		rttext.put(parameter, value);

		if( !rtvalRequest.isEmpty()){
			var res = rtvalRequest.get(param);
			if( res != null)
				res.forEach( wr -> wr.writeLine(param + " : " + value));
		}
		return true;
	}

	public String getRealtimeText(String parameter, String def) {
		String result = rttext.get(parameter);
		return result == null ? def : result;
	}
	/* ************************************ F L A G S ************************************************************* */
	public boolean hasFlag( String flag){
		return flags.get(flag)!=null;
	}
	public boolean isFlagUp( String flag ){
		var f = flags.get(flag);
		if( f==null)
			Logger.warn("No such flag: "+flag);
		return f==null?false:f;
	}
	public boolean isFlagDown( String flag ){
		var f = flags.get(flag);
		if( f==null)
			Logger.warn("No such flag: "+flag);
		return f==null?false:!f;
	}

	/**
	 * Raises a flag/sets a boolean.
	 * @param flag The flags/bits to set
	 * @return True if this is a new flag/bit
	 */
	public boolean raiseFlag( String... flag ){
		int cnt = flags.size();
		for( var f : flag)
			flags.put(f,true);
		return cnt!=flags.size();
	}
	/**
	 * Lowers a flag/clears a boolean.
	 * @param flag The flags/bits to clear
	 * @return True if this is a new flag/bit
	 */
	public boolean lowerFlag( String... flag ){
		int cnt = flags.size();
		for( var f : flag)
			flags.put(f,false);
		return cnt!=flags.size();
	}

	/**
	 * Set the state of the flag
	 * @param id The flag id
	 * @param state The new state for the flag
	 * @return True if the state was changed, false if a new flag was made
	 */
	public boolean setFlagState( String id, boolean state){
		return flags.put(id,state) != null;
	}
	public ArrayList<String> listFlags(){
		return flags.entrySet().stream().map( ent -> ent.getKey()+" : "+ent.getValue()).collect(Collectors.toCollection(ArrayList::new));
	}
	/* ********************************* O V E R V I E W *********************************************************** */
	/**
	 * Get a listing of all the parameters-value pairs currently stored
	 * 
	 * @return Readable listing of the parameters
	 */
	public List<String> getRealtimeValuePairs() {
		ArrayList<String> params = new ArrayList<>();
		doubleVals.forEach((param, value) -> params.add(param + " : " + value));
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
		doubleVals.forEach((param, value) -> params.add(param));
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
			stream = doubleVals.entrySet().stream()
					.filter(e -> e.getKey().contains(param.substring(1, param.length() - 1)));
		} else if (param.endsWith("*")) {
			stream = doubleVals.entrySet().stream()
					.filter(e -> e.getKey().startsWith(param.substring(0, param.length() - 1)));
		} else if (param.startsWith("*")) {
			stream = doubleVals.entrySet().stream().filter(e -> e.getKey().endsWith(param.substring(1)));
		} else if (param.isEmpty() || param.equalsIgnoreCase("groups")) {
			stream = doubleVals.entrySet().stream();
		} else {
			stream = doubleVals.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(param));
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
	 * Get a listing of all stored variables that belong to a certain group
	 * @param group The group they should belong to
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsGroupList(String group, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Group: "+group+"</b>": TelnetCodes.TEXT_CYAN+"Group: "+group+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");
		doubleVals.values().stream().filter( dv -> dv.getGroup().equalsIgnoreCase(group))
				.forEach(dv -> join.add(space+dv.getName()+" : "+dv.toString()));
		rttext.entrySet().stream().filter(ent -> ent.getKey().startsWith(group+"_"))
				.forEach( ent -> join.add( space+ent.getKey().split("_")[1]+" : "+ent.getValue()) );
		flags.entrySet().stream().filter(ent -> ent.getKey().startsWith(group+"_"))
				.forEach( ent -> join.add( space+ent.getKey().split("_")[1]+" : "+ent.getValue()) );
		return join.toString();
	}

	/**
	 * Get a listing of all stored variables that have the given name
	 * @param name The name of the variable or the string the name starts with if ending it with *
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsNameList(String name, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Name: "+name+"</b>":TelnetCodes.TEXT_CYAN+"Name: "+name+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");
		doubleVals.values().stream().filter( dv -> dv.getName().matches(name.replace("*",".*")))
				.forEach(dv -> join.add(space+dv.getGroup()+" -> "+dv.getName()+" : "+dv.toString()));
		rttext.entrySet().stream().filter(ent -> ent.getKey().matches(name.replace("*",".*")))
				.forEach( ent -> join.add( space+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		flags.entrySet().stream().filter(ent -> ent.getKey().endsWith(name.replace("*",".*")))
				.forEach( ent -> join.add( space+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		return join.toString();
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
	public String storeRTVals(Path settings){
		XMLfab fab = XMLfab.withRoot(settings,"dcafs","settings","rtvals");
		var keys = doubleVals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()).collect(Collectors.toList());
		for( var dv : keys ){
			var dd = doubleVals.get(dv);
			fab.selectOrCreateParent("double","id",dv)
					.attr("unit",dd.unit)
					.up();
		}
		keys = rttext.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()).collect(Collectors.toList());
		for( var dt : keys ){
			fab.selectOrCreateParent("text","id",dt).up();
		}
		fab.build();
		return "New rtvals/rttexts added";
	}
	/* ******************************************************************************************************/
	/**
	 * Get the current timestamp in db approved format, this should be overridden if
	 * a gps is present
	 * 
	 * @return The timestamp in a sql valid format yyyy-MM-dd HH:mm:ss.SSS
	 */
	public synchronized String getTimeStamp() {
		return LocalDateTime.now(ZoneOffset.UTC).format(TimeTools.LONGDATE_FORMATTER_UTC);
	}

	/* ******************************************************************************************************/
	/**
	 * Method to override that return the status
	 * 
	 * @param html Whether to use html EOL
	 * @return Status information
	 */
	public String getStatus(boolean html) {
		return "";
	}

	public void removeRequest(Writable writable ) {
		rtvalRequest.forEach( (key,list) -> list.remove(writable));
		rttextRequest.forEach( (key,list) -> list.remove(writable));
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
}