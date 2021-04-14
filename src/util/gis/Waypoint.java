package util.gis;

import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class Waypoint implements Comparable<Waypoint>{
	
	public enum STATE{INSIDE,OUTSIDE,ENTER,LEAVE,UNKNOWN}

	static DateTimeFormatter sqlFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	double lat;
	double lon;
	double range;
	double lastDist=-1;
	double depth;
	String name;
	int id;
	boolean inRange = false;
	boolean lastCheck = false;
	STATE state=STATE.UNKNOWN;
	boolean temp=false;
	
	double bearing=0;
	
	/* Stuff to determine enter and leave time if entered and left multiple times in succession */
	boolean active = false;
	boolean movementReady=false;
	LocalDateTime enterTime;
	LocalDateTime leaveTime;	
	double leaveDistance=0;
		
	/* Specific movements */
	ArrayList<Travel> travels = new ArrayList<>();

	public Waypoint ( int id, String name, double lat, double lon, double depth, double range ){
		this.name=name.trim();
		this.lat=lat;
		this.lon=lon;
		this.range=range;
		this.id=id;
		this.depth=depth;
		Logger.info( "New waypoint added: "+this.name+"\t"+lat+" "+lon+" Req Range:"+range);
	}

	public Waypoint ( int id, String name, double lat, double lon ){
		this(id,name,lat,lon,0,50);
	}	
	public Waypoint ( String name, double lat, double lon, double range ){
		this( 0, name,lat,lon,0,range);
	}
	public Waypoint( String name, double lat, double lon, double range,boolean temp){
		this( 0, name,lat,lon,0, range);
		this.temp=temp;
	}	
	public Waypoint( String name ){
		this.name=name;
	}
	public STATE currentState( LocalDateTime when, double lat, double lon ){
		lastDist = GisTools.roughDistanceBetween(lon, lat, this.lon, this.lat, 3)*1000;// From km to m				
		bearing = GisTools.calcBearing( lon, lat, this.lon, this.lat, 2 );
		
		boolean ok = lastDist < range;
		
		if( ok == lastCheck){			
			state = lastDist < range?STATE.INSIDE:STATE.OUTSIDE;
		}else{
			lastCheck = ok;
			state = ok?STATE.ENTER:STATE.LEAVE;
		}
		if( state == STATE.ENTER ){
			if( !active ){
				enterTime=when;
			}
			active = true;
		}
		if( state == STATE.LEAVE ){
			leaveTime=when;
			leaveDistance=lastDist;
		}
		if( state == STATE.OUTSIDE && lastDist > 600 && active){			
			active = false;
			movementReady=true;
		}
		return state;
	}
	public double getLastDistance( ) {
		return lastDist;
	}
	public String checkIt( LocalDateTime when, double lat, double lon ){
		String travel="";
		
		switch( currentState( when , lat, lon ) ){
			case ENTER:
			case LEAVE:
				travel = checkTravel();				
				break;
			case INSIDE:
				travel="IN";
				break;
			case OUTSIDE:
				String l = getLastMovement();
				if( !l.isBlank()){
					FileTools.appendToTxtFile("logs/stationMoves.txt", l+"\r\n");
					Logger.info( "Travel: "+l);
				}
				break;
			default:
				break;    		
		}
		return travel;
	}
	public String getLastMovement(){		
		if( movementReady ){
			movementReady=false;
			return "Arrived at "+name+" on "+enterTime.format(sqlFormat) + " and left on " + leaveTime.format(sqlFormat);
		}		
		return "";
	}
	public LocalDateTime getEnterTimeStamp(){
		return enterTime;
	}
	public LocalDateTime getLeaveTimeStamp(){
		return leaveTime;
	}
	public boolean isTemp(){
		return temp;
	}
	public String getName(){
		return name;
	}
	public String toString(){
		return toString( false, false, 0.0 );
	}
	public String toString(boolean coord, boolean simple, double sog){
		String m = "away";
		String nm=name;

		if(state==null)
			return "Unknown state";
		
		int sec=0;
		String suffix=".";
		if( sog != 0.0 ){
			sec = (int)(lastDist/(sog*0.514444444));
			if( sec > 0 ){
				suffix=" ("+TimeTools.convertPeriodtoString(sec, TimeUnit.SECONDS)+").";				
			}
		}
		if( lastDist != -1)
			m= Tools.metersToKm(lastDist,2);
		if( coord ) {
			nm += " ["+GisTools.fromDegrToDegrMin(lat,4,"°")+";"+GisTools.fromDegrToDegrMin(lon,4,"°")+"]";
		}
		if( simple ){
			switch(state){
				case ENTER: return "Entered in range to "+nm;
				case INSIDE:return "Inside "+nm;			
				case LEAVE: return "Left "+nm+" and "+leaveDistance+" from center.";
				case OUTSIDE:return "Outside "+nm+" and "+m+" from center"+suffix;
				default: return "Unknown state of "+nm+".";		
			}
		}else{
			String mess="";
			switch(state){
				case ENTER:   mess = "Entered "; break;
				case INSIDE:  mess = "Inside ";  break;			
				case LEAVE:   mess = "Left ";    break;
				case OUTSIDE: mess = "Outside "; break;
				default:      mess = "Unknown state of "+name+".";break;		
			}			
			return mess + name+" at " +TimeTools.formatLongUTCNow()+ " and "+m+" from center, bearing "+bearing+"° "+suffix;
		}
	}
	public String simpleList(String newline){
		StringJoiner join = new StringJoiner(newline,
					this.name+" ["+GisTools.fromDegrToDegrMin(lat,4,"°")+";"+GisTools.fromDegrToDegrMin(lon,4,"°")+"]\tRange:"+range+"m","");
		if( this.travels.isEmpty() ){
			join.add(" |-> No travel linked.");
		}else{
			for( Travel tr : travels){
				join.add(" |-> "+tr.toString());
			}
		}
		return join.toString();
	}
	public double getLat(){
		return lat;
	}
	public double getLon(){
		return lon;
	}
	public double getRange(){
		return range;
	}
	public boolean samePosition( double lat, double lon){
		return this.lat == lat && this.lon == lon;
	}
	public boolean isNear() {
		return state == STATE.INSIDE || state == STATE.ENTER;
	}
	public void updatePosition( double lat, double lon ){
		this.lat=lat;
		this.lon=lon;
	}
	public void setName( String name ){
		this.name=name;
	}
	/***********************************************************************************/
	/***********************************************************************************/
	/**
	 * Generate a WPL sentence based on this waypoint
	 * @return The generated WPL sentence
	 */
	public String generateWPL(){
		/*
		 * WPL Waypoint Location
				1      2   3      4   5   6
				|      |   |      |   |   |
			$--WPL,llll.ll,a,yyyyy.yy,a,c--c*hh
			1) Latitude
			2) N or S (North or South)
			3) Longitude
			4) E or W (East or West)
			5) Waypoint Name
			6) Checksum
		 */
		
		String latWPL = GisTools.parseDegreesToGGA(this.lat)+","+(this.lat>0?"N":"S")+",";		
		String lonWPL = GisTools.parseDegreesToGGA(this.lon)+","+(this.lon>0?"E":"W")+",";
		if( this.lon < 100)
			lonWPL = "0"+lonWPL;
		String result = "$GPWPL,"+latWPL+lonWPL+name+"*";
		result += MathUtils.getNMEAchecksum(result+"00");
		return result;
	}
	/***********************************************************************************/
	/***********************************************************************************/
	/**
	 * Adds a possible travel to the waypoint
	 * 
	 * @param name The name of the travel
	 * @param dir The direction either in(or enter) or out( or leave)
	 * @param minb Minimum bearing at the time of dir to be considered correct
	 * @param maxb Maximum bearing at the time of dir to be considered correct
	 */
	public void addTravel( String name, String dir, double minb, double maxb ){
		travels.add(new Travel(name, dir, minb, maxb));
		Logger.info("Added travel named "+name+" to waypoint "+this.name);
	}
	/**
	 * Check if any travel occurred 
	 * @return The name of the travel that occurred
	 */
	public String checkTravel(){
		for( Travel t : travels ){
			if( state == t.direction && bearing >= t.minBearing && bearing <= t.maxBearing ){
				Logger.info("Travel occurred "+t.name);
				return t.name;				
			}
		}
		return "";
	}
	public List<Travel> getTravels(){
		return this.travels;
	}
	@Override
	public int compareTo(Waypoint arg0) {
		return Double.compare(lastDist, arg0.lastDist);
	}

	public double distanceTo( double lat, double lon){
		lastDist = GisTools.roughDistanceBetween(lon, lat, this.lon, this.lat, 3)*1000;// From km to m						
		return lastDist;
	}
	public double bearingTo( double lat, double lon ) {
		bearing = GisTools.calcBearing( lon, lat, this.lon, this.lat, 2 );
		return bearing;
	}
	static class Travel{
		String name="";
		double minBearing=0;
		double maxBearing=0;
		STATE direction;		
				
		public Travel( String name, String dir, double minb, double maxb ){
			this.name=name;
			if( dir.equals("in")||dir.equals("enter"))
				direction = STATE.ENTER;
			if( dir.equals("out")||dir.equals("leave"))
				direction = STATE.LEAVE;
			this.minBearing=minb;
			this.maxBearing=maxb;
		}
		public String getDirection(){
			switch( direction ){
				case ENTER: return "in";
				case LEAVE: return "out";
				default: return "in";
			}
		}
		public String toString(){
			return (direction==STATE.ENTER?"Entering ":"Leaving")+" with heading between "+minBearing+"° and "+maxBearing+"° is called "+name;
		}
	}
}
