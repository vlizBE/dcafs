package devices;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.database.SQLiteDB;
import util.database.SQLiteDB.RollUnit;
import util.gis.Positioning;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GAPS {
	
	/* GAPS */
    private final ArrayList<Positioning> gapsTracks = new ArrayList<>();
	private final ArrayList<Positioning> staticPositions = new ArrayList<>();
	private final ArrayList<Positioning[]> tracks = new ArrayList<>();
    private final Map<Integer,String> gapsNames = new HashMap<>();
    int time = 240;
	
	/* other */
	int cogsogWindow = 12;
	boolean debug = false;

	SQLiteDB positionData;

	public GAPS( Document xml, SQLiteDB db ) {
		this.positionData = db;
		this.readXMLsettings(xml);
		this.addTables();
	}
	public GAPS( Document xml ){
		this( xml, new SQLiteDB("gaps", Path.of("db","GAPS_"), "yyyy-MM-dd", 1, RollUnit.DAY) );
	}
	public void setDebug(){
		debug = true;
	}

	/* ********************************* X M L **********************************************/
	/**
	 * Check if GAPS informtion is in the given xml document
	 * @param xml The xml document
	 * @return True if it is
	 */
	public static boolean inXML(Document xml){
		Element gapsEle = XMLtools.getFirstElementByTag( xml, "gaps");
		return gapsEle != null;
	}
	/**
	 * Reads the settings from the xml (mostly default names for id's)
	 * @param xml The xml document
	 * @return True of something was read
	 */
	public boolean readXMLsettings( Document xml ){
		
		Element gapsEle = XMLtools.getFirstElementByTag( xml, "gaps");
		if( gapsEle == null )
			return false;

		for( Element ele : XMLtools.getChildElements( gapsEle, "transponder")){
        	int id = XMLtools.getIntAttribute(ele, "id", -1);
        	String value = ele.getFirstChild().getTextContent();
        	addDefaultTitle(id, value);
        	Logger.info("Adding transponder: "+id+"->"+value);
        }
        for( Element ele : XMLtools.getChildElements( gapsEle, "static")){
        	int id = XMLtools.getIntAttribute(ele, "id", -1);
        	double lat = XMLtools.getDoubleAttribute(ele, "lat", -1);
        	double lon = XMLtools.getDoubleAttribute(ele, "lon", -1);
        	String value = ele.getFirstChild().getTextContent();
        	addStaticPosition(id, lat, lon, value);
        	addDefaultTitle(id, value);
        	Logger.info("Adding static: "+id+"->"+value);        	
		}
		for( Element ele : XMLtools.getChildElements( gapsEle, "track")){
			int id = XMLtools.getIntAttribute(ele, "id", -1);
        	String[] from = XMLtools.getStringAttribute(ele, "from", "").split(";");
			String[] to = XMLtools.getStringAttribute(ele, "to", "").split(";");
			String title =  ele.getFirstChild().getTextContent();

			if( from.length ==2 && to.length==2 ){
				double fromLat = Tools.parseDouble(from[0], -999);
				double fromLon = Tools.parseDouble(from[1], -999);
				double toLat = Tools.parseDouble(to[0], -999);
				double toLon = Tools.parseDouble(to[1], -999);
				addTrack( id, title, fromLat,fromLon,toLat,toLon);
				addDefaultTitle(id, title);
			}
			
		}
		return true;
	}
	/* **************************************************************************************/
	private void addTables(){
		
		//static String TABLE = "CREATE TABLE if not exists 'PositionData' ('TimeStamp' TEXT NOT NULL,'ID' INTEGER, 'Longitude' REAL,'Latitude' REAL,'Depth' REAL,'CoG' REA,'SoG' REALL, 'Travel' REAL);"
		positionData.addTableIfNotExists("PositionData")
				.addTimestamp("TimeStamp").isNotNull()
				.addInteger("ID")
				.addReal("Longitude").addReal("Latitude").addReal("Depth").addReal("CoG").addReal("SoG").addReal("Travel");
		positionData.addTableIfNotExists("ReferenceData")
						.addInteger("ID").isPrimaryKey()
						.addText("title");
		positionData.createContent(false);
		Logger.warn("Database created for GAPS, don't forget to add it to the manager.");
	}
	public SQLiteDB getSQLitDB( ){
		return positionData;
	}
	/* **************************************************************************************************************/
	/**
	 * Adds a default title to a certain transponder id
	 * @param id The id of the transponder
	 * @param title The title to give it
	 */
	public void addDefaultTitle( int id, String title ){
		this.gapsNames.put(id, title);
		positionData.doDirectInsert("ReferenceData", id,title);
		//positionData.addInsert( Insert.into("ReferenceData").addNr(id).addText(title).create())
	}

	public String getTitle( int id ) {		
		return gapsNames.getOrDefault(id, "?");
	}
	public void setMaxHistoryAge( String age ) {
		time = TimeTools.parsePeriodStringToSeconds(age);
		Logger.info("History age set to "+time+"s.");
	}
	/* **************************************************************************************************************/
	/**
	 * Add a static position to the list of transponders
	 * 
	 * @param id The id of the transponder
 	 * @param lat Latitude in degrees
	 * @param lon Longitude in degrees
	 * @param title Title for this static position
	 */
	public void addStaticPosition( int id, double lat, double lon, String title ) {
		Positioning p = new Positioning(id,lat,lon);
		p.setTitle(title);
		staticPositions.add(p);
	}
	/**
	 * Get the positioning of all that statics
	 * @return List of all the positionings
	 */
	public List<Positioning> getStatics( ){
		return this.staticPositions;
	}
	public void addTrack( int id, String title, double fromLat,double fromLon,double toLat,double toLon){
		Positioning[] p = {new Positioning(id, fromLat, fromLon),new Positioning(id, toLat, toLon)};
		p[0].setTitle(title);p[1].setTitle(title);
		Logger.info("Adding track with id="+id+" between ("+fromLat+","+fromLon+") and ("+toLat+","+toLon+")" );
		tracks.add(p);
	}
	public double getDistanceToTrack( int trackID, int transponderID ){
		Positioning trackStart=null;
		Positioning trackEnd=null;

		for( Positioning[] p : tracks ){
			if( p[0].getID() == trackID){
				trackStart = p[0];
				trackEnd = p[1];
			}
		}
		if( trackStart == null ){
			return -999;
		}
		Positioning transp = this.getPosition(transponderID);
		if( transp == null ){
			Logger.warn("No transponder found");
			return -999;
		}

		double diffBearing = trackStart.bearingTo(transp)-trackStart.bearingTo(trackEnd);
		
		boolean dir = diffBearing >= 0;

		diffBearing = Math.abs(diffBearing);
		double distStartTrans = trackStart.distanceTo(transp);

		double shortest = distStartTrans*Math.sin(Math.toRadians(diffBearing));
		return Tools.roundDouble( shortest ,1)*(dir?-1:1);
	}
	/* *************************************************************************************************************/
	/**
	 * Set the window in which data is averaged to calculate the CoG and SoG
	 * @param seconds Averaging window in seconds
	 */
	public void setCoGandSogWindow( int seconds){
		cogsogWindow=seconds;
	}
	/**
	 * Adds a position of a transponder (by id) 
	 * @param id The id of the transponder
 	 * @param timestamp The timestamp this position was determined
 	 * @param lat Latitude in degrees
	 * @param lon Longitude in degrees
	 * @param depth Depth in meters
	 * @return True if this was the first occurrence of this transponder
	 */
	public boolean addPosition( int id, String timestamp, double lat, double lon, double depth ){
		int index = -1;
		boolean isNew = false;
		for( int a=0;a<gapsTracks.size();a++){
			Positioning p = gapsTracks.get(a);
			if( p.getID()==id )
				index = a;			
		}
		double distance=0;
		double cog = -999;
		double sog=-999;
		if( index == -1){
			Positioning p = new Positioning(id,lat,lon,depth);
			p.setCogSoGwindow(cogsogWindow);
			p.setMaxHistoryTime(time);
			p.setTitle(gapsNames.get(id));
			gapsTracks.add(p);
			isNew = true;
		}else{
			distance = gapsTracks.get(index).updatePosition(lat, lon, depth);
			gapsTracks.get(index).setTitle(gapsNames.get(id));	
			cog = gapsTracks.get(index).getCoG();	
			sog = gapsTracks.get(index).getSoG()[0];	
		}	
					
		//String query = "INSERT into PositionData (Timestamp,Latitude,Longitude,Depth,CoG,SoG,Travel,ID) VALUES ( '"+timestamp+"'"+line.replace(";", ",")+","+id+");"
		positionData.doDirectInsert( "PositionData", null, id,lon,lat,depth,cog,sog,distance);
		return isNew;
	}
	/* **************************************************************************************************************/
	/**
	 * Get the WPL sentence for all transponders
	 * @return WPL sentence for all transponders
	 */
	public String getAllWPL( ){
		StringBuilder b = new StringBuilder();
		for( Positioning p : gapsTracks)
			b.append(p.generateWPL()).append("\r\n");
		String res = b.toString();		
		return res.substring(0,res.length()-2);
	}
	/**
	 * Get the WPL sentence for a specific transponder
	 * @param index The index of the wanted transponder 
	 * @return WPL formatted information on the transponder
	 */
	public String getWPL( int index ){
		if( index == -1 ){
			Positioning p = new Positioning( 1,55.494753,-15.8032733+Math.random()*0.5 );
			Positioning l = new Positioning( 2,55.494753,15.8032733+Math.random()*0.5 );
			return p.generateWPL()+"\r\n"+l.generateWPL();
		}
		if( index >=gapsTracks.size())
			return "";
		return gapsTracks.get(index).generateWPL();
	}
	/* **********************************  T R A N S P O N D E R **************************************/
	/**
	 * Get the amount of transponders tracked
	 * @return The amount of tracked transponders
	 */
	public int getTransponderCount() {
		return gapsNames.size();
	}
	/**
	 * Get the depth of a certain transponder
	 * @param id The id of the transponder
 	 * @return The depth in meters
	 */
	public double getTransponderDepth(int id) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id && p.getAge() < 60)
				return p.getDepth();
		}
		return -1;
	}
	/**
	 * Get the Latitude of a certain transponder
	 * @param id The id of the transponder
	 * @return Langitude in degrees
	 */
	public double getTransponderLat(int id) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id && p.getAge() < 60)
				return p.getLatitude();
		}
		return -1;
	}
	/**
	 * Get the Longitude of a certain transponder
	 * @param id The id of the transponder
	 * @return Longitude in degrees
	 */
	public double getTransponderLon(int id) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id && p.getAge() < 60)
				return p.getLongitude();
		}
		return -1;
	}
	public String getTransDepthString(int id) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id)
				return p.getTitle()+": "+p.getDepth()+"m ["+p.getDescentRate(2)+"m/s]";
		}
		return "";
	}
	public String getTransponderPosition( int id,String degr ){			
		for( Positioning p : gapsTracks ){
			if( p.getID() == id ){
				String title = p.getTitle().isBlank()?""+p.getID():p.getTitle();
				return title+": "+p.getPositionString().replace("°", degr)+" (Age:"+p.getAge()+"s)";
			}
		}
		return "1:0*.0 0*0.0 (Age:-1s)";		
	}	
	/* */

	/* **************************************************************************************/
	/**
	 * Get the bearing between two transponders
	 * @param fromID Starting point transponder
	 * @param toID Destination point transponder
	 * @return Bearing in degrees or -99 if not possible
	 */
	public double getBearing( int fromID,int toID) {
		Positioning from = this.getPosition(fromID);
		Positioning to = this.getPosition(toID);
		if(from==null || to == null)
			return -99;
		return from.bearingTo(to);
	}
	/**
	 * Get the distance between two transponders
	 * 
	 * @param fromID Starting point transponder
	 * @param toID Destination point transponder
	 * @return The distance in km between the two transponders
	 */
	public double getDistance( int fromID, int toID ) {
		Positioning from = this.getPosition(fromID);
		Positioning to = this.getPosition(toID);
		if( to == null ){
			double dd = getDistanceToTrack(fromID,toID);
			if( dd != -999)
				return dd;
		}
		if(from==null || to == null)
			return -99;
		return from.distanceTo(to);
	}
	/**
	 * Get the distance between two transponders
	 * 
	 * @param fromID Starting point transponder
	 * @param toID Destination point transponder
	 * @param includeDepth Whether or not to include the depth in the answer
	 * @return The distance in km between the two transponders in a formatted string
	 */
	public String getDistanceString(int fromID, int toID, boolean includeDepth) {
		double d=0;
		double d1=0;
		
		Positioning from = this.getPosition(fromID);
		d = from.getDepth();
		Positioning to = this.getPosition(toID);		
		d1=to.getDepth();
		
		double x = from.distanceTo(to)*1000;
		double bea = from.bearingTo(to);
		
		if( includeDepth ) {
			double y = Math.abs(d-d1);
			double z = Math.sqrt( Math.pow(x, 2)+Math.pow(y, 2));
			if( d==-1 || d1==-1 || z >500)
				return "-1m";
			return Tools.roundDouble( z,2)+"m @ "+bea+"°";
		}else {
			return Tools.roundDouble( x,2)+"m | "+bea+"°";
		}

	}
	public List<Positioning> getPositions(){
		return this.gapsTracks;
	}
	public int getID( String title ) {
		for( Positioning p : gapsTracks) {
			if( p.getTitle().equalsIgnoreCase(title)) 
				return p.getID();
		}
		return -1;
	}
	public String[] getActiveTitles() {
		ArrayList<String> titles = new ArrayList<>();
		for( Positioning p : gapsTracks) {
			titles.add(p.getTitle());
		}
		return titles.toArray(new String[0]);
	}
	public Positioning getPosition( int id ) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id) 
				return p;
		}
		for( Positioning p : this.staticPositions) {
			if( p.getID()==id) 
				return p;
		}
		return null;
	}
	public Positioning getPosition( String name ) {
		for( Positioning p : gapsTracks ) {
			if( p.getTitle().equalsIgnoreCase(name))
				return p;
		}
		return null;
	}
	public synchronized void clearHistory() {
		for( Positioning p : gapsTracks)
			p.clearHistory();
	}
	public double getCourse( int id ) {
		for( Positioning p : gapsTracks) {
			if( p.getID()==id) {
				return p.getCoG();
			}
		}
		for( Positioning p : staticPositions) {
			if( p.getID()==id) {
				return p.getCoG();
			}
		}
		return 0;
	}
	/* **************************************************************************************/
	/**
	 * Set the heading of a followed object
 	 * @param title The title of the transponder
	 * @param heading The new heading in degrees
	 * @return False if no transponder was found
	 */
	public boolean setHeading( String title, double heading) {
		for( Positioning p : gapsTracks) {
			if( p.getTitle().equalsIgnoreCase(title)) {
				p.setHeading(heading);
				return true;
			}
		}
		Logger.warn("Title not found! <"+title+">");
		return false;
	}
	/**
	 * Get the heading of a transponder
 	 * @param title The name of the transponder
	 * @return Heading to the transponder if found 0 if not (-999 gives other issues)
	 */
	public double getHeading( String title ) {
		for( Positioning p : gapsTracks) {
			if( p.getTitle().equalsIgnoreCase(title)) {
				return p.getHeading();
			}
		}
		return 0;
	}
	/* **************************************************************************************************************/
	/**
	 * Get the current available data formatted in a humanly readable way
 	 * @return The formatted data
	 */
	public String getFormattedData(){
		StringBuilder b = new StringBuilder();		
		for( Positioning p : gapsTracks ){
			String prefix = ""+p.getID();
			if( !p.getTitle().isBlank() )
				prefix = p.getTitle()+"["+p.getID()+"]";
			b.append(prefix).append("\t").append(p.getPositionString()).append("\t").append(p.getDepth()).append("m\t").append(p.getAge()).append("s").append("\r\n");
		}
		if( b.toString().isBlank() )
			return "No positions received yet...";
		return b.toString();
	}
	/* **************************************************************************************************************/
	/**
	 * Get the response for a TransServer calc: request
	 * @param request The request given
	 * @param timestamp Current timestamp
	 * @return Response to the request or unknown command if not understood 
	 */
	public String getCalcResponse(String request, String timestamp) {
		if( request.startsWith("gapsid") ){
			request=request.replace("gapsid", "gaps");
			return request+" "+ timestamp +" "+ getTransponderPosition(Tools.parseInt(request.substring(4), -1),"*"); 
		}
		if( request.startsWith("gapswpl") ){
			int nr = Tools.parseInt( request.substring(7),-1);
			if( nr == -1)
				return getAllWPL();
			return getWPL(nr);
		}
		switch( request ){
			case "overlay2":  return "gapsall;"+ timestamp 
												+";"+ getTransponderPosition(0,"*")
												+";"+ getTransponderPosition(1,"*")
												+";"+ getTransponderPosition(2,"*")
												+";"+ getTransponderPosition(3,"*");
			case "gapsfake":   return  getWPL(-1); 
			default: return "unknown command";
		}
	}
	/* **************************************************************************************************************/
	/**
	 * Process a pre split nmea sag message
	 * @param split The nmea message split on ,
	 * @return The position deduced from the SAG message
	 */
	public void processSAG( String[] split ){
		
		if( split.length != 13)
			return;

		int id = Tools.parseInt(split[6], -1);
		double latd = Tools.parseDouble(split[7].substring(0, 2),-999);
		double latm = Tools.parseDouble(split[7].substring(2),-999);
		double lat = Tools.roundDouble( latd+latm/60, 8);
		
		if( split[8].equals("S"))
			lat *= -1;
		
		double lond = Tools.parseDouble(split[9].substring(0, 3),-999);
		double lonm = Tools.parseDouble(split[9].substring(3),-999);
		double lon = Tools.roundDouble( lond+lonm/60, 8);
		
		if( split[10].equals("W"))
			lon *= -1;
		
		double depth = Tools.parseDouble(split[12],-999);		
		
		String datetime = split[5]+split[4]+split[3]+" "+split[2];
		String form = TimeTools.reformatDate(datetime, "yyyyMMdd HHmmss.SSS", "yyyy-MM-dd HH:mm:ss.SSS");
		
		if( id >= 0 ){
			addPosition(id, form, lat, lon, depth);
		}
	}
}
