package util.gis;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class Waypoint implements Comparable<Waypoint>{
	
	public enum STATE{INSIDE,OUTSIDE,ENTER,LEAVE,UNKNOWN}

	static DateTimeFormatter sqlFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	double lat;
	double lon;
	double range;
	double lastDist=-1;
	String name;
	int id;
	STATE state=STATE.UNKNOWN;
	boolean temp=false;
	
	double bearing=0;

	/* Stuff to determine enter and leave time if entered and left multiple times in succession */
	boolean active = false;
	boolean movementReady=false;
	OffsetDateTime enterTime;
	OffsetDateTime leaveTime;
	double leaveDistance=0;
		
	/* Specific movements */
	ArrayList<Travel> travels = new ArrayList<>();

	public Waypoint( String name ){
		this.name=name;
	}

	public static Waypoint build( String name){
		return new Waypoint(name);
	}
	public Waypoint lat( double lat){
		this.lat=lat;
		return this;
	}
	public Waypoint lon( double lon){
		this.lon=lon;
		return this;
	}
	public Waypoint range( double range){
		this.range=range;
		return this;
	}
	public boolean hasTravelCmd(){
		for( var travel: travels )
			if( !travel.cmds.isEmpty())
				return true;
		return false;
	}
	public STATE currentState( OffsetDateTime when, double lat, double lon ){
		lastDist = GisTools.roughDistanceBetween(lon, lat, this.lon, this.lat, 3)*1000;// From km to m				
		bearing = GisTools.calcBearing( lon, lat, this.lon, this.lat, 2 );

		switch( state ){
			case INSIDE:
				if( lastDist > range ){ // Was inside but went beyond the range
					state = STATE.LEAVE;
					leaveTime=when;
					leaveDistance=lastDist;
				}
				break;
			case OUTSIDE:
				if( lastDist < range ){ // Was outside but came within the range
					state=STATE.ENTER;
					if( !active ){
						enterTime=when;
					}
					active = true;
				}
				break;
			case ENTER:
			case LEAVE:
			case UNKNOWN:
				state = lastDist < range?STATE.INSIDE:STATE.OUTSIDE;
				break;
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
	public Optional<Travel> checkIt(OffsetDateTime when, double lat, double lon ){

		switch( currentState( when , lat, lon ) ){
			case ENTER:
			case LEAVE:
				if( getLastDistance() < 500 && getLastDistance() > 1) // Ignore abnormal movements
					return checkTravel();
				break;
			case OUTSIDE:
				String l = getLastMovement();
				if( !l.isBlank()){
					FileTools.appendToTxtFile(Path.of("logs","waypointsMoves.txt"), l+"\r\n");
					Logger.info( "Travel: "+l);
				}
				break;
			case INSIDE:
			default:
				break;    		
		}
		return Optional.empty();
	}
	public String getLastMovement(){		
		if( movementReady ){
			movementReady=false;
			return "Arrived at "+name+" on "+enterTime.format(sqlFormat) + " and left on " + leaveTime.format(sqlFormat);
		}		
		return "";
	}
	public boolean isTemp(){
		return temp;
	}
	public Waypoint makeTemp(){
		temp=true;
		return this;
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
	public String getInfo(String newline){
		String prefix;
		if( !newline.startsWith("<")) {
			prefix = TelnetCodes.TEXT_GREEN + name + TelnetCodes.TEXT_YELLOW;
		}else{
			prefix = name;
		}
		prefix += " ["+GisTools.fromDegrToDegrMin(lat,4,"°")+";"+GisTools.fromDegrToDegrMin(lon,4,"°")+"]\tRange:"+range+"m";

		StringJoiner join = new StringJoiner(newline,
					prefix,"");
		join.add("");
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

	/* ******************************************************************************** **/
	/**
	 * Adds a travel to the waypoint
	 * 
	 * @param name The name of the travel
	 * @param dir The direction either in(or enter) or out( or leave)
	 * @param bearing Range of bearing in readable english, fe. from 100 to 150
	 * @return An optional Travel, which is empty if the bearing parsing failed
	 */
	public Optional<Travel> addTravel( String name, String dir, String bearing ){
		var travel = new Travel(name, dir, bearing);
		if( travel.isValid()) {
			travels.add(travel);
			Logger.info("Added travel named "+name+" to waypoint "+this.name);
			return Optional.ofNullable(travel);
		}else{
			Logger.error( id+" (wp)-> Failed to add travel, parsing bearing failed: "+bearing);
			return Optional.empty();
		}
	}
	/**
	 * Check if any travel occurred, if so return the travel in question
	 * @return The travel that occurred
	 */
	public Optional<Travel> checkTravel(){
		for( Travel t : travels ){
			if( t.check(state,bearing) ){
				Logger.info("Travel occurred "+t.name);
				return Optional.of(t);
			}
		}
		return Optional.empty();
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
	public class Travel{
		String name="";
		double maxBearing=360.0,minBearing=0.0;
		STATE direction;		

		ArrayList<String> cmds;
		boolean valid = true;

		public Travel( String name, String dir, String bearing ){
			this.name=name;
			direction = switch( dir ){
				case "in","enter" -> STATE.ENTER;
				case "out","leave" -> STATE.LEAVE;
				default -> STATE.UNKNOWN;
			};
			if( !bearing.contains("->")){
				Logger.error("Incorrect bearing for "+name+" must be of format 0->360 or 0 -> 360");
				valid=false;
			}else{
				var br = bearing.replace(" ","").split("->");
				if( br.length!=2){
					Logger.error("Incorrect bearing for "+name+" must be of format 0->360 or 0 -> 360");
					valid=false;
				}else {
					minBearing = NumberUtils.createDouble(br[0]);
					maxBearing = NumberUtils.createDouble(br[1]);
				}
			}
		}
		public boolean isValid(){
			return valid;
		}
		public boolean check(STATE state, double curBearing){
			if( !valid )
				return false;
			return state == direction && Double.compare(curBearing,minBearing) >=0 && Double.compare(curBearing,maxBearing)<=0;
		}
		public ArrayList<String> getCmds(){
			return cmds;
		}
		public String getDirection(){
			switch( direction ){
				case LEAVE: return "out";
				case ENTER:
				default: return "in";
			}
		}
		public String toString(){
			String info = name +" = "+(direction==STATE.ENTER?" coming closer than "+range+"m":" going further away than "+range+"m");
			return info+" with a bearing from"+minBearing+ " to "+maxBearing+"°";
		}
		public String getBearingString(){
			return (minBearing+" -> "+maxBearing).replace(".0","");
		}
		public Travel addCmd( String cmd ){
			if( cmd==null) {
				Logger.error(name+" -> Invalid cmd given");
				return this;
			}
			if( cmds==null)
				cmds=new ArrayList<>();
			cmds.add(cmd);
			return this;
		}
	}
}
