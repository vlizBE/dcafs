package io.hardware.i2c;

import com.diozero.api.DeviceAlreadyOpenedException;
import com.diozero.api.DeviceBusyException;
import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class I2CWorker implements Runnable, Commandable {

    private final BlockingQueue<Datagram> dQueue;
    private final HashMap<String, ExtI2CDevice> devices = new HashMap<>();
    private final BlockingQueue<Pair<String, String>> work = new LinkedBlockingQueue<>();

    private boolean log = true;
    private boolean debug = false;
    private boolean goOn=true;

    private final LinkedHashMap<String,I2CCommand> commands = new LinkedHashMap<>();
    Path scriptsPath;

    public I2CWorker(Document xml, BlockingQueue<Datagram> dQueue, String workPath) {
        this.dQueue = dQueue;
        scriptsPath = Path.of(workPath).resolve("i2cscripts");
        readSettingsFromXML(xml);
    }
    public boolean setDebug( boolean debug ){
        this.debug=debug;
        return debug;
    }
    public String getStatus(String eol){
        StringJoiner join = new StringJoiner(eol);
        devices.forEach( (key,val) -> join.add( val.getStatus(key)));
        return join.toString();
    }
    /* ***************************************************************************************************** */
    /**
     * Adds a device to the hashmap
     * 
     * @param id         The name of the device, will be used to reference it from the TaskManager etc
     * @param controller The controller the device is connected to
     * @param address    The address the device had
     * @return The created device
     */
    public ExtI2CDevice addDevice(String id, String script, String label, int controller, int address) {

        ExtI2CDevice device = new ExtI2CDevice(controller, address, script, label);
        devices.put(id, device);
        try{
            device.probeIt();
        }catch( RuntimeIOException e){
            Logger.error("Probing the new device failed: "+device.getAddr());
        }
        return device;
    }

    /**
     * Get the device based on its id
     * 
     * @param id The label for this device
     * @return The requested device
     */
    public ExtI2CDevice getI2CDevice(String id) {
        return devices.get(id);
    }

    /**
     * Get a readable list of all the registered devices
     * @param full Add the complete listing of the commands and not just id/info
     * @return Comma separated list of registered devices
     */
    public String getDeviceList(boolean full) {
        StringJoiner join = new StringJoiner("\r\n");
        devices.forEach((key, device) -> join.add(key+" -> "+device.toString()));
        join.add("\r\n-Stored scripts-");
        String last="";

        for( var entry : commands.entrySet() ){
            String[] split = entry.getKey().split(":");
            var cmd = entry.getValue();
            if( !last.equalsIgnoreCase(split[0])){
                if( !last.isEmpty())
                    join.add("");
                join.add(TelnetCodes.TEXT_GREEN+split[0]+TelnetCodes.TEXT_YELLOW);
                last = split[0];
            }
            join.add("\t"+split[1]+" -> "+cmd.getInfo()+" ("+cmd.bits+"bits)");
            if( full)
                join.add(cmd.toString("\t   "));
        }
        return join.toString();
    }
    public boolean registerWritable(String id, Writable wr){
        ExtI2CDevice device =  devices.get(id);
        if( device == null )
            return false;
        device.addTarget(wr);
        return true;
    }

    public String getListeners(){
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue("None yet");
        devices.forEach( (id,device) -> join.add( id+" -> "+device.getWritableIDs()));
        return join.toString();
    }
    /* ***************************************************************************************************** */
    /* ***************************************************************************************************** */
    /**
     * Add work to the worker
     * 
     * @param id  The device that needs to execute a command
     * @param command The command the device needs to execute
     * @return True if the command was valid
     */
    public boolean addWork(String id, String command) {
        ExtI2CDevice i2c = devices.get(id.toLowerCase());
        if (i2c == null) {
            Logger.error("Invalid job received, unknown device '" + id + "'");
            return false;
        }
        if (!commands.containsKey(i2c.getScript()+":"+command)) {
            Logger.error("Invalid command received '" + i2c.getScript()+":"+command + "'.");
            return false;
        }
        work.add(Pair.of(id, command));
        return true;
    }
    public void stopWorker(){
        goOn=false;
    }
    /* ************************  Commands  ***********************************************/
	/**
	 * Add a command to this device
	 * 
	 * @param id The id for this command 
	 * @param command The command to add
	 * @return True is no command with this id was already present
	 */
	public boolean addCommand( String id , I2CCommand command ){
		boolean wasNew = commands.get(id)!=null;
		commands.put(id, command);
		return wasNew;
	}
	/**
	 * Remove a command from the list
	 * 
	 * @param id The reference to the command
	 * @return True if a command was removed
	 */
	public boolean removeCommand( String id ){
		return commands.remove(id)!=null;
	}
    /* ************************* READ XML SETTINGS *******************************************/
    /**
     * Reads the settings for the worker from the given xml file, this mainly
     * consists of devices with their commands.
     * 
     * @param xml The document that holds the settings
     * @return True if no issues were encountered
     */
    public String readSettingsFromXML(Document xml) {
        Element i2c = XMLtools.getFirstElementByTag(xml, "i2c");

        if (i2c != null) {                       
            Logger.info("Found settings for a I2C bus");
            devices.values().forEach(I2CDevice::close);
            devices.clear();
            for( Element i2c_bus : XMLtools.getChildElements( i2c, "bus") ){
                int bus = XMLtools.getIntAttribute(i2c_bus, "controller", -1);
                Logger.info("Reading devices on the I2C bus of controller "+bus);
                if( bus ==-1 ){
                    Logger.error("Invalid controller number given.");
                    continue;
                }
                for( Element device : XMLtools.getChildElements( i2c_bus, "device")){

                    String id = XMLtools.getStringAttribute( device, "id", "").toLowerCase();
                    String script = XMLtools.getStringAttribute( device, "script", "").toLowerCase();
                    String label = XMLtools.getStringAttribute( device, "label", "void").toLowerCase();
                    
                    int address = Tools.parseInt( XMLtools.getStringAttribute( device , "address", "0x00" ),16);

                    addDevice( id, script, label, bus, address );
                    Logger.info("Adding "+id+"("+address+") to the devicelist of controller "+bus);					
                }           
            }
        }else{
            Logger.info("No settings found for I2C, no use reading the commandsets.");
            return "No settings found for I2C.";         
        }                            
        return reloadCommands();
    }
    public static boolean addDeviceToXML(XMLfab fab, String id, int bus, String address,String script){
        return fab.digRoot("i2c").selectOrAddChildAsParent("bus","controller",""+bus)
                .selectOrAddChildAsParent("device","id",id).attr("address",address).attr("script",script)
                .build() != null;
    }
    public String reloadCommands( ){
        List<Path> xmls;
        try (Stream<Path> files = Files.list(scriptsPath)){
            xmls = files.filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList());
        }catch (IOException e) {            
            Logger.error("Something went wrong trying to read the commandset files");
            return "Failed to read files in i2cscripts folder";
        }
        Logger.info("Reading I2C scripts from: "+scriptsPath);

        commands.clear();
        for( Path p : xmls ){

            Document doc = XMLtools.readXML(p);
            if( doc == null ){
                return "Syntax error in "+p.getFileName().toString();
            }
            for( Element set : XMLtools.getAllElementsByTag(  doc , "commandset") ){
            
                String script = XMLtools.getStringAttribute( set,"script",""); 

                for( Element command : XMLtools.getChildElements( set, "command")){
                    I2CCommand cmd = new I2CCommand();

                    String cmdID = XMLtools.getStringAttribute( command, "id", "");                    

                    cmd.setInfo( XMLtools.getStringAttribute( command, "info", "") );
                    int bits = XMLtools.getIntAttribute( command, "bits", 8);
                    boolean msb = XMLtools.getBooleanAttribute( command, "msbfirst",true);
                    cmd.setReadBits( bits );

                    for( Element ele : XMLtools.getChildElements( command, "*") ){
                        
                        String reg = XMLtools.getStringAttribute( ele , "reg", "" );

                        boolean ok=false;
                        switch( ele.getNodeName() ){
                            case "read":
                                boolean signed = XMLtools.getBooleanAttribute(ele, "signed",false);
                                ok = cmd.addRead( Tools.fromHexStringToBytes( reg ) // register address
                                                , XMLtools.getIntAttribute( ele , "return", 0 ) // how many bytes
                                                , XMLtools.getIntAttribute(ele,"bits",bits) //how many bits to combine
                                                , XMLtools.getBooleanAttribute( ele, "msbfirst",msb)
                                                , signed) != null;
                            break;
                            case "write":
                                if( ele.getTextContent().isEmpty() && !reg.isEmpty()){
                                    ok = cmd.addWrite( Tools.fromHexStringToBytes( reg ) ) != null;
                                }else if( reg.isEmpty() && !ele.getTextContent().isEmpty() ){
                                    ok = cmd.addWrite( Tools.fromHexStringToBytes( ele.getTextContent() )) != null;
                                }else{
                                    ok = cmd.addWrite( ArrayUtils.addAll( Tools.fromHexStringToBytes( reg ),
                                                            Tools.fromHexStringToBytes( ele.getTextContent() )) ) != null;
                                }                                                                
                            break;
                            case "alter":
                                byte[] d = ArrayUtils.addAll( Tools.fromHexStringToBytes( reg ),
                                        Tools.fromHexStringToBytes( ele.getTextContent() ));                                    
                                ok = cmd.addAlter( d, XMLtools.getStringAttribute( ele , "operand", "or" ) ) != null;
                            break;
                            case "wait_ack":
                                if( !ele.getTextContent().isEmpty() ){
                                    int cnt = Tools.parseInt(ele.getTextContent(), -1);
                                    if( cnt != -1){
                                        ok = cmd.addWaitAck(cnt) != null;
                                    }else{
                                        ok=false;
                                    }
                                }
                            break;
                            case "math":
                                if( !ele.getTextContent().isEmpty() ){
                                    cmd.addMath(ele.getTextContent());
                                    ok=true;
                                }
                                break;
                            default:
                                Logger.error("Unknown command: "+ele.getNodeName());
                                break;
                        }
                        if(!ok ){
                            Logger.error("Invalid data received for command");
                            continue;
                        }
                        if(debug)
                            Logger.info("Added "+cmdID +" to "+script+" which does: "+ cmd);
                    }
                    
                    commands.put( script+":"+cmdID,cmd);                            
                    Logger.info("Total bytes received during this command: "+cmd.getTotalReturn());
                }     
            }
        }
        return "All files ("+xmls.size()+") read ok.";
    }
    public void disableLogging(){
        log=false;
    }
    /* ***************************************************************************************************** */
    @Override
    public void run() {
        while (goOn) {
            Pair<String, String> job; // devicename, command id
            try {
                job = work.take();
                String deviceID = job.getLeft();
                String cmdID = job.getRight();

                ExtI2CDevice device = devices.get(deviceID);

                // Execute the command
                I2CCommand com = commands.get(device.getScript()+":"+cmdID);
                List<Double> altRes=null;
                try {
                    altRes = doCommand(device, com);
                    device.updateTimestamp();
                }catch( RuntimeIOException e ){
                    Logger.error("Failed to run command for "+device.getAddr()+":"+e.getMessage());
                    continue;
                }
                // Do something with the result...
                String label = device.getLabel()+":"+cmdID;

                StringJoiner output = new StringJoiner(";",deviceID+";"+cmdID+";","");
                altRes.forEach( x -> output.add(""+x));

                if( !device.getLabel().equalsIgnoreCase("void") ){
                    dQueue.add( Datagram.build(output.toString()).label(label).origin(job.getLeft()).payload(altRes) );
                }
                 try {
                     device.getTargets().forEach(wr -> wr.writeLine(output.toString()));
                     device.getTargets().removeIf(wr -> !wr.isConnectionValid());
                 }catch(Exception e){
                     Logger.error(e);
                 }

                 if( log )
                     Logger.tag("RAW").warn( "1\t" + job.getLeft()+":"+label + "\t" + output );

            } catch (InterruptedException e) {
                Logger.error(e);
                // Restore interrupted state...
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Converts an array of bytes to a list of ints according to the bits. Note that bytes aren't split in their nibbles
     * fe. 10bits means take the first two bytes and shift right, then take the next two
     * @param result the array of bytes
     * @param bits the amount of bits to put in an int, bits will be shifted!
     * @return the resulting list (might be empty)
     */
    private synchronized List<Integer> convertBytesToInt(byte[] result, int bits){
        return convertBytesToInt(result,bits,true,false);
    }
    public static List<Integer> convertBytesToInt(byte[] bytes, int bits, boolean msbFirst, boolean signed){
     
        int[] intResults = new int[bytes.length];
        ArrayList<Integer> ints = new ArrayList<>();

        for( int a=0;a<bytes.length;a++){
            intResults[a]=bytes[a]<0?bytes[a]+256:bytes[a];
        }
        int temp;
        switch( bits ){
            case 8: // Direct byte -> unsigned int conversion
                for( int a : intResults){
                    ints.add( signed? MathUtils.toSigned8bit(a):a);
                }
            break;
                //TODO Shift in the other direction and LSB/MSB first
            case 10: // take the full first byte and only the 2 MSB of the second
                for( int a=0;a<bytes.length;a+=2){
                    temp=intResults[a]*4+intResults[a+1]/64;
                    ints.add( signed? MathUtils.toSigned12bit(temp):temp );
                }
            break;
            //TODO Shift in the other direction and LSB/MSB first
            case 12: // take the full first byte and only the MSB nibble of the second
                for( int a=0;a<bytes.length;a+=2){
                    temp = intResults[a]*16+intResults[a+1]/16;
                    ints.add( signed? MathUtils.toSigned12bit(temp):temp );
                }
            break;
            case 16: // Concatenate two bytes 
                for( int a=0;a<bytes.length;a+=2){
                    if( msbFirst ){
                        temp = intResults[a]*256+intResults[a+1];
                    }else{
                        temp = intResults[a]+intResults[a+1]*256;
                    }
                    ints.add( signed? MathUtils.toSigned16bit(temp):temp );
                }
            break;
            //TODO Shift in the other direction?
            case 20: // Concatenate two bytes and take the msb nibble of the third
                for( int a=0;a<bytes.length;a+=3){
                    if( msbFirst ){
                        temp = (intResults[a]*256+intResults[a+1])*16+intResults[a+2]/16;
                    }else{
                        temp = (intResults[a+2]*256+intResults[a+1])*16+intResults[a]/16;
                    }
                    ints.add( signed? MathUtils.toSigned20bit(temp):temp );
                }
            break;
            case 24: // Concatenate three bytes
                for( int a=0;a<bytes.length;a+=3){
                    if( msbFirst ) {
                        temp = (intResults[a] * 256 + intResults[a + 1]) * 256 + intResults[a + 2];
                    }else{
                        temp = (intResults[a+2] * 256 + intResults[a + 1]) * 256 + intResults[a];
                    }
                    ints.add( signed? MathUtils.toSigned24bit(temp):temp );
                }
            break;
            default:
                Logger.error("Tried to use an undefined amount of bits "+bits);
                break;
        }
        return ints;
    }
    /**
     * Executes the given command
     * @param device The device to which to send the command
     * @param com The command to send
     * @return The bytes received as reply to the command
     */
    private synchronized List<Double> doCommand(ExtI2CDevice device, I2CCommand com  ) throws RuntimeIOException{

        var result = new ArrayList<Double>();
        device.probeIt();

        if( com != null ){
                for( I2CCommand.CommandStep cmd : com.getAll() ){ // Run te steps, one by one (in order)
                    byte[] toWrite = cmd.write;

                    switch( cmd.type ){ // Type of subcommand
                        case READ:
                            byte[] b;
                            ByteBuffer bb;
                            try {
                                if( toWrite.length==0){
                                    // read readCount bytes and put in readBuffer
                                    b = device.readBytes( cmd.readCount );
                                }else if(toWrite.length==1){
                                    // after writing the first byte, read readCount bytes and put in readBuffer
                                    b = new byte[cmd.readCount];
                                    device.readI2CBlockData(toWrite[0], b);
                                    device.updateTimestamp();
                                }else{
                                    device.writeBytes( toWrite ); // write all the bytes in the array
                                    b = device.readBytes( cmd.readCount );
                                }
                            }catch( RuntimeIOException e ){
                                Logger.error("Error trying to read from "+device.getAddr()+": "+e.getMessage());
                                continue;
                            }
                            if( debug ){
                                Logger.info( "Read: "+Tools.fromBytesToHexString(b));
                            }
                            convertBytesToInt(b,cmd.bits,cmd.isMsbFirst(),cmd.isSigned()).forEach(
                                    x -> result.add(Tools.roundDouble((double)x,0)) );
                            break;
                        case WRITE:
                            device.writeBytes( toWrite ) ;
                            break;
                        case ALTER_OR: // Read the register, alter it and write it again
                            byte[] sub = {toWrite[0]};
                            device.writeBytes(sub);
                            sub[0]=device.readByte();
                            toWrite[1] |= sub[0];
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_AND:
                            device.writeByte(toWrite[0]);
                            toWrite[1] &= device.readByte();
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_NOT:
                            device.writeByte(toWrite[0]);
                            toWrite[1] ^= 0xFF;
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_XOR:
                            device.writeByte(toWrite[0]);
                            toWrite[1] ^= device.readByte();
                            device.writeBytes(toWrite);
                            break;
                        case WAIT_ACK: // Wait for an ack to be received up to x attempts
                            boolean ok=false;
                            double tries;
                            int max = Tools.toUnsigned(toWrite[0]);
                            Logger.info("Max attempts for ACK: "+max);
                            for( tries=1;tries<max&&!ok;tries+=1)
                                ok=device.probe(I2CDevice.ProbeMode.AUTO);

                            if(ok){
                                Logger.info("Wait ack ok after "+tries+" attempts of "+max);
                                result.add(tries);
                            }else{
                                result.add(0.0);
                                Logger.info("Wait ack failed after "+tries+" attempts of "+max);
                                return result;
                            }
                            break;
                        case MATH:
                            var ar = result.toArray( new Double[0] );
                            result.set(cmd.index,cmd.fab.solveFor(ar));
                            break;
                        default:
                            Logger.error("Somehow managed to use an none existing type...");
                            break;
                    }
                }
        }
        if( result.size() != com.getTotalIntReturn())
            Logger.error("Didn't read the expected amount of bytes: "+result.size()+" instead of "+com.getTotalIntReturn());
        return result;
    }
    /* ***************************************************************************************************** */
    /**
     * Search the bus of the specified controller for responsive devices
     * 
     * @param controller The index of the controller to look at
     * @return A list of found used addresses, prepended with Busy of currently addressed by a driver
     */
    public static String detectI2Cdevices( int controller ){
		StringJoiner b = new StringJoiner("\r\n");

		for (int device_address = 0; device_address < 128; device_address++) {
			if (device_address < 0x03 || device_address > 0x77) {
				// Out of bounds
			} else {
				try (I2CDevice device = new I2CDevice(controller, device_address)) {
					if (device.probe( I2CDevice.ProbeMode.AUTO )) {
						b.add( "0x"+String.format("%02x ", device_address) );
					}
				} catch (DeviceBusyException e) {
					b.add("Busy - 0x"+String.format("%02x ", device_address));
				} catch( DeviceAlreadyOpenedException e){
				    b.add("Controller already addressed by DAS, fix todo");
                }
			}
		}
		String result = b.toString();
		return result.isBlank()?"No devices found.\r\n":result;
	}
    /* ******************************* C O M M A N D A B L E ******************************************************** */
    @Override
    public boolean removeWritable( Writable wr ){
        int cnt=0;
        for( ExtI2CDevice device : devices.values() ){
            cnt += device.removeTarget(wr)?1:0;
        }
        return cnt!=0;
    }
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
            String[] cmd = request[1].split(",");

            switch( cmd[0] ){
                case "?":
                    StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
                    join.add("i2c:detect,<controller> -> Detect the devices connected to a certain controller")
                            .add("i2c:list -> List all registered devices and their commands")
                            .add("i2c:cmds -> List all registered devices and their commands including comms")
                            .add("i2c:reload -> Reload the command file(s)")
                            .add("i2c:debug,on/off -> Enable or disable extra debug feedback in logs")
                            .add("i2c:forward,device -> Show the data received from the given device")
                            .add("i2c:adddevice,id,bus,address,script -> Add a device on bus at hex addres that uses script")
                            .add("i2c:addblank,scriptname -> Adds a blank i2c script to the default folder")
                            .add("i2c:<device>,<command> -> Send the given command to the given device");
                    return join.toString();
                case "list": return getDeviceList(false);
                case "cmds": return getDeviceList(true);
                case "reload": return reloadCommands();
                case "forward": return registerWritable(cmd[1],wr)?"Added forward":"No such device";
                case "listeners": return getListeners();
                case "debug":
                    if( cmd.length == 2){
                        if( setDebug(cmd[1].equalsIgnoreCase("on")) )
                            return "Debug"+cmd[1];
                        return "Failed to set debug, maybe no i2cworker yet?";
                    }else{
                        return "Incorrect number of variables: i2c:debug,on/off";
                    }
                case "addblank":
                    if( cmd.length != 2)
                        return "Incorrect number of variables: i2c:addblank,scriptname";
                    if( !Files.isDirectory(scriptsPath)) {
                        try {
                            Files.createDirectories(scriptsPath);
                        }catch( IOException e){
                            Logger.error(e);
                        }
                    }
                    XMLfab.withRoot(scriptsPath.resolve(cmd[1]+".xml"),"commandset").attr("script",cmd[1])
                            .addParentToRoot("command","An empty command to start with")
                            .attr("id","cmdname").attr("info","what this does")
                            .build();
                    return "Blank added";
                case "adddevice":
                    if( cmd.length != 5)
                        return "Incorrect number of variables: i2c:adddevice,id,bus,address,script";
                    if( !Files.isDirectory(scriptsPath)) {
                        try {
                            Files.createDirectories(scriptsPath);
                        }catch( IOException e){
                            Logger.error(e);
                        }
                    }
                    if( I2CWorker.addDeviceToXML(XMLfab.withRoot(scriptsPath.getParent().resolve("settings.xml"),"dcafs","settings"),
                            cmd[1], //id
                            Integer.parseInt(cmd[2]), //bus
                            cmd[3], //address in hex
                            cmd[4] //script
                    )) {
                        // Check if the script already exists, if not build it
                        var p = scriptsPath.resolve(cmd[4]+".xml");
                        if( !Files.exists(p)){
                            XMLfab.withRoot(p,"commandset").attr("script",cmd[4])
                                    .addParentToRoot("command","An empty command to start with")
                                    .attr("id","cmdname").attr("info","what this does")
                                    .build();
                            readSettingsFromXML(XMLtools.readXML(scriptsPath.getParent().resolve("settings.xml")));
                            return "Device added, created blank script at "+p;
                        }else{
                            return "Device added, using existing script";
                        }

                    }
                    return "Failed to add device to XML";
                case "detect":
                    if( cmd.length == 2){
                        return I2CWorker.detectI2Cdevices( Integer.parseInt(cmd[1]) );
                    }else{
                        return "Incorrect number of variables: i2c:detect,<bus>";
                    }
                default:
                    if( cmd.length!=2) {
                        String oks="";
                        for( var dev : devices.entrySet()){
                            if( dev.getKey().matches(cmd[0])){
                                dev.getValue().addTarget(wr);
                                if( !oks.isEmpty())
                                    oks+=", ";
                                oks += dev.getKey();
                            }
                        }
                        if( !oks.isEmpty()) {
                            return "Request for i2c:"+cmd[0]+" accepted for "+oks;
                        }else{
                            Logger.error("No matches for i2c:"+cmd[0]+" requested by "+wr.getID());
                            return "No matches for i2c:"+cmd[0];
                        }
                    }
                    if( wr!=null && wr.getID().equalsIgnoreCase("telnet") ){
                        registerWritable(cmd[0],wr);
                    }
                    if( addWork(cmd[0], cmd[1]) ){
                        return "Command added to the queue.";
                    }else{
                        return "Failed to add command to the queue, probably wrong device or command";
                    }
            }
    }
}