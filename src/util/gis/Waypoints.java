package util.gis;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.gis.Waypoint.Travel;
import util.tools.Tools;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

public class Waypoints {

    List<Waypoint> wps = new ArrayList<>();
    Document xml=null;
    static final String XML_TAG = "waypoints";
    static final String XML_TRAVEL = "travel";
    static final String XML_CHILD_TAG = "waypoint";

    /* *************************** C O N S T R U C T O R *********************************/
    public Waypoints(){

    }
    public Waypoints(Document xml){
        this.xml=xml;
    }
    public void setXML( Document xml ){
        this.xml=xml;
    }

    /* ****************************** A D D I N G ****************************************/
    /**
     * Adding a waypoint to the list
     * @param wp The waypoint to add
     */
    public void addWaypoint( Waypoint wp ) {    	
    	this.wps.add(wp);
    }
    public void addWaypoint( double lat, double lon, String name) {
    	this.wps.add( new Waypoint( name, lat, lon, 50) );
    }
    public void addWaypoint( double lat, double lon, double range, String name) {
    	this.wps.add( new Waypoint( name, lat, lon, range) );
    }
    public boolean addWaypointIfNew( Waypoint wp ) {    	
        if( !this.isExisting( wp.name ) ){
            addWaypoint( wp );    
            return true;
        }
        return false;
    }
    public boolean reloadWaypointsFromXML(){
        return loadWaypointsFromXML(xml,true);
    }
    public static boolean inXML( Document xml ){
        return XMLtools.getFirstElementByTag(xml,XML_TAG) != null;
    }
    public boolean loadWaypointsFromXML( Document xml, boolean clear ){
        
        if( xml == null){
            Logger.warn("Reading Waypoints failed because invalid XML.");
            return false;
        }
        if( clear ){
            this.wps.clear();
        }
        this.xml = xml;
        Element wpts = XMLtools.getFirstElementByTag(xml,XML_TAG);
        
        if( wpts == null )
            return false;
        
        Logger.info("Reading Waypoints");
        for( Element el : XMLtools.getChildElements(wpts, XML_CHILD_TAG)){
        	if( el != null ){
        		String name = el.getFirstChild().getNodeValue();
        		double lat = GisTools.convertStringToDegrees(el.getAttribute("lat"));
        		double lon = GisTools.convertStringToDegrees(el.getAttribute("lon"));
        		
                double range = Tools.parseDouble( el.getAttribute("range"), -999);
                this.addWaypoint( new Waypoint(name,lat,lon,range) );	  
        	}
        }
        Logger.info("Checking for travel...");
        for( Element el : XMLtools.getChildElements(wpts, XML_TRAVEL)){
        	if( el != null ){
        		double minBearing = Tools.parseDouble( el.getAttribute("min_bearing"), -999);
            	double maxBearing = Tools.parseDouble( el.getAttribute("max_bearing"), -999);
            	String dir = el.getAttribute("dir");
            	String wayp = el.getAttribute(XML_CHILD_TAG);
            	String travel = el.getFirstChild().getNodeValue();
            	
            	for( Waypoint way : wps ){
            		if( way.getName().equals(wayp) ){
            			way.addTravel( travel, dir, minBearing, maxBearing );
            			break;
            		}
            	}
        	}
        }
        return true;
    }
    /**
     * Write the waypoint data to the file it was read fom originally
     * @param includeTemp Whether or not to also include the temp waypoints
     * @return True if succesful
     */
    public boolean storeInXML( boolean includeTemp ){
        if( xml==null){
            Logger.error("XML not defined yet.");
            return false;
        }  
        Element root = XMLtools.getFirstElementByTag( xml, "dcafs" );
        int cnt=0;
        if( root==null){
            Logger.error("XML root (das) not found.");
            return false;
        }
        Element wpts = XMLtools.getFirstElementByTag( xml, XML_TAG );
        if( wpts != null ){
            XMLtools.removeAllChildren(wpts);
        }else{
            wpts = xml.createElement(XML_TAG);
        }
        //Adding the clients
        for( Waypoint wp : wps ){
            if( !wp.isTemp() || includeTemp){
                cnt++;
                Element ele = xml.createElement(XML_CHILD_TAG);
                ele.setAttribute("lat", ""+wp.getLat() );
                ele.setAttribute("lon", ""+wp.getLon() );
                ele.setAttribute("range", ""+wp.getRange() );
                ele.appendChild( xml.createTextNode(wp.getName() ));
                wpts.appendChild(ele);
                
                for( Travel tr : wp.getTravels() ){
                    ele = xml.createElement(XML_TRAVEL);
                    ele.setAttribute("dir", ""+tr.getDirection() );
                    ele.setAttribute("min_bearing", ""+tr.minBearing );
                    ele.setAttribute("max_bearing", "" + tr.maxBearing );
                    ele.setAttribute(XML_CHILD_TAG, "" + wp.getName() );
                  
                    ele.appendChild( xml.createTextNode(tr.name) );
                    wpts.appendChild(ele);
                }
            }
        }
        Logger.info("Stored "+cnt+" waypoints.");
        root.appendChild(wpts);

        String file = xml.getDocumentURI();
		file=file.replace("file:/", "");
		file=file.replace("%20", " ");
        return XMLtools.writeXML(Path.of(file) , xml);//overwrite the file
        
    }
    /* ******************************** G E T ********************************************/

    /**
     * Get the waypoint with the given name
     * @param name The name to look for
     * @return The waypoint found or null if not
     */
    public Waypoint getWaypoint( String name ) {
    	return getWaypoint(name,false);
    }
    public Waypoint getWaypoint( String name, boolean createIfNew ) {
    	for( Waypoint wp : wps ) {
    		if( wp.getName().equals(name)){
                Logger.info("Waypoint found "+name+" in list of "+wps.size()); 
                return wp;

            }
        }
        if( createIfNew ){
            wps.add( new Waypoint(name) );
            return wps.get(wps.size()-1);
        }           
        Logger.error("No such waypoint "+name+" in list of "+wps.size()); 
    	return null;
    }
    public List<Waypoint> items(){
        return wps;
    }
    /* ****************************** R E M O V E ****************************************/

    /**
     * Remove the waypoint with the given name
     * @param name The name of the waypoint to remove
     * @return True if it was removed
     */
    public boolean removeWaypoint( String name ) {
    	for( Waypoint wp : wps ) {
    		if( wp.getName().equals(name)){
    			wps.remove(wp);
    			return true;
    		}
    	}
    	return false;
    }
    /**
     * Remove all the waypoints that are temporary
     */
    public void clearTempWaypoints(){
        wps.removeIf( Waypoint::isTemp );  		
    }
    /* ******************************** I N F O ******************************************/
    /**
     * Get the amount of waypoints
     * @return The size of the list containing the waypoints
     */
    public int size() {
    	return wps.size();
    }
    /**
     * Check if a waypoint with the given name exists
     * @param name The code to look for
     * @return True if the code was found
     */
    public boolean isExisting( String name ){
        for( Waypoint wp : wps ){
            if( wp != null && wp.getName().equals(name)){
                return true;
            }
        }
        return false;
    }
    public boolean isNear( String code ){
        for( Waypoint wp : wps ){
            if( wp != null && wp.getName().equals(code)){
                return wp.isNear();
            }
        }
        return false;
    }
    /**
     * Get an overview off all the available waypoints
     * @param coords Whether or not to add coordinates
     * @param sog Speed is used to calculate the time till the waypoint
     * @return A descriptive overview off all the current waypoints
     */
	public String getListing( boolean coords, double sog ){
        StringBuilder b = new StringBuilder();
        if( wps.isEmpty() )
            return "No waypoints collected yet.";
    	Collections.sort(wps);
    	for( Waypoint w : wps)
    		b.append( w.toString(coords, true, sog) ).append("\r\n");
    	return b.toString();
    }
    public String getSimpleListing( String newline ){
        StringBuilder b = new StringBuilder();
        if( wps.isEmpty() )
            return "No waypoints collected yet.";
        for( Waypoint wp : wps ){
            b.append(wp.simpleList(newline));
        }
        return b.toString();
    }
    /**
     * Request the closest waypoints to the given coordinates
     * @param lat The latitude
     * @param lon The longitude
     * @return Name of the closest waypoint
     */
    public String getClosestWaypoint( double lat, double lon){
		double dist=10000000;
		String wayp="None";
		for( Waypoint wp : wps ) {
			double d = wp.distanceTo( lat, lon );
			if( d < dist ) {
				dist = d;
				wayp = wp.getName();
			}
		}
		return wayp;
    }
    /* ****************************************************************************************************/
    /* ****************************************************************************************************/
    /**
     * Reply to requests made
     * @param req The request
     * @param html Determines if EOL should be <br> or crlf
     * @param sog Current speed over ground (only needed for states request)
     * @return Descriptive reply to the request
     */
    public String replyToSingleRequest( String req, boolean html, double sog ){
        
        String[] cmd = req.split(",");

		switch( cmd[0] ){
            case "?":
                    StringJoiner b = new StringJoiner(html?"<br>":"\r\n");
                    b.add( "wpts:print or wpts:list or wpts:listing -> Get a listing of all waypoints with travel.")
                    .add( "wpts:states -> Get a listing  of the state of each waypoint.")
                    .add( "wpts:reload -> Reloads the waypoints from the settings file.")
                    .add( "wpts:remove,<name> -> Remove a waypoint with a specific name")
                    .add( "wpts:new,<id,<lat>,<lon>,<range> -> Create a new waypoint with the name and coords lat and lon in decimal degrees")
                    .add( "wpts:update,id,lat,lon -> Update the waypoint coords lat and lon in decimal degrees")
                    .add( "wpts:travel,<waypoint>,<minbearing>,<maxbearing>,<name> -> Add travel to a waypoint.");
                    return b.toString();
			case "print": case "list": case "listing": return getSimpleListing(html?"<br>":"\r\n");
            case "states": return getListing(false, sog );
            case "store": 
                if( this.storeInXML(false) ){
                    return "Storing waypoints succesful";
                }else{
                    return "Storing waypoints failed";
                }
			case "reload": 
				if( reloadWaypointsFromXML() ){
					return "Reloaded stored waypoints";
				}else{
					return "Failed to reload waypoints";
                }
            case "new": //wpts:new,51.1253,2.2354,wrak
                if( cmd.length < 4)
                    return "Not enough parameters given";
                if( cmd.length > 5)
                    return "To many parameters given (fe. 51.1 not 51,1)";

                double lat = GisTools.convertStringToDegrees(cmd[1]);
                double lon = GisTools.convertStringToDegrees(cmd[2]);
                String name = cmd[3];
                double range = 50;
                if( cmd.length == 5){
                    name=cmd[4];
                    range = Tools.parseDouble(cmd[3], 50);    
                }
                
                addWaypoint( lat, lon, range, name);
                return "Added waypoint called "+cmd[3]+ " lat:"+lat+"°\tlon:"+lon+"°\tRange:"+range+"m";
            case "update":
                if( cmd.length < 4)
                    return "Not enough parameters given wpts:update,id,lat,lon";
                var wpOpt = getWaypoint(cmd[1]);
                if( wpOpt!=null) {
                    wpOpt.updatePosition(Tools.parseDouble(cmd[2], -999), Tools.parseDouble(cmd[3], -999));
                    return "Updated "+cmd[1];
                }
                return "No such waypoint";
            case "remove":
                if( removeWaypoint( cmd[1]) ) {
                    return "Waypoint removed";
                }else {
                    return "No waypoint found with the name.";
                }
            case XML_TRAVEL:
                Waypoint way = this.getWaypoint(cmd[1]);
                if( way == null ){
                    return "No such waypoint: "+cmd[1];
                }
                if( cmd.length != 6)
                    return "Incorrect amount of parameters";
                double minBearing = Tools.parseDouble(cmd[2], 0);
                double maxBearing = Tools.parseDouble(cmd[3], 360);
                
                way.addTravel(cmd[5], cmd[4], minBearing, maxBearing);
                return "Added travel "+cmd[5]+" to "+ cmd[1];
            default:
                return "Unknown waypoints command";
        }
    }
}