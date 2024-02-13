package io.hardware.i2c;

import com.diozero.api.DeviceAlreadyOpenedException;
import com.diozero.api.DeviceBusyException;
import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import javax.swing.text.Utilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class I2CWorker implements Commandable {

    private final BlockingQueue<Datagram> dQueue;

    private final HashMap<String, ExtI2CDevice> devices = new HashMap<>();
    private final LinkedHashMap<String,I2CCommand> commands = new LinkedHashMap<>();

    private boolean debug = false;

    private final Path scriptsPath; // Path to the scripts
    private final Path settingsPath; // Path to the settingsfile
    private ExecutorService executor; // Executor to run the commands

    public I2CWorker(Path settings, BlockingQueue<Datagram> dQueue) {
        this.dQueue = dQueue;
        this.settingsPath=settings;
        scriptsPath = settings.getParent().resolve("i2cscripts");
        readFromXML();
    }

    /**
     * Enable or disable extra debug info
     * @param debug True to enable
     * @return The new debug state
     */
    public boolean setDebug( boolean debug ){
        this.debug=debug;
        return debug;
    }

    /**
     * Get info on  the current status of the attached devices
     * @param eol The eol to use
     * @return The concatenated status's
     */
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
    public Optional<ExtI2CDevice> addDevice(String id, String script, String label, int controller, int address) {

        if( address==-1) {
            Logger.warn(id+" -> Invalid address given");
            return Optional.empty();
        }

        try (I2CDevice device = new I2CDevice(controller, address)) {
            if (!device.probe( I2CDevice.ProbeMode.AUTO )) {
                Logger.error("Probing the new device failed: "+address);
                return Optional.empty();
            }
        } catch ( RuntimeIOException e ){
            Logger.error("Probing the new device failed: "+address);
            return Optional.empty();
        }
        try{
            devices.put(id, new ExtI2CDevice(id,controller, address, script, label));
            return Optional.of(devices.get(id));
        }catch( RuntimeIOException e){
            Logger.error("Probing the new device failed: "+address);
            return Optional.empty();
        }
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
    public int getDeviceCount(){
        return devices.size();
    }
    /**
     * Add the writable to the list of targets of the device
     * @param id The id of the device
     * @param wr The writable of the target
     * @return True if the addition was ok
     */
    public boolean addTarget(String id, Writable wr){
        ExtI2CDevice device =  devices.get(id);
        if( device == null )
            return false;
        device.addTarget(wr);
        return true;
    }

    /**
     * Get a list of all the devices with their targets
      * @return The list
     */
    public String getListeners(){
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue("None yet");
        devices.forEach( (id,device) -> join.add( id+" -> "+device.getWritableIDs()));
        return join.toString();
    }
    /* ************************* READ XML SETTINGS *******************************************/
    /**
     * Reads the settings for the worker from the given xml file, this mainly
     * consists of devices with their commands.
     */
    private void readFromXML() {

        var i2cOpt = XMLtools.getFirstElementByTag( settingsPath, "i2c");
        if( i2cOpt.isPresent() ){
            Logger.info("Found settings for a I2C bus");
            devices.values().forEach(I2CDevice::close);
            devices.clear();
            for( Element i2c_bus : XMLtools.getChildElements( i2cOpt.get(), "bus") ){
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

                    if( addDevice( id, script, label, bus, address ).isPresent() ){
                        Logger.info("Adding "+id+"("+address+") to the device list of controller "+bus);
                    }else{
                        Logger.error("Tried to add "+id+" to the i2c device list, but probe failed");
                    }
                }           
            }
        }else{
            Logger.info("No settings found for I2C, no use reading the commandsets.");
            return;
        }                            
        reloadSets();
    }

    private String reloadSets( ){
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

            var docOpt = XMLtools.readXML(p);
            if( docOpt.isEmpty() ){
                return "Syntax error in "+p.getFileName().toString();
            }
            var doc = docOpt.get();
            for( Element set : XMLtools.getAllElementsByTag(  doc , "commandset") ){
            
                String script = XMLtools.getStringAttribute( set,"script","");
                String defOut = XMLtools.getStringAttribute( set,"output","dec");

                for( Element command : XMLtools.getChildElements( set, "command")){
                    I2CCommand cmd = new I2CCommand();

                    String cmdID = XMLtools.getStringAttribute( command, "id", "");
                    cmd.setOutType( XMLtools.getStringAttribute( command,"output",defOut) );
                    cmd.setScale(XMLtools.getIntAttribute( command, "scale", -1));
                    cmd.setInfo( XMLtools.getStringAttribute( command, "info", "") );
                    cmd.setReadBits( XMLtools.getIntAttribute( command, "bits", 8) );
                    cmd.setMsbFirst( XMLtools.getBooleanAttribute( command, "msbfirst",true) );

                    for( Element step : XMLtools.getChildElements(command)){
                        if( step.getTagName().equalsIgnoreCase("repeat")){
                            cmd.addRepeat( NumberUtils.toInt(step.getAttribute("cnt")));
                            for(Element sub : XMLtools.getChildElements(step))
                                cmd.addStep(sub);
                            cmd.addReturn();
                        }else{
                            cmd.addStep(step);
                        }
                    }
                    commands.put( script+":"+cmdID,cmd);
                }     
            }
        }
        if( !commands.isEmpty() && executor==null ) // Only need the executor if there are actually commands
            executor = Executors.newSingleThreadExecutor();
        return "All files ("+xmls.size()+") read ok.";
    }
    /* ***************************************************************************************************** */
    /* ***************************************************************************************************** */
    /**
     * Converts an array of bytes to a list of ints according to the bits. Note that bytes aren't split in their nibbles
     *   fe. 10bits means take the first two bytes and shift right, then take the next two
     * @param bytes The array with the bytes to convert
     * @param bits The amount of bits the int should use
     * @param msbFirst If the msb byte comes first
     * @param signed If the resulting int is signed
     * @return The resulting ints
     */
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
     * Executes the given commandset
     * @param device The device to which to send the command
     * @param com The command to send
     * @return The bytes received as reply to the command
     */
    private synchronized List<Double> doCommand(ExtI2CDevice device, I2CCommand com  ) throws RuntimeIOException{

        var result = new ArrayList<Double>();
        device.probeIt();
        device.updateTimestamp();
        int repeat = 0;

        if( com != null ){
                var coms = com.getAll();
                for( int a=0;a<coms.size();a++){ // Run te steps, one by one (in order)
                    var cmd = coms.get(a);
                    byte[] toWrite = cmd.write;

                    switch( cmd.type ){ // Type of subcommand
                        case READ:
                            byte[] b;
                            try {
                                int rd = cmd.readCount;
                                if( rd < 1 ){
                                    rd *= -1; // index is stored as a negative number
                                    if( rd < result.size()) {
                                        rd = result.get(rd).intValue();
                                    }else{
                                        Logger.warn("Used invalid index of "+rd+" because result size "+result.size());
                                        break;
                                    }
                                }
                                while( rd > 0 ) {
                                    if (toWrite.length == 1) {
                                        // after writing the first byte, read readCount bytes and put in readBuffer
                                        b = new byte[Math.min(rd, 32)];
                                        device.readI2CBlockData(toWrite[0], b);
                                    } else {
                                        if (toWrite.length != 0)
                                            device.writeBytes(toWrite); // write all the bytes in the array
                                        b = device.readBytes(Math.min(rd, 32));
                                    }
                                    if( debug ){
                                        Logger.info( "Read: "+Tools.fromBytesToHexString(b));
                                    }
                                    rd-=b.length;
                                    convertBytesToInt(b,cmd.bits,cmd.isMsbFirst(),cmd.isSigned()).forEach(
                                            x -> result.add(Tools.roundDouble((double)x,0)) );
                                }
                            }catch( RuntimeIOException e ){
                                Logger.error("Error trying to read from "+device.getAddr()+": "+e.getMessage());
                                continue;
                            }

                            break;
                        case WRITE:
                            device.writeBytes( toWrite ) ;
                            break;
                        case ALTER_OR: // Read the register, alter it and write it again
                            device.writeBytes(toWrite[0]);
                            toWrite[1] |= device.readByte();
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_AND:
                            device.writeByte(toWrite[0]);
                            toWrite[1] &= device.readByte();
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_NOT:
                            device.writeByte(toWrite[0]);
                            toWrite[1] = device.readByte();
                            toWrite[1] ^= 0xFF;
                            device.writeBytes(toWrite);
                            break;
                        case ALTER_XOR:
                            device.writeByte(toWrite[0]);
                            toWrite[1] ^= device.readByte();
                            device.writeBytes(toWrite);
                            break;
                        case WAIT:
                            try {
                                TimeUnit.MILLISECONDS.sleep(cmd.readCount);
                            } catch (InterruptedException e) {
                                Logger.error(e);
                            }
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
                            var res = cmd.fab.solveFor(result.toArray( new Double[0] ));
                            if( com.getScale()!=-1 ) {
                                res = Tools.roundDouble(res,com.getScale());
                            }
                            result.set( cmd.index,res);
                            break;
                        case DISCARD:
                            while( result.size() > cmd.readCount) {
                                result.remove(cmd.readCount);
                            }
                            break;
                        case REPEAT:
                            repeat = cmd.readCount;
                            break;
                        case RETURN:
                            if( repeat > 0){
                                a=cmd.readCount;
                                repeat --;
                            }
                            break;
                        default:
                            Logger.error("Somehow managed to use an none existing type: "+cmd.type);
                            break;
                    }
                }
            if( result.size() != com.getTotalIntReturn())
                Logger.error("Didn't read the expected amount of bytes: "+result.size()+" instead of "+com.getTotalIntReturn());
        }
        return result;
    }
    /* ***************************************************************************************************** */
    /**
     * Search the bus of the specified controller for responsive devices
     * 
     * @param controller The index of the controller to look at
     * @return A list of found used addresses, prepended with Busy if currently addressed by a driver or Used if by dcafs
     */
    public static String detectI2Cdevices( int controller ){
		StringJoiner b = new StringJoiner("\r\n");
        var gr = TelnetCodes.TEXT_GREEN;
        var ye = TelnetCodes.TEXT_YELLOW;
        var or = TelnetCodes.TEXT_ORANGE;
        var red = TelnetCodes.TEXT_RED;
		for (int device_address = 0; device_address < 128; device_address++) {
			if (device_address >= 0x03 && device_address <= 0x77) {
				try (I2CDevice device = new I2CDevice(controller, device_address)) {
					if (device.probe( I2CDevice.ProbeMode.AUTO )) {
						b.add( gr+"Free"+ye+" - 0x"+String.format("%02x ", device_address) );
					}
				} catch (DeviceBusyException e) {
					b.add(red+"Busy"+ye+" - 0x"+String.format("%02x ", device_address)+"(in use by another process)");
				} catch( DeviceAlreadyOpenedException e){
				    b.add(or+"Used"+ye+" - 0x"+String.format("%02x ", device_address)+"(in use by dcafs)");
                } catch( RuntimeIOException e ){
                    return "No such bus "+controller;
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
            String gr = html?"":TelnetCodes.TEXT_GREEN;
            String cyan = html?"":TelnetCodes.TEXT_CYAN;
            String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

            switch( cmd[0] ){
                case "?":
                    StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
                    join.add( cyan+"Create/load devices/scripts"+reg)
                                .add(gr+"  i2c:detect,bus"+reg+" -> Detect the devices connected on the given bus")
                                .add(gr+"  i2c:adddevice,id,bus,address,script"+reg+" -> Add a device on bus at hex address that uses script")
                                .add(gr+"  i2c:addblank,scriptname"+reg+" -> Adds a blank i2c script to the default folder")
                                .add(gr+"  i2c:reload"+reg+" -> Reload the commandset file(s)")
                            .add("").add( cyan+" Get info"+reg)
                                .add(gr+"  i2c:list"+reg+" -> List all registered devices with commandsets")
                                .add(gr+"  i2c:listeners"+reg+" -> List all the devices with their listeners")
                            .add("").add( cyan+" Other"+reg)
                                .add(gr+"  i2c:debug,on/off"+reg+" -> Enable or disable extra debug feedback in logs")
                                .add(gr+"  i2c:device,commandset"+reg+" -> Use the given command on the device")
                                .add(gr+"  i2c:id"+reg+" -> Request the data received from the given id (can be regex)");
                    return join.toString();
                case "list": return getDeviceList(true);
                case "reload": return reloadSets();
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
                        return "Incorrect number of variables: i2c:adddevice,id,bus,hexaddress,script";
                    if( !Files.isDirectory(scriptsPath)) {
                        try {
                            Files.createDirectories(scriptsPath);
                        }catch( IOException e){
                            Logger.error(e);
                        }
                    }
                    var opt =  addDevice(cmd[1],cmd[4],"void", NumberUtils.toInt(cmd[2]),Tools.parseInt(cmd[3],-1));
                    if( opt.isEmpty())
                        return "Probing "+cmd[3]+" on bus "+cmd[2]+" failed";
                    opt.ifPresent( d -> d.storeInXml( XMLfab.withRoot(settingsPath,"dcafs","settings").digRoot("i2c")));

                    // Check if the script already exists, if not build it
                    var p = scriptsPath.resolve(cmd[4]+".xml");
                    if( !Files.exists(p)){
                        XMLfab.withRoot(p,"commandset").attr("script",cmd[4])
                                .addParentToRoot("command","An empty command to start with")
                                .attr("id","cmdname").attr("info","what this does")
                                .build();
                        readFromXML();
                        return "Device added, created blank script at "+p;
                    }else{
                        return "Device added, using existing script";
                    }
                case "detect":
                    if( cmd.length == 2){
                        return I2CWorker.detectI2Cdevices( Integer.parseInt(cmd[1]) );
                    }else{
                        return "Incorrect number of variables: i2c:detect,bus";
                    }
                default:
                    if( cmd.length!=2) {
                        StringBuilder oks= new StringBuilder();
                        for( var dev : devices.entrySet()){
                            if( dev.getKey().matches(cmd[0])){
                                dev.getValue().addTarget(wr);
                                if(oks.length() > 0)
                                    oks.append(", ");
                                oks.append(dev.getKey());
                            }
                        }
                        if(oks.length() > 0) {
                            return "Request for i2c:"+cmd[0]+" accepted for "+oks;
                        }else{
                            Logger.error("No matches for i2c:"+cmd[0]+" requested by "+wr.getID());
                            return "No matches for i2c:"+cmd[0];
                        }
                    }
                    if( wr!=null && wr.getID().equalsIgnoreCase("telnet") ){
                        if( cmd[0].isEmpty() ){
                            removeWritable(wr);
                        }else {
                            addTarget(cmd[0], wr);
                        }
                    }
                    if( addWork(cmd[0], cmd[1]) ){
                        return "Command "+cmd[0]+":"+cmd[1]+" added to the queue.";
                    }else{
                        return "Failed to add command to the queue, probably wrong device or command";
                    }
            }
    }
    /**
     * Add work to the worker
     *
     * @param id  The device that needs to execute a command
     * @param command The command the device needs to execute
     * @return True if the command was valid
     */
    private boolean addWork(String id, String command) {
        ExtI2CDevice device = devices.get(id.toLowerCase());
        if (device == null) {
            Logger.error("Invalid job received, unknown device '" + id + "'");
            return false;
        }
        if (!commands.containsKey(device.getScript()+":"+command)) {
            Logger.error("Invalid command received '" + device.getScript()+":"+command + "'.");
            return false;
        }
        executor.submit(()->doWork(device,command));
        return true;
    }
    public void doWork(ExtI2CDevice device, String cmdID) {

        // Execute the command
        I2CCommand com = commands.get(device.getScript()+":"+cmdID);
        List<Double> altRes;
        try {
            altRes = doCommand(device, com);
            device.updateTimestamp();
        }catch( RuntimeIOException e ){
            Logger.error("Failed to run command for "+device.getAddr()+":"+e.getMessage());
            return;
        }
        // Do something with the result...
        StringJoiner output = new StringJoiner(";",device.getID()+";"+cmdID+";","");
        switch (com.getOutType()) {
            case DEC -> altRes.forEach(x -> {
                if (x.toString().endsWith(".0")) {
                    output.add(Integer.toString(x.intValue()));
                } else {
                    output.add(x.toString());
                }
            });
            case HEX -> altRes.forEach(x -> {
                String val = Integer.toHexString(x.intValue()).toUpperCase();
                output.add("0x" + (val.length() == 1 ? "0" : "") + val);
            });
            case BIN -> altRes.forEach(x -> output.add("0b" + Integer.toBinaryString(x.intValue())));
            case CHAR -> {
                var line = new StringJoiner("");
                altRes.forEach(x -> line.add("" + (char) x.intValue()));
                output.add(line.toString());
            }
        }
        if( !device.getLabel().equalsIgnoreCase("void") ){
            dQueue.add( Datagram.build(output.toString()).label(device.getLabel()+":"+cmdID).origin(device.getID()).payload(altRes) );
        }
        try {
            device.getTargets().forEach(wr -> wr.writeLine(output.toString()));
            device.getTargets().removeIf(wr -> !wr.isConnectionValid());
        }catch(Exception e){
            Logger.error(e);
        }

        Logger.tag("RAW").warn( "1\t" + device.getID()+":"+device.getLabel() + "\t" + output );
    }
}