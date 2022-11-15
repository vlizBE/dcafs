package util.data;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A storage class
 *
 */
public class RealtimeValues implements Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, RealVal> realVals = new ConcurrentHashMap<>(); 		 // doubles
	private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // integers
	private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>(); 			 // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); 		 // booleans

	private final IssuePool issuePool;

	/* Data update requests */
	private final HashMap<String, List<Writable>> textRequest = new HashMap<>();

	/* General settings */
	private final Path settingsPath;

	/* Patterns */
	private final Pattern words = Pattern.compile("[a-zA-Z]+[_:\\d]*[a-zA-Z\\d]+\\d*"); // find references to *val

	/* Other */
	private final BlockingQueue<Datagram> dQueue; // Used to issue triggered cmd's

	boolean readingXML=false;

	public RealtimeValues( Path settingsPath,BlockingQueue<Datagram> dQueue ){
		this.settingsPath=settingsPath;
		this.dQueue=dQueue;

		readFromXML( XMLfab.withRoot(settingsPath,"dcafs", "rtvals") );
		issuePool = new IssuePool(dQueue, settingsPath,this);
	}

	/* ************************************ X M L ****************************************************************** */
	/**
	 * Read an rtvals node
	 */
	public void readFromXML( Element rtvalsEle ){

		double defReal = XMLtools.getDoubleAttribute(rtvalsEle,"realdefault",Double.NaN);
		String defText = XMLtools.getStringAttribute(rtvalsEle,"textdefault","");
		boolean defFlag = XMLtools.getBooleanAttribute(rtvalsEle,"flagdefault",false);
		int defInteger = XMLtools.getIntAttribute(rtvalsEle,"integerdefault",-999);

		readingXML=true;
		if( XMLtools.hasChildByTag(rtvalsEle,"group") ) {
			XMLtools.getChildElements(rtvalsEle, "group").forEach(
					rtval -> {
						// Both id and name are valid attributes for the node name that forms the full id
						var groupName = XMLtools.getStringAttribute(rtval, "id", ""); // get the node id
						groupName = XMLtools.getStringAttribute(rtval, "name", groupName);
						for (var groupie : XMLtools.getChildElements(rtval)) { // Get the nodes inside the group node
							// First check if id is used
							processRtvalElement(groupie, groupName, defReal, defText, defFlag, defInteger);
						}
					}
			);
		}else{ // Rtvals node without group nodes
			XMLtools.getChildElements(rtvalsEle).forEach(
					ele -> processRtvalElement(ele,"",defReal,defText,defFlag,defInteger)
			);
		}
		readingXML=false;
	}
	public void readFromXML( XMLfab fab ){
		readFromXML(fab.getCurrentElement());
	}
	/**
	 * Process a Val node
	 * @param rtval The node to process
	 * @param group The group of the Val
	 * @param defReal The default real value
	 * @param defText The default text value
	 * @param defFlag The default boolean value
	 */
	private void processRtvalElement(Element rtval, String group, double defReal, String defText, boolean defFlag, int defInteger ){
		String name = XMLtools.getStringAttribute(rtval,"name","");
		name = XMLtools.getStringAttribute(rtval,"id",name);
		if( name.isEmpty())
			name = rtval.getTextContent();
		String id = group.isEmpty()?name:group+"_"+name;

		switch (rtval.getTagName()) {
			case "double", "real" -> {
				RealVal rv;
				if( !hasReal(id) ) {
					rv = RealVal.build(rtval,group,defReal);
					if( rv!=null)
						realVals.put(rv.id(),rv);
				}else{
					rv = getRealVal(id).map( r -> r.alter(rtval,defReal)).orElse(null);
				}
				if( rv!=null && rv.hasTriggeredCmds() )
					rv.enableTriggeredCmds(dQueue);

			}
			case "integer", "int" -> {
				IntegerVal iv;
				if( !hasInteger(id) ) {
					iv = IntegerVal.build(rtval,group,defInteger);
					if( iv!=null)
						integerVals.put(iv.id(),iv);
				}else{
					iv = getIntegerVal(id).map( i -> i.alter(rtval, defInteger)	).orElse(null);
				}
				if( iv!=null && iv.hasTriggeredCmds() )
					iv.enableTriggeredCmds(dQueue);
			}
			case "flag" -> {
				FlagVal fv;
				if( !hasFlag(id)){
					fv = FlagVal.build(rtval,group,defFlag);
					if( fv!=null)
						flagVals.put(fv.id(),fv);
				}else{
					fv = getFlagVal(id).map( f -> f.alter(rtval,defFlag)).orElse(null);
				}
				if( fv!=null && fv.hasTriggeredCmds() )
					fv.enableTriggeredCmds(dQueue);
			}
			case "text" -> setText(id, XMLtools.getStringAttribute(rtval, "default", defText));
		}
	}
	/* ************************************* P A R S I N G ********************************************************* */
	/**
	 * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps.
	 * If anything goes wrong, the 'error' will be returned. If this is set to ignore if something is not found it
	 * will be replaced according to the type: real-> NaN, int -> Integer.MAX
	 * @param line The line to parse
	 * @param error The line to return on an error or 'ignore' if errors should be ignored
	 * @return The (possibly) altered line
	 */
	public String simpleParseRT( String line,String error ){

		var found = words.matcher(line).results().map(MatchResult::group).toList();
		for( var word : found ){
			String replacement;
			if( word.contains(":")){ // Check if the word contains a : with means it's {d:id} etc
				var id = word.split(":")[1];

				replacement = switch (word.charAt(0) ){
					case 'd','r' -> {
						if( !hasReal(id) ){
							Logger.error("No such real "+id+", extracted from "+line); // notify
							if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
								yield error;
						}
						yield ""+getReal(id,Double.NaN);
					}
					case 'i' -> {
						if( !hasInteger(id) ) { // ID found
							Logger.error("No such integer "+id+", extracted from "+line); // notify
							if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
								yield error;
						}
						yield "" + getInteger(id,Integer.MAX_VALUE);
					}
					case 'f'-> {
						if (!hasFlag(id)) {
							Logger.error("No such flag " + id + ", extracted from " + line);
							if (!error.equalsIgnoreCase("ignore"))
								yield error;
						}
						yield getFlagState(id) ? "1" : "0";
					}
					case 't', 'T' -> {
						var te = texts.get(id);
						if (te == null && word.charAt(0) == 'T') {
							texts.put(id, "");
							te = "";
						}
						if (te == null) {
							Logger.error("No such text " + id + ", extracted from " + line);
							if (!error.equalsIgnoreCase("ignore"))
								yield error;
						}
						yield te;
					}
					default -> {
						Logger.error("No such type: "+word.charAt(0));
						yield error;
					}
				};
			}else { // If it doesn't contain : it could be anything...
				if (hasReal(word)) { //first check for real
					replacement = "" + getReal(word,Double.NaN);
 				} else { // if not
					if( hasInteger(word)){
						replacement = ""  + getInteger(word,-999);
					}else {
						if (hasText(word)) { //next, try text
							replacement = getText(word,"");
						} else if (hasFlag(word)) { // if it isn't a text, check if it's a flag
							replacement = getFlagState(word) ? "1" : "0";
						} else{
							Logger.error("Couldn't process " + word + " found in " + line); // log it and abort
							return error;
						}
					}
				}
			}
			assert replacement != null;
			if( replacement.equalsIgnoreCase(error))
				return error;
			if( !replacement.isEmpty() )
				line = line.replace(word,replacement);
		}
		return line;
	}

	/**
	 * Stricter version to parse a realtime line, must contain the references within { }
	 * Options are:
	 * - RealVal: {d:id} and {real:id}
	 * - FlagVal: {f:id} or {b:id} and {flag:id}
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
					case "d", "r", "double", "real" -> {
						var d = getReal(p[1], Double.NaN);
						if (!Double.isNaN(d) || !error.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : "" + d);
					}
					case "i", "int", "integer" -> {
						var i = getInteger(p[1], Integer.MAX_VALUE);
						if (i != Integer.MAX_VALUE)
							line = line.replace("{" + p[0] + ":" + p[1] + "}", "" + i);
					}
					case "t", "text" -> {
						String t = getText(p[1], error);
						if (!t.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
					}
					case "f", "b", "flag" -> {
						var d = getFlagVal(p[1]);
						var r = d.map(FlagVal::toString).orElse(error);
						if (!r.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
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
		if( line.toLowerCase().matches(".*[{][drfi]:.*") && !pairs.isEmpty()){
			Logger.warn("Found a {*:*}, might mean parsing a section of "+line+" failed");
		}
		return line;
	}
	/* ************************************ R E A L V A L ***************************************************** */

	/**
	 * Add a RealVal to the collection if it doesn't exist yet, optionally writing it to xml
	 * @param rv The RealVal to add
	 * @param xmlPath The path to the xml
	 * @return True if it was added
	 */
	public boolean addRealVal(RealVal rv, Path xmlPath) {
		if( rv==null) {
			Logger.error("Invalid RealVal received, won't try adding it");
			return false;
		}
		if( realVals.containsKey(rv.getID()))
			return false;

		if(xmlPath!=null&& Files.exists(xmlPath))
			rv.storeInXml(XMLfab.withRoot(xmlPath,"dcafs","rtvals"));
		return realVals.put(rv.getID(),rv)==null;
	}
	public boolean addRealVal(RealVal rv, boolean storeInXML) {
		return addRealVal(rv,storeInXML?settingsPath:null);
	}
	public boolean hasReal(String id){
		if( id.isEmpty()) {
			Logger.error("Realval -> Empty id given");
			return false;
		}
		return realVals.containsKey(id);
	}
	/**
	 * Retrieve a RealVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested RealVal or null if not found
	 */
	public Optional<RealVal> getRealVal( String id ){
		if( id.isEmpty()) {
			Logger.error("Realval -> Empty id given");
			return Optional.empty();
		}
		var opt = Optional.ofNullable(realVals.get(id));
		if( opt.isEmpty())
			Logger.error( "Tried to retrieve non existing realval "+id);
		return opt;
	}
	/**
	 * Sets the value of a real (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @return True if it was created
	 */
	public boolean updateReal(String id, double value) {
		return getRealVal(id).map( r -> {r.updateValue(value);return true;}).orElse(false);
	}
	/**
	 * Alter all the values of the reals in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of reals updated
	 */
	public int updateRealGroup(String group, double value){
		var set = realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(rv->rv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of a real
	 *
	 * @param id The id to get the value of
	 * @param defVal The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public double getReal(String id, double defVal) {
		var star = id.indexOf("*");
		var dOpt = getRealVal(star==-1?id:id.substring(0,star));

		if (dOpt.isEmpty()) {
			return defVal;
		}
		return dOpt.get().value(star==-1?"":id.substring(star+1));
	}
	/* ************************************ I N T E G E R V A L ***************************************************** */

	/**
	 * Retrieve a IntegerVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested IntegerVal or empty optional if not found
	 */
	public Optional<IntegerVal> getIntegerVal( String id ){
		if( integerVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing integerval "+id);
		return Optional.ofNullable(integerVals.get(id));
	}
	public boolean hasInteger( String id ){
		return integerVals.containsKey(id);
	}
	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param iv The IntegerVal to add
	 * @param xmlPath The path of the xml to store it in
	 * @return The requested or newly made IntegerVal
	 */
	public IntegerVal addIntegerVal( IntegerVal iv, Path xmlPath ){
		if( iv==null) {
			Logger.error("Invalid IntegerVal given, can't add it");
			return null;
		}
		var val = integerVals.get(iv.getID());
		if( !integerVals.containsKey(iv.getID())){
			integerVals.put(iv.getID(),iv);
			if(xmlPath!=null && Files.exists(xmlPath)) {
				iv.storeInXml(XMLfab.withRoot(xmlPath, "dcafs", "rtvals"));
			}else if( xmlPath!=null){
				Logger.error("No such file found: "+xmlPath);
			}
			return iv;
		}
		return val;
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The IntegerVal id
	 * @param value the new value
	 * @return True if it was updated
	 */
	public boolean updateInteger(String id, int value) {
		return getIntegerVal(id).map( i -> {i.value(value); return true;}).orElse(false);
	}

	/**
	 * Alter all the values of the ints in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of ints updated
	 */
	public int updateIntegerGroup(String group, int value){
		var set = integerVals.values().stream().filter( iv -> iv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(iv->iv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of an integer
	 *
	 * @param id The id to get the value of
	 * @return The value found or the bad value
	 */
	public int getInteger(String id, int defVal) {
		var star = id.indexOf("*");
		IntegerVal i = integerVals.get(star==-1?id:id.substring(0,star));
		if (i == null) {
			Logger.error("No such id: " + id);
			return defVal;
		}
		return i.intValue( star==-1?"":id.substring(star+1) );
	}
	/* *********************************** T E X T S  ************************************************************* */
	public boolean hasText(String id){
		return texts.containsKey(id);
	}
	public void addTextVal( String parameter, String value, Path xmlPath){
		boolean created = setText(parameter,value);

		if( created ){
			var fab = XMLfab.withRoot(xmlPath, "dcafs", "rtvals");
			var name = parameter.contains("_")?parameter.substring(parameter.indexOf("_")+1):parameter;
			fab.alterChild("group","id",parameter.contains("_")?parameter.substring(0,parameter.indexOf("_")):"")
					.down(); // Go down in the group

			if( fab.hasChild("text","id",name).isEmpty()) { // If this one isn't present
				fab.addChild("text").attr("id", name);
			}
			fab.build();
		}

	}

	/**
	 * Set the value of a textval and create it if it doesn't exist yet
	 * @param parameter The name/id of the val
	 * @param value The new content
	 * @return True if it was created
	 */
	public boolean setText(String parameter, String value) {

		if( parameter.isEmpty()) {
			Logger.error("Empty param given");
			return false;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		boolean created = texts.put(parameter, value)==null;

		if( !textRequest.isEmpty()){
			var res = textRequest.get(parameter);
			if( res != null)
				res.forEach( wr -> wr.writeLine(parameter + " : " + value));
		}
		return created;
	}

	/**
	 * Update an existing TextVal
	 * @param id The id of the TextVal
	 * @param value The new content
	 * @return True if found and updated
	 */
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

	/* ************************************** F L A G S ************************************************************* */
	public FlagVal addFlagVal( FlagVal fv ){
		return addFlagVal(fv,null);
	}
	public FlagVal addFlagVal( String group,String name ){
		return addFlagVal(FlagVal.newVal(group,name),null);
	}
	public FlagVal addFlagVal( FlagVal fv, Path xmlPath ){
		if( fv==null) {
			Logger.error("Invalid flagval given");
			return null;
		}
		if( !hasFlag(fv.id())){
			flagVals.put(fv.id(),fv);
			if(xmlPath!=null && Files.exists(xmlPath)) {
				fv.storeInXml(XMLfab.withRoot(xmlPath, "dcafs", "rtvals"));
			}else if( xmlPath==null){
				fv.storeInXml(XMLfab.withRoot(settingsPath, "dcafs", "rtvals"));
			}
			return fv;
		}
		return getFlagVal(fv.id()).get();
	}
	public Optional<FlagVal> getFlagVal( String flag ){
		if( flag.isEmpty())
			return Optional.empty();
		return Optional.ofNullable(flagVals.get(flag));
	}
	public boolean hasFlag( String flag ){
		return getFlagVal(flag).isPresent();
	}
	public boolean getFlagState(String flag ){
		var f = getFlagVal(flag);
		if( f.isEmpty()) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.get().isUp();
	}

	/**
	 * Raises a flag
	 * @param flags The flags/bits to set
	 * @return The amount of flags changed
	 */
	public int raiseFlag( String... flags ){
		int cnt=0;
		for( var f : flags) {
			int res = getFlagVal(f).map(fv->{fv.setState(true); return 1;}).orElse(0);
			if( res==0)
				Logger.error("No flags found with the name/id: "+f);
			cnt+=res;
		}
		return cnt;
	}
	/**
	 * Lowers a flag or clears a bool.
	 * @param flags The flags/bits to clear
	 * @return The amount of flags changed
	 */
	public int lowerFlag( String... flags ){
		int cnt=0;
		for( var f : flags) {
			int res = getFlagVal(f).map(fv->{fv.setState(false); return 1;}).orElse(0);
			if( res==0)
				Logger.error("No flags found with the name/id: "+f);
			cnt+=res;
		}
		return cnt;
	}
	public boolean setFlagState( String id, String state){
		if(!hasFlag(id)) {
			Logger.error("No such flagVal "+id);
			return false;
		}
		getFlagVal(id).map( fv->fv.setState(state));
		return true;
	}
	public boolean setFlagState( String id, boolean state){
		if(!hasFlag(id)) {
			Logger.error("No such flagVal "+id);
			return false;
		}
		getFlagVal(id).map( fv->fv.setState(state));
		return true;
	}
	/* ******************************************************************************************************/

	public int addRequest(Writable writable, String type, String req) {

		switch (type) {
			case "rtval", "double", "real" -> {
				var rv = realVals.get(req);
				if (rv == null)
					return 0;
				rv.addTarget(writable);
				return 1;
			}
			case "int", "integer" -> {
				var iv = integerVals.get(req);
				if (iv == null)
					return 0;
				iv.addTarget(writable);
				return 1;
			}
			case "text" -> {
				var t = textRequest.get(req);
				if (t == null) {
					textRequest.put(req, new ArrayList<>());
					Logger.info("Created new request for: " + req);
				} else {
					Logger.info("Appended existing request to: " + t + "," + req);
				}
				if (!textRequest.get(req).contains(writable)) {
					textRequest.get(req).add(writable);
					return 1;
				}
			}
			default -> Logger.warn("Requested unknown type: " + type);
		}
		return 0;
	}
	public void removeRequests( Writable wr ){
		realVals.values().forEach( rv -> rv.removeTarget(wr));
		integerVals.values().forEach( iv -> iv.removeTarget(wr));
		textRequest.forEach( (k,v) -> v.remove(wr));
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {

		switch( request[0] ){
			case "rv": case "reals":
				return replyToRealsCmd(request,html);
			case "texts": case "tv":
				return replyToTextsCmd(request,html);
			case "flags": case "fv":
				return replyToFlagsCmd(request,html);
			case "rtval": case "real": case "int": case "integer":
				int s = addRequest(wr,request[0],request[1]);
				return s!=0?"Request added to "+s+" realvals":"Request failed";
			case "rtvals": case "rvs":
				return replyToRtvalsCmd(request,html);
			case "":
				removeRequests(wr);
				return "";
			default:
				return "unknown command "+request[0]+":"+request[1];
		}
	}
	public boolean removeWritable(Writable writable ) {
		realVals.values().forEach(rv -> rv.removeTarget(writable));
		integerVals.values().forEach( iv -> iv.removeTarget(writable));
		textRequest.forEach( (key, list) -> list.remove(writable));
		return true;
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
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");
		switch( cmds[0] ){
			/* Info */
			case "?":
				join.add(ora+"Note: both tv and texts are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  tv:new,id,value"+reg+" -> Create a new text (or update) with the given id/value")
						.add( green+"  tv:update,id,value"+reg+" -> Update an existing text, do nothing if not found")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  tv:?"+reg+" -> Show this message")
						.add( green+"  tv:list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  tv:reqs"+reg+" -> Get a listing of the requests active");
				return join.toString();
			case "list":
				return String.join(html?"<br>":"\r\n",getRtvalsList(html,false,false,true, true));
			case "reqs":
				textRequest.forEach((rq, list) -> join.add(rq +" -> "+list.size()+" requesters"));
				return join.toString();
			/* Create or alter */
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

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var cmds = request[1].split(",");
		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");

		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both fv and flags are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  fv:raise,id"+reg+" or "+green+"flags:set,id"+reg+" -> Raises the flag/Sets the bit, created if new")
						.add( green+"  fv:lower,id"+reg+" or "+green+"flags:clear,id"+reg+" -> Lowers the flag/Clears the bit, created if new")
						.add( green+"  fv:toggle,id"+reg+" -> Toggles the flag/bit, not created if new")
						.add( green+"  fv:match,id,refid"+reg+" -> The state of the flag becomes the same as the ref flag")
						.add( green+"  fv:negated,id,refid"+reg+" -> The state of the flag becomes the opposite of the ref flag")
						.add( green+"  fv:addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id, current triggers:raised,lowered")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  fv:list"+reg+" -> Give a listing of all current flags and their state");
				return join.toString();
			case "list":
				return getRtvalsList(html,false,true,false,false);
			case "new": case "add":
				if( cmds.length <2)
					return "Not enough arguments, need flags:new,id<,state> or fv:new,id<,state>";
				if( hasFlag(cmds[1]))
					return "Flag already exists with that name";
				int pos = cmds[1].indexOf("_");
				String group = pos!=-1?cmds[1].substring(0,pos):"";
				String name = pos==-1?cmds[1]:cmds[1].substring(pos+1);
				var flag = FlagVal.newVal(group,name).setState(Tools.parseBool( cmds.length==3?cmds[2]:"false",false));
				addFlagVal(flag,settingsPath);
				return "Flag created/updated "+cmds[1];
			case "raise": case "set":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:raise,id or flags:set,id";
				return raiseFlag(cmds[1])==0?"No such flag":"Flag raised";
			case "match":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:match,id,targetflag";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];
				if( !hasFlag(cmds[2]))
					return "No such flag: "+cmds[2];
				getFlagVal(cmds[2]).ifPresent( to -> to.setState( getFlagVal(cmds[1]).get().isUp()));
				return "Flag matched accordingly";
			case "negated":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:negated,id,targetflag";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];
				if( !hasFlag(cmds[2]))
					return "No such flag: "+cmds[2];
				if( getFlagState(cmds[2])){
					lowerFlag(cmds[1]);
				}else{
					raiseFlag(cmds[1]);
				}
				return "Flag negated accordingly";
			case "lower": case "clear":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:lower,id or flags:clear,id";
				return lowerFlag(cmds[1])==0?"No such flag":"Flag lowered";
			case "toggle":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:toggle,id";
				if( !hasFlag(cmds[1]) )
					return "No such flag";
				return getFlagVal(cmds[1]).map(FlagVal::toggleState).orElse(false)?"Flag raised":"Flag Lowered";
			case "addcmd":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:addcmd,id,when:cmd";
				var fv = flagVals.get(cmds[1]);
				if( fv==null)
					return "No such real: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !fv.hasTriggeredCmds())
					fv.enableTriggeredCmds(dQueue);
				fv.addTriggeredCmd(when, cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("flag","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
			case "update":
				if( cmds.length < 3)
					return "Not enough arguments, fv:update,id,comparison";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];

		}
		return "unknown command "+request[0]+":"+request[1];
	}
	public String replyToRealsCmd(String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		double result;
		RealVal rv;
		String p0 = request[0];
		var join = new StringJoiner(html?"<br>":"\r\n");
		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both rv and reals are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  "+p0+":new,group,name"+reg+" -> Create a new real with the given group & name")
						.add( green+"  "+p0+":update,id,value"+reg+" -> Update an existing real, do nothing if not found")
						.add( green+"  "+p0+":addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id")
						.add( green+"  "+p0+":alter,id,param:value"+reg+" -> Alter some of the params of id, currently scale and unit are possible")
						.add( green+"  "+p0+":updategroup,groupid,value"+reg+" -> Update all RealVals that belong to the groupid with the given value")
						.add("").add( cyan+" Get info"+reg)
				  	    .add( green+"  "+p0+":?"+reg+" -> Show this message" )
						.add( green+"  "+p0+":list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  "+p0+":reqs"+reg+" -> Get a listing of all the requests currently active");
				return join.toString();
			case "list": return getRtvalsList(html,true,false,false, false);
			case "new": case "create":
				if( cmds.length!=3 )
					return "Not enough arguments given, "+request[0]+":new,id(,value)";

				rv = RealVal.newVal(cmds[1], cmds[2]);
				if( addRealVal(rv,true) ) {
					return "New realVal added " + rv.getID() + ", stored in xml";
				}
				return "Real already exists";
			case "alter":
				if( cmds.length<3)
					return "Not enough arguments: "+request[0]+":alter,id,param:value";
				var vals = cmds[2].split(":");
				if( vals.length==1)
					return "Incorrect param:value pair: "+cmds[2];
				var rvOpt = getRealVal(cmds[1]);
				if( rvOpt.isEmpty())
					return "No such real yet.";
				var realVal = rvOpt.get();
				var digger = XMLdigger.digRoot(settingsPath,"dcafs")
						.goDown("settings","rtvals")
						.goDown("group","name",realVal.group())
						.goDown("real","name",realVal.name());
				if( digger.isValid() ){
					switch( vals[0]){
						case "scale":
							realVal.scale(NumberUtils.toInt(vals[1]));
							break;
						case "unit":
							realVal.unit(vals[1]);
							break;
						default:
							return "unknown parameter: "+vals[0];
					}
					digger.alterAttrAndBuild(vals[0],vals[1]);
					return "Altered "+vals[0]+ " to "+ vals[1];
				}else{
					return "No valid nodes found";
				}
			case "update":
				if( !hasReal(cmds[1]) )
					return "No such id "+cmds[1];
				result = processExpression(cmds[2]);
				if( Double.isNaN(result) )
					return "Unknown id(s) in the expression "+cmds[2];
				updateReal(cmds[1],result);
				return cmds[1]+" updated to "+result;
			case "updategroup":
				int up = updateRealGroup(cmds[1], NumberUtils.createDouble(cmds[2]));
				if( up == 0)
					return "No real's updated";
				return "Updated "+up+" reals";
			case "addcmd":
				if( cmds.length < 3)
					return "Not enough arguments, rv:addcmd,id,when:cmd";
				rv = realVals.get(cmds[1]);
				if( rv==null)
					return "No such real: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !rv.hasTriggeredCmds())
					rv.enableTriggeredCmds(dQueue);
				rv.addTriggeredCmd(when,cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("real","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
			case "reqs":
				join.setEmptyValue("None yet");
				realVals.forEach((id, d) -> join.add(id +" -> "+d.getTargets()));
				return join.toString();
			default:
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	private double processExpression( String expr ){
		double result=Double.NaN;

		expr = parseRTline(expr,"");
		expr = expr.replace("true","1");
		expr = expr.replace("false","0");

		expr = simpleParseRT(expr,""); // Replace all references with actual numbers if possible

		if( expr.isEmpty()) // If any part of the conversion failed
			return result;

		var parts = MathUtils.extractParts(expr);
		if( parts.size()==1 ){
			result = NumberUtils.createDouble(expr);
		}else if (parts.size()==3){
			result = Objects.requireNonNull(MathUtils.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new Double[]{});
		}else{
			try {
				result = MathUtils.simpleCalculation(expr, Double.NaN, false);
			}catch(IndexOutOfBoundsException e){
				Logger.error("Index out of bounds while processing "+expr);
				return Double.NaN;
			}
		}
		return result;
	}
	public String replyToRtvalsCmd( String[] request, boolean html ){

		if( request[1].isEmpty())
			return getRtvalsList(html,true,true,true, true);

		String[] cmds = request[1].split(",");

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None Yet");

		if( cmds.length==1 ){
			switch (cmds[0]) {
				case "?" -> {
					join.add(cyan + " Interact with XML" + reg)
							.add(green + "  rtvals:store" + reg + " -> Store all rtvals to XML")
							.add(green + "  rtvals:reload" + reg + " -> Reload all rtvals from XML")
							.add("").add(cyan + " Get info" + reg)
							.add(green + "  rtvals:?" + reg + " -> Get this message")
							.add(green + "  rtvals" + reg + " -> Get a listing of all rtvals")
							.add(green + "  rtvals:groups" + reg + " -> Get a listing of all the available groups")
							.add(green + "  rtvals:group,groupid" + reg + " -> Get a listing of all rtvals belonging to the group")
							.add(green + "  rtvals:name,valname" + reg + " -> Get a listing of all rtvals with the given valname (independent of group)")
							.add(green + "  rtvals:resetgroup,groupid" + reg + " -> Reset the integers and real rtvals in the given group");
					return join.toString();
				}
				case "reload" -> {
					readFromXML(XMLfab.withRoot(settingsPath, "dcafs", "settings", "rtvals"));
					return "Reloaded rtvals";
				}
			}
		}else if(cmds.length==2){
			switch(cmds[0]){
				case "group":  return getRTValsGroupList(cmds[1],true,true,true,true,html);
				case "groups":
					String groups = String.join(html?"<br>":"\r\n",getGroups());
					return groups.isEmpty()?"No groups yet":groups;
				case "name"	:  return getAllIDsList(cmds[1],html);
				case "resetgroup":
					int a = updateIntegerGroup(cmds[1],-999);
					a += updateRealGroup(cmds[1],Double.NaN);
					return "Reset "+a+" vals.";
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
	public String getRTValsGroupList(String group, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts, boolean html) {
		String eol = html ? "<br>" : "\r\n";
		String title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
		if( group.isEmpty()){
			title = html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW;
		}

		StringJoiner join = new StringJoiner(eol, title + eol, "");
		join.setEmptyValue("None yet");
		ArrayList<NumericVal> nums = new ArrayList<>();
		if (showReals){
			nums.addAll(realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( showInts ){
			nums.addAll(integerVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( !nums.isEmpty()){
			nums.stream()
					.sorted((nv1, nv2) -> {
						if (nv1.order() != nv2.order()) {
							return Integer.compare(nv1.order(), nv2.order());
						} else {
							return nv1.name().compareTo(nv2.name());
						}
					})
					.map(nv -> {
						if( nv instanceof RealVal)
							return "  " + nv.name() + " : "+ nv.value()+ nv.unit();
						return "  " + nv.name() + " : "+ nv.intValue() + nv.unit();
					} ) // change it to strings
					.forEach(join::add);
		}
		if( showTexts ) {
			texts.entrySet().stream().filter(ent -> ent.getKey().startsWith(group + "_"))
					.map(ent -> "  " + ent.getKey().split("_")[1] + " : " + ent.getValue())
					.sorted().forEach(join::add);
		}
		if( showFlags ) {
			flagVals.values().stream().filter(fv -> fv.group().equalsIgnoreCase(group))
					.map(v -> "  " + v.name() + " : " + v) //Change it to strings
					.sorted().forEach(join::add); // Then add the sorted the strings
		}
		return join.toString();
	}
	/**
	 * Get the full listing of all reals,flags and text, so both grouped and ungrouped
	 * @param html If true will use html newline etc
	 * @return The listing
	 */
	public String getRtvalsList(boolean html, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts){
		String eol = html?"<br>":"\r\n";
		StringJoiner join = new StringJoiner(eol,"Status at "+ TimeTools.formatShortUTCNow()+eol+eol,"");
		join.setEmptyValue("None yet");

		// Find & add the groups
		for( var group : getGroups() ){
			var res = getRTValsGroupList(group,showReals,showFlags,showTexts,showInts,html);
			if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
				join.add(res).add("");
		}
		var res = getRTValsGroupList("",showReals,showFlags,showTexts,showInts,html);
		if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
			join.add(res).add("");

		if( !html)
			return join.toString();

		// Try to fix some symbols to correct html counterpart
		return join.toString().replace("°C","&#8451") // fix the °C
								.replace("m²","&#13217;") // Fix m²
								.replace("m³","&#13221;"); // Fix m³
	}
	/**
	 * Get a listing of all stored variables that have the given name, so across groups
	 * @param name The name of the variable or the string the name starts with if ending it with *
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getAllIDsList(String name, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Name: "+name+"</b>":TelnetCodes.TEXT_CYAN+"Name: "+name+TelnetCodes.TEXT_YELLOW;

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");

		String regex;
		if( name.contains("*") && !name.contains(".*")) {
			regex = name.replace("*", ".*");
		}else{
			regex=name;
		}
		realVals.values().stream().filter(rv -> rv.name().matches(regex))
				.forEach(rv -> join.add("  "+(rv.group().isEmpty()?"":rv.group()+" -> ")+rv.name()+" : "+rv));
		integerVals.values().stream().filter( iv -> iv.name().matches(regex))
				.forEach(iv -> join.add("  "+(iv.group().isEmpty()?"":iv.group()+" -> ")+iv.name()+" : "+iv));
		texts.entrySet().stream().filter(ent -> ent.getKey().matches(regex))
				.forEach( ent -> join.add( "  "+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		flagVals.values().stream().filter(fv -> fv.name().matches(regex))
				.forEach(fv -> join.add("  "+(fv.group().isEmpty()?"":fv.group()+" -> ")+fv.name()+" : "+fv));
		return join.toString();
	}
	/**
	 * Get a list of all the groups that exist in the rtvals
	 * @return The list of the groups
	 */
	public List<String> getGroups(){

		var groups = realVals.values().stream()
				.map(RealVal::group)
				.distinct()
				.filter(group -> !group.isEmpty())
				.collect(Collectors.toList());

		integerVals.values().stream()
				.map(IntegerVal::group)
				.filter(group -> !group.isEmpty() )
				.distinct()
				.forEach(groups::add);

		texts.keySet().stream()
				.filter( k -> k.contains("_"))
				.map( k -> k.split("_")[0] )
				.distinct()
				.filter( g -> !g.equalsIgnoreCase("dcafs"))
				.forEach(groups::add);

		flagVals.values().stream()
				.map(FlagVal::group)
				.filter(group -> !group.isEmpty() )
				.distinct()
				.forEach(groups::add);

		return groups.stream().distinct().sorted().toList();
	}
	/* ******************************** I S S U E P O O L ********************************************************** */

	/**
	 * Get a list of the id's of the active issues
	 * @return A list of the active issues id's
	 */
	public ArrayList<String> getActiveIssues(){
		return issuePool.getActives();
	}

	/**
	 * Get the IssuePool object
	 * @return The IssuePool object
	 */
	public IssuePool getIssuePool(){
		return issuePool;
	}
}