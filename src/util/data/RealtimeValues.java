package util.data;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.MathCollector;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.gis.Waypoints;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
public class RealtimeValues implements CollectorFuture, DataProviding, Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, DoubleVal> doubleVals = new ConcurrentHashMap<>(); // doubles
	private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>(); // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); // booleans
	private final HashMap<String, MathCollector> mathCollectors = new HashMap<>(); // Math collectors
	private Waypoints waypoints; //waypoints
	private IssuePool issuePool;

	/* Data update requests */
	private final HashMap<String, List<Writable>> doubleRequest = new HashMap<>();
	private final HashMap<String, List<Writable>> textRequest = new HashMap<>();

	/* General settings */
	private final Path settingsPath;

	/* Patterns */
	private final Pattern words = Pattern.compile("[a-zA-Z]+[_:0-9]*[a-zA-Z]+\\d*"); // find references to doublevals etc

	/* Other */
	private final BlockingQueue<Datagram> dQueue;

	public RealtimeValues( Path settingsPath,BlockingQueue<Datagram> dQueue ){
		this.settingsPath=settingsPath;
		this.dQueue=dQueue;

		issuePool = new IssuePool(dQueue, settingsPath,this);

		readFromXML();
	}
	public Waypoints enableWaypoints(ScheduledExecutorService scheduler){
		waypoints = new Waypoints(settingsPath,scheduler,this,dQueue);
		return waypoints;
	}
	public IssuePool getIssuePool(){
		return issuePool;
	}
	/**
	 * Read the rtvals node in the settings.xml
	 */
	public void readFromXML(){
		var fab = XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals");
		double defDouble = XMLtools.getDoubleAttribute(fab.getCurrentElement(),"doubledefault",Double.NaN);
		String defText = XMLtools.getStringAttribute(fab.getCurrentElement(),"textdefault","");
		boolean defFlag = XMLtools.getBooleanAttribute(fab.getCurrentElement(),"flagdefault",false);

		fab.getChildren("*").forEach(
				rtval -> {
					String id = XMLtools.getStringAttribute(rtval,"id","");
					if( id.isEmpty())
						return;
					if( rtval.getTagName().equals("group")){
						id += "_";
						for( var groupie : XMLtools.getChildElements(rtval)){
							var gid = XMLtools.getStringAttribute(groupie,"id","");
							gid = id+XMLtools.getStringAttribute(groupie,"name",gid);
							processRtvalElement(groupie, gid.toLowerCase(), defDouble, defText, defFlag);
						}
					}else {
						processRtvalElement(rtval, id.toLowerCase(), defDouble, defText, defFlag);
					}
				}
		);
	}
	private void processRtvalElement(Element rtval, String id, double defDouble, String defText, boolean defFlag ){
		switch( rtval.getTagName() ){
			case "double":
				int scale = XMLtools.getIntAttribute(rtval,"fractiondigits",-1);
				if( scale == -1)
					scale = XMLtools.getIntAttribute(rtval,"scale",-1);
				var dv = getOrAddDoubleVal(id);
				dv.name(XMLtools.getChildValueByTag(rtval,"name",dv.getName()))
						.group(XMLtools.getChildValueByTag(rtval,"group",dv.getGroup()))
						.unit(XMLtools.getStringAttribute(rtval,"unit",""))
						.fractionDigits(scale)
						.defValue(XMLtools.getDoubleAttribute(rtval,"default",defDouble))
						.enableHistory(XMLtools.getChildIntValueByTag(rtval,"history",-1));
					if( XMLtools.getBooleanAttribute(rtval,"keeptime",false) )
						dv.enableTimekeeping();
				if( !XMLtools.getChildElements(rtval,"cmd").isEmpty() )
					dv.enableTriggeredCmds(dQueue);
				for( Element trigCmd : XMLtools.getChildElements(rtval,"cmd")){
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					dv.addTriggeredCmd(cmd,trig);
				}
				break;
			case "text":
				setText(id,XMLtools.getStringAttribute(rtval,"default",defText));
				break;
			case "flag":
				var fv = getOrAddFlagVal(id);
				fv.name(XMLtools.getChildValueByTag(rtval,"name",fv.getName()))
						.group(XMLtools.getChildValueByTag(rtval,"group",fv.getGroup()))
						.defState(XMLtools.getBooleanAttribute(rtval,"default",defFlag));
				if( XMLtools.getBooleanAttribute(rtval,"keeptime",false) )
					fv.enableTimekeeping();
				break;
		}
	}
	/**
	 * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps
	 * @param line The line to parse
	 * @param error The line to return on an error or 'ignore' if errors should be ignored
	 * @return The (possibly) altered line
	 */
	public String simpleParseRT( String line,String error ){

		var found = words.matcher(line).results().map(MatchResult::group).collect(Collectors.toList());

		for( var word : found ){
			var d = getDouble(word, Double.NaN);
			if (!Double.isNaN(d)) {
				line = line.replace(word,""+d);
			}else{
				var t = getText(word,"");
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

		if( !line.contains("{"))
			return line;

		var pairs = Tools.parseKeyValue(line,true);
		for( var p : pairs ){
			if(p.length==2) {
				switch (p[0]) {
					case "d":
					case "double": {
						var d = getDouble(p[1], Double.NaN);
						if (!Double.isNaN(d) || !error.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : "" + d);
						break;
					}
					case "t":
					case "text":
						String t = getText(p[1], error);
						if (!t.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
						break;
					case "f":
					case "flag": {
						var d = getFlagVal(p[1]);
						var r = d.map(FlagVal::toString).orElse(error);
						if (!r.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
						break;
					}
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
	public String buildNumericalMem( String exp, ArrayList<NumericVal> nums, int offset){
		if( nums==null)
			nums = new ArrayList<>();

		// Find all the double/flag pairs
		var pairs = Tools.parseKeyValue(exp,true);
		for( var p : pairs ) {
			boolean ok=false;
			if (p.length == 2) {
				for( int pos=0;pos<nums.size();pos++ ){ // go through the known doubleVals
					var d = nums.get(pos);
					if( d.getID().equalsIgnoreCase(p[1])) { // If a match is found
						exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (offset + pos));
						ok=true;
						break;
					}
				}
				if( ok )
					continue;
				int index;
				switch(p[0]){
					case "d": case "double":
						var d = getDoubleVal(p[1]);
						if( d.isPresent() ){
							index = nums.indexOf(d.get());
							if(index==-1){
								nums.add( d.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find a doubleval with id "+p[1]);
							return "";
						}
						break;
					case "f": case "flag":
						var f = getFlagVal(p[1]);
						if( f.isPresent() ){
							index = nums.indexOf(f.get());
							if(index==-1){
								nums.add( f.get() );
								index = nums.size()-1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						}else{
							Logger.error("Couldn't find a FlagVal with id "+p[1]);
							return "";
						}
						break;
					default:
						Logger.error("Operation containing unknown pair: "+p[0]+":"+p[1]);
						return "";
				}
			}else{
				Logger.error( "Pair containing odd amount of elements: "+String.join(":",p));
			}
		}
		// Figure out the rest?
		var found = words.matcher(exp).results().map(MatchResult::group).collect(Collectors.toList());
		for( String fl : found){
			int index;
			if( fl.startsWith("flag:")){
				var f = getFlagVal(fl.substring(5));
				if( f.isPresent() ){
					index = nums.indexOf(f.get());
					if(index==-1){
						nums.add( f.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					Logger.error("Couldn't find a FlagVal with id "+fl);
					return "";
				}
			}else{
				var d = getDoubleVal(fl);
				if( d.isPresent() ){
					index = nums.indexOf(d.get());
					if(index==-1){
						nums.add( d.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					Logger.error("Couldn't find a doubleval with id "+fl);
					return "";
				}
			}
		}
		nums.trimToSize();
		return exp;
	}
	public Optional<NumericVal> getNumericVal( String id){
		if( id.startsWith("{")){
			id = id.substring(1,id.length()-2);
			var pair = id.split(":");
			switch(pair[0]) {
				case "d":case "double":
					return Optional.ofNullable(doubleVals.get(id));
				case "f": case "flag":
					return Optional.ofNullable(flagVals.get(id));
			}
			return Optional.empty();
		}
		return getDoubleVal(id).map( d -> Optional.of((NumericVal)d)).orElse(Optional.ofNullable((flagVals.get(id))));
	}
	/* ************************************ D O U B L E V A L ***************************************************** */

	/**
	 * Retrieve a DoubleVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested DoubleVal or null if not found
	 */
	public Optional<DoubleVal> getDoubleVal( String id ){
		if( doubleVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing doubleval "+id);
		return Optional.ofNullable(doubleVals.get(id));
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
	public boolean hasDouble( String id){
		return doubleVals.containsKey(id);
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @param createIfNew Whether to create a new object if none was found
	 * @return True if it was created
	 */
	private boolean setDouble(String id, double value, boolean createIfNew) {
		boolean ok = false;
		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return false;
		}
		var d = doubleVals.get(id);
		if( d==null ) {
			if( createIfNew ) {
				var par = id.split("_");
				if (par.length == 2) {
					doubleVals.put(id, DoubleVal.newVal(par[0], par[1]).value(value) );
				} else {
					doubleVals.put(id, DoubleVal.newVal("", par[0]).value(value));
				}
				ok=true;
			}else{
				Logger.error("No such double "+id+" yet, create it first");
			}
		}else{
			d.setValue(value);
		}

		if( !doubleRequest.isEmpty()){
			var res = doubleRequest.get(id);
			if( res != null)
				res.forEach( wr -> wr.writeLine(id + " : " + value));
		}
		return ok;
	}
	public boolean setDouble(String id, double value){
		return setDouble(id,value,true);
	}
	public boolean updateDouble(String id, double value) {
		if( id.isEmpty())
			return false;
		return !setDouble(id,value,false);
	}
	public int updateDoubleGroup(String group, double value){
		var set = doubleVals.values().stream().filter( dv -> dv.getGroup().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(dv->dv.setValue(value));
		return set.size();
	}
	/**
	 * Get the value of a double
	 *
	 * @param id The id to get the value of
	 * @param bad The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public double getDouble(String id, double bad) {
		return getDouble(id,bad,false);
	}
	public double getDouble(String id, double defVal, boolean createIfNew) {

		DoubleVal d = doubleVals.get(id.toLowerCase());
		if (d == null) {
			if( createIfNew ){
				Logger.warn("ID "+id+" doesn't exist, creating it with value "+defVal);
				setDouble(id,defVal,true);
			}else{
				Logger.debug("No such id: " + id);
			}
			return defVal;
		}
		if (Double.isNaN(d.getValue())) {
			Logger.error("ID: " + id + " is NaN.");
			return defVal;
		}
		return d.getValue();
	}
	/**
	 * Get a listing of all the parameters-value pairs currently stored
	 *
	 * @return Readable listing of the parameters
	 */
	public List<String> getDoublePairs() {
		ArrayList<String> ids = new ArrayList<>();
		doubleVals.forEach((id, value) -> ids.add(id + " : " + value));
		Collections.sort(ids);
		return ids;
	}
	public List<String> getDoubleIDs() {
		ArrayList<String> ids = new ArrayList<>();
		doubleVals.forEach((id, value) -> ids.add(id));
		Collections.sort(ids);
		return ids;
	}
	/**
	 * Get a listing of double id : value pairs currently stored that meet the id regex request
	 *
	 * @return Readable listing of the doubles
	 */
	public String getMatchingDoubles(String id, String eol) {
		return doubleVals.entrySet().stream().filter(e -> e.getKey().matches( id ))
												.sorted(Map.Entry.comparingByKey())
												.map(e -> e.getKey() + " : " + e.getValue().toString())
												.collect(Collectors.joining(eol));
	}
	/* *********************************** T E X T S  ************************************************************* */
	public boolean hasText(String id){
		return texts.containsKey(id);
	}
	public boolean setText(String parameter, String value) {
		final String param=parameter.toLowerCase();

		if( param.isEmpty()) {
			Logger.error("Empty param given");
			return false;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		boolean created = texts.put(parameter, value)==null;

		if( !doubleRequest.isEmpty()){
			var res = doubleRequest.get(param);
			if( res != null)
				res.forEach( wr -> wr.writeLine(param + " : " + value));
		}
		return created;
	}
	public boolean updateText( String id, String value){
		if( texts.containsKey(id)) {
			texts.put(id, value);
			return true;
		}
		return false;
	}
	public String getText(String parameter, String def) {
		String result = texts.get(parameter);
		return result == null ? def : result;
	}
	public List<String> getTextPairs() {
		ArrayList<String> params = new ArrayList<>();
		texts.forEach((param, value) -> params.add(param + " : " + value));
		Collections.sort(params);
		return params;
	}
	public List<String> getTextIDs() {
		ArrayList<String> params = new ArrayList<>();
		texts.forEach((param, value) -> params.add(param));
		Collections.sort(params);
		return params;
	}
	/**
	 * Get a listing of text ids : value pairs currently stored that meet the id
	 * request
	 *
	 * @return Readable listing of the parameters
	 */
	public String getFilteredTexts(String id, String eol) {
		Stream<Entry<String, String>> stream;
		if (id.endsWith("*") && id.startsWith("*")) {
			stream = texts.entrySet().stream()
					.filter(e -> e.getKey().contains(id.substring(1, id.length() - 1)));
		} else if (id.endsWith("*")) {
			stream = texts.entrySet().stream()
					.filter(e -> e.getKey().startsWith(id.substring(0, id.length() - 1)));
		} else if (id.startsWith("*")) {
			stream = texts.entrySet().stream().filter(e -> e.getKey().endsWith(id.substring(1)));
		} else if (id.isEmpty()) {
			stream = texts.entrySet().stream();
		} else {
			stream = texts.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(id));
		}
		return stream.sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + " : " + e.getValue()).collect(Collectors.joining(eol));
	}
	/* ************************************ F L A G S ************************************************************* */
	public FlagVal getOrAddFlagVal( String id ){
		if( id.isEmpty())
			return null;

		var val = flagVals.get(id);
		if( val==null){
			flagVals.put(id,FlagVal.newVal(id));
		}
		return flagVals.get(id);
	}
	public Optional<FlagVal> getFlagVal( String flag){
		return Optional.ofNullable(flagVals.get(flag));
	}
	public boolean hasFlag( String flag){
		return flagVals.get(flag)!=null;
	}
	public boolean isFlagUp( String flag ){
		var f = flagVals.get(flag);
		if( f==null)
			Logger.warn("No such flag: "+flag);
		return f != null && f.isUp();
	}
	public boolean isFlagDown( String flag ){
		var f = flagVals.get(flag);
		if( f==null)
			Logger.warn("No such flag: "+flag);
		return f != null && !f.isDown();
	}

	/**
	 * Raises a flag
	 * @param flag The flags/bits to set
	 * @return True if this is a new flag/bit
	 */
	public boolean raiseFlag( String... flag ){
		int cnt = flagVals.size();
		for( var f : flag) {
			setFlagState(f,true);
		}
		return cnt!= flagVals.size();
	}
	/**
	 * Lowers a flag or clears a bool.
	 * @param flag The flags/bits to clear
	 * @return True if this is a new flag/bit
	 */
	public boolean lowerFlag( String... flag ){
		int cnt = flagVals.size();
		for( var f : flag){
			setFlagState(f,false);
		}
		return cnt!= flagVals.size();
	}
	/**
	 * Set the state of the flag
	 * @param id The flag id
	 * @param state The new state for the flag
	 * @return True if the state was changed, false if a new flag was made
	 */
	public boolean setFlagState( String id, boolean state){
		int size = flagVals.size();
		getFlagVal(id).ifPresentOrElse(f->f.setState(state),()->flagVals.put(id, FlagVal.newVal(id).setState(state)));
		return size==flagVals.size();
	}
	public ArrayList<String> listFlags(){
		return flagVals.entrySet().stream().map(ent -> ent.getKey()+" : "+ent.getValue()).collect(Collectors.toCollection(ArrayList::new));
	}
	/* ********************************* O V E R V I E W *********************************************************** */

	public String storeRTVals(Path settings){
		XMLfab fab = XMLfab.withRoot(settings,"dcafs","settings","rtvals");
		var keys = doubleVals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getKey).collect(Collectors.toList());
		for( var dv : keys ){
			var dd = doubleVals.get(dv);
			fab.selectOrAddChildAsParent("double","id",dv)
					.attr("unit",dd.unit)
					.up();
		}
		keys = texts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getKey).collect(Collectors.toList());
		for( var dt : keys ){
			fab.selectOrAddChildAsParent("text","id",dt).up();
		}
		keys = flagVals.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Entry::getKey).collect(Collectors.toList());
		for( var dt : keys ){
			fab.selectOrAddChildAsParent("flag","id",dt).up();
		}
		fab.build();
		return "New doubles/texts/flags added";
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

	public String getRequestList( String request ){
		String[] req = request.split(":");
		StringJoiner join = new StringJoiner("\r\n");
		join.setEmptyValue("None yet");
		switch (req[0]) {
			case "rtval": case "double": case "doubles":
				doubleRequest.forEach((rq, list) -> join.add(rq +" -> "+list.size()+" requesters"));
				break;
			case "texts":
				textRequest.forEach((rq, list) -> join.add(rq +" -> "+list.size()+" requesters"));
				break;
		}
		return join.toString();
	}
	public boolean addRequest(Writable writable, String[] req) {

		switch (req[0]) {
			case "rtval": case "double":
				var r = doubleRequest.get(req[1]);
				if( r == null) {
					doubleRequest.put(req[1], new ArrayList<>());
					Logger.info("Created new request for: " + req[1]);
				}else{
					Logger.info("Appended existing request to: " + r + "," + req[1]);
				}
				if( !doubleRequest.get(req[1]).contains(writable)) {
					doubleRequest.get(req[1]).add(writable);
					return true;
				}
				break;
			case "text":
				var t = textRequest.get(req[1]);
				if( t == null) {
					textRequest.put(req[1], new ArrayList<>());
					Logger.info("Created new request for: " + req[1]);
				}else{
					Logger.info("Appended existing request to: " + t + "," + req[1]);
				}
				if( !textRequest.get(req[1]).contains(writable)) {
					textRequest.get(req[1]).add(writable);
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
			setDouble(message,(double)result,false);
		}
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {

		switch( request[0] ){
			case "doubles": case "dv":
				return replyToDoublesCmd(request,html);
			case "texts": case "tv":
				return replyToTextsCmd(request,html);
			case "flags": case "fv":
				return replyToFlagsCmd(request,html);
			case "rtval": case "double":
				return addRequest(wr,request)?"Request added":"Failed request";
			case "rtvals":
				return replyToRtvalsCmd(request,wr,html);
			default:
				return "unknown command "+request[0]+":"+request[1];
		}
	}
	public boolean removeWritable(Writable writable ) {
		int size = doubleRequest.size()+textRequest.size();
		doubleRequest.forEach( (key, list) -> list.remove(writable));
		textRequest.forEach( (key, list) -> list.remove(writable));
		return size - (doubleRequest.size()+textRequest.size())!=0;
	}
	public String replyToTextsCmd( String[] request,  boolean html ){

		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		if( cmds.length>3){
			for( int a=3;a<cmds.length;a++){
				cmds[2]+=","+cmds[3];
			}
		}
		switch( cmds[0] ){
			case "?":
				var join = new StringJoiner(html?"<br>":"\r\n");
				join.add( " texts,? -> Show this message" )
						.add( " texts or texts:list -> Give a listing of currently stored texts")
						.add( " texts:new,id,value -> Create a new text (or update) with the given id/value")
						.add( " texts:update,id,value -> Update an existing text, do nothing if not found")
						.add( " texts:id,value -> Same as update, so don't call the text new or update...");
			case "list":
				return String.join(html?"<br>":"\r\n",getTextPairs());
			case "new": case "create":
				if( setText(cmds[1],cmds[2]) )
					return cmds[1]+" stored with value "+cmds[2];
				return cmds[1]+" updated with value "+cmds[2];
			case "update":
				if( updateText(cmds[1],cmds[2]) )
					return cmds[1]+" updated with value "+cmds[2];
				return "No such text found: "+cmds[1];
			default:
				if( updateText( cmds[0],cmds[1] ) ){
					return cmds[0]+" updated with value "+cmds[1];
				}
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	public String replyToFlagsCmd( String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		var join = new StringJoiner(html?"<br>":"\r\n");
		switch( cmds[0] ){
			case "?":
				join.add("flags or flags:list -> Give a listing of all current flags and their state")
						.add("flags:raise,id or flags:set,id -> Raises the flag/Sets the bit, created if new")
						.add("flags:lower,id or flags:clear,id -> Lowers the flag/Clears the bit, created if new")
						.add("flags:toggle,id -> Toggles the flag/bit, not created if new");
			case "list":
				join.setEmptyValue("No flags yet");
				listFlags().forEach(join::add);
				return join.toString();
			case "new":
				if( cmds.length !=3)
					return "Not enough arguments, need flags:new,id,state or fv:new,id,state";
				setFlagState(cmds[1],Tools.parseBool(cmds[2],false));
				return "Flag created/updated "+cmds[1];
			case "raise": case "set":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:raise,id or flags:set,id";
				return raiseFlag(cmds[1])?"New flag raised":"Flag raised";
			case "lower": case "clear":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:lower,id or flags:clear,id";
				return lowerFlag(cmds[1])?"New flag raised":"Flag raised";
			case "toggle":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:toggle,id";

				if( !hasFlag(cmds[1]) )
					return "No such flag";

				if( isFlagUp(cmds[1])) {
					lowerFlag(cmds[1]);
					return "flag lowered";
				}
				raiseFlag(cmds[1]);
				return "Flag raised";
		}
		return "unknown command "+request[0]+":"+request[1];
	}
	public String replyToDoublesCmd(String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		double result;
		switch( cmds[0] ){
			case "?":
				var join = new StringJoiner(html?"<br>":"\r\n");
				join.add( " double:? -> Show this message" )
						.add( " doubles or doubles:list -> Give a listing of currently stored texts")
						.add( " doubles:new,id,value -> Create a new double (or update) with the given id/value")
						.add( " doubles:update,id,value -> Update an existing double, do nothing if not found")
						.add( " doubles:id,value -> Same as update, so don't call the double new or update...");
			case "list":
				return String.join(html?"<br>":"\r\n",getTextPairs());
			case "new": case "create":
				result = processExpression(cmds[2],true);
				if( Double.isNaN(result) )
					return "Failed to create new double";
				setDouble(cmds[1],result);
				return cmds[1]+" created/updated to "+result;
			case "alter":
				if( cmds.length<3)
					return "Not enough arguments: doubles:alter,id,param:value";
				var vals = cmds[2].split(":");
				if( vals.length==1)
					return "Incorrect param:value pair: "+cmds[2];
				return getDoubleVal(cmds[1]).map( d -> {
					if( vals[0].equals("scale")) {
						d.fractionDigits(NumberUtils.toInt(vals[1]));
						return "Scaling for " +cmds[1]+" set to " + d.digits + " digits";
					}else{
						return "Unknown param: "+vals[0];
					}
				}).orElse("No such DoubleVal");
			case "update":
				if( !hasDouble(cmds[1]) )
					return "No such id "+cmds[1];
				result = processExpression(cmds[2],false);
				if( Double.isNaN(result) )
					return "Unknown id(s) in the expression "+cmds[2];
				updateDouble(cmds[1],result);
				return cmds[1]+" updated to "+result;
			case "updategroup":
				int up = updateDoubleGroup(cmds[1], NumberUtils.createDouble(cmds[2]));
				if( up == 0)
					return "No double's updated";
				return "Updated "+up+" doubles";
			default:
				if( hasDouble(cmds[0]) ) {
					result = processExpression(cmds[0],false);
					if( Double.isNaN(result) )
						return "Unknown id(s) in the expression "+cmds[1];
					updateDouble(cmds[0],result);
					return cmds[0]+" updated to "+result;
				}
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	private double processExpression( String exp, boolean create ){
		double result=Double.NaN;

		exp = simpleParseRT(exp,create?"create":"");
		if( exp.isEmpty())
			return result;

		var parts = MathUtils.extractParts(exp);
		if( parts.size()==1 ){
			if( !NumberUtils.isCreatable(exp)) {
				if( hasDouble(exp) || create ) {
					result = getDouble(exp, 0, create);
				}else{
					return Double.NaN;
				}
			}else{
				result = NumberUtils.createDouble(exp);
			}
		}else if (parts.size()==3){
			if( !NumberUtils.isCreatable(parts.get(0))) {
				if( hasDouble(parts.get(0)) || create ) {
					parts.set(0, "" + getDouble(parts.get(0), 0, create));
				}else{
					return Double.NaN;
				}
			}
			if( !NumberUtils.isCreatable(parts.get(2))) {
				if( hasDouble(parts.get(2)) || create ) {
					parts.set(2, "" + getDouble(parts.get(2), 0, create));
				}else{
					return Double.NaN;
				}
			}
			result = Objects.requireNonNull(MathUtils.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new Double[]{});
		}else{
			try {
				result = MathUtils.simpleCalculation(exp, Double.NaN, false);
			}catch(IndexOutOfBoundsException e){
				Logger.error("Index out of bounds while processing "+exp);
				return Double.NaN;
			}
		}
		return result;
	}
	public String replyToRtvalsCmd( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a list of all rtvals options";

		if( request[1].isEmpty())
			return getFullList(html);

		String[] cmds = request[1].split(",");
		if( cmds.length==1 ){
			if ("store" .equals(cmds[0])) {
				return storeRTVals(settingsPath);
			} else {
				addRequest(wr, request);
			}
		}else if(cmds.length==2){
			switch(cmds[0]){
				case "group":  return getRTValsGroupList(cmds[1],html);
				case "groups":
					String groups = String.join(html?"<br>":"\r\n",getGroups());
					return groups.isEmpty()?"No groups yet":groups;
				case "name"	:  return getAllIDsList(cmds[1],html);
			}
		}
		return "unknown command: "+request[0]+":"+request[1];
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
				.forEach(dv -> join.add(space+dv.getName()+" : "+dv));
		texts.entrySet().stream().filter(ent -> ent.getKey().startsWith(group+"_"))
				.forEach( ent -> join.add( space+ent.getKey().split("_")[1]+" : "+ent.getValue()) );
		flagVals.entrySet().stream().filter(ent -> ent.getKey().startsWith(group+"_"))
				.forEach( ent -> join.add( space+ent.getKey().split("_")[1]+" : "+ent.getValue()) );
		return join.toString();
	}
	/**
	 * Get a listing of all stored variables that have the given name
	 * @param name The name of the variable or the string the name starts with if ending it with *
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getAllIDsList(String name, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Name: "+name+"</b>":TelnetCodes.TEXT_CYAN+"Name: "+name+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");

		String regex;
		if( name.contains("*")&& name.contains(".*")) {
			regex = name.replace("*", ".*");
		}else{
			regex=name;
		}
		doubleVals.values().stream().filter( dv -> dv.getName().matches(regex))
				.forEach(dv -> join.add(space+(dv.getGroup().isEmpty()?"":dv.getGroup()+" -> ")+dv.getName()+" : "+dv));
		texts.entrySet().stream().filter(ent -> ent.getKey().matches(regex))
				.forEach( ent -> join.add( space+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		flagVals.values().stream().filter(fv -> fv.getName().matches(regex))
				.forEach(fv -> join.add(space+(fv.getGroup().isEmpty()?"":fv.getGroup()+" -> ")+fv.getName()+" : "+fv));
		return join.toString();
	}
	public String getFullList(boolean html){
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Grouped</b>":TelnetCodes.TEXT_CYAN+"Grouped"+TelnetCodes.TEXT_YELLOW;
		String space = html?"  ":"  ";
		StringJoiner join = new StringJoiner(eol,getGroups().isEmpty()?"":title+eol,"");

		// Find & add the groups
		getGroups().forEach( group -> join.add(getRTValsGroupList(group,html)) );

		// Add the not grouped ones
		boolean ngDoubles = doubleVals.values().stream().anyMatch( dv -> dv.getGroup().isEmpty());
		boolean ngTexts = texts.keySet().stream().anyMatch(k -> k.contains("_"));
		boolean ngFlags = flagVals.keySet().stream().anyMatch(k -> k.contains("_"));

		if( ngDoubles || ngTexts || ngFlags) {
			join.add("");
			join.add(html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW);

			if (ngDoubles) {
				join.add(html ? "<b>Doubles</b>" : TelnetCodes.TEXT_BLUE + "Doubles" + TelnetCodes.TEXT_YELLOW);
				doubleVals.values().stream().filter(dv -> dv.getGroup().isEmpty())
						.forEach(dv -> join.add(space + dv.getName() + " : " + dv));
			}
			if (ngTexts) {
				join.add("");
				join.add(html ? "<b>Texts</b>" : TelnetCodes.TEXT_BLUE + "Texts" + TelnetCodes.TEXT_YELLOW);
				texts.entrySet().stream().filter(e -> !e.getKey().contains("_"))
						.forEach(e -> join.add(space + e.getKey() + " : " + e.getValue()));
			}
			if (ngFlags) {
				join.add("");
				join.add(html ? "<b>Flags</b>" : TelnetCodes.TEXT_BLUE + "Flags" + TelnetCodes.TEXT_YELLOW);
				flagVals.entrySet().stream().filter(e -> !e.getKey().contains("_"))
						.forEach(e -> join.add(space + e.getKey() + " : " + e.getValue()));
			}
		}
		return join.toString();
	}
	public List<String> getGroups(){
		var groups = doubleVals.values().stream()
				.map(DoubleVal::getGroup)
				.filter(group -> !group.isEmpty()).distinct().collect(Collectors.toList());
		texts.keySet().stream()
						.filter( k -> k.contains("_"))
						.map( k -> k.split("_")[0] )
						.distinct()
						.filter( g -> !groups.contains(g))
						.forEach(groups::add);
		flagVals.values().stream()
						.map(FlagVal::getGroup)
						.filter(group -> !group.isEmpty() )
						.distinct()
						.filter( g -> !groups.contains(g))
						.forEach(groups::add);

		Collections.sort(groups);
		return groups;
	}
}