package io.stream.serialport;

import com.fazecast.jSerialComm.*;
import io.stream.BaseStream;
import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.time.Instant;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

/**
 * Variant of the StreamHandler class that is
 */
public class SerialStream extends BaseStream implements Writable {

    protected SerialPort serialPort;
    String port ="";

    public SerialStream(String port, BlockingQueue<Datagram> dQueue, String label, int priority) {
        super("", label, dQueue);
        setPriority(priority);
        setPort(port);
    }

    public SerialStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
    }
    protected String getType(){
        return "serial";
    }
    public boolean setPort(String port) {
        try{
            this.port=port;
            serialPort = SerialPort.getCommPort(port);
        }catch( SerialPortInvalidPortException e ){
            Logger.error("No such serial port: " + port);
            Logger.error(e);
            return false;
        }
        return true;
    }

    public String getInfo() {
        String info = "No connection to "+port;
        if( serialPort!=null){
            info = serialPort.getSystemPortName();
            if( info.equalsIgnoreCase("0"))
                info=port;
            info += " | "+ getSerialSettings();
        }
        return "SERIAL [" + id + "|" + label + "] " + info;
    }

    public boolean connect() {
        return this.doConnect(eol);
    }

    private boolean doConnect(String delimiter) {
        eol = delimiter;
        connectionAttempts++;

        if (serialPort == null) {
            return false;
        }

        if (serialPort.openPort()) {
            this.addListener();
            Logger.info("Connected to serialport " + serialPort.getSystemPortName());
            listeners.forEach( l -> l.notifyOpened(id) );
        } else {
            Logger.info("FAILED connection to serialport " + serialPort.getSystemPortName());
            return false;
        }
        return true;
    }

    private void addListener() {
        if (serialPort == null)
            return;

        if (eol.isEmpty()) {
            serialPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    processListenerEvent( event.getReceivedData() );
                }
            });
        } else {
            serialPort.addDataListener(new MessageListener(eol));
        }
    }

    private final class MessageListener implements SerialPortMessageListenerWithExceptions {

        byte[] deli;

        public MessageListener(String delimiter) {
            this.deli = delimiter.getBytes();
        }

        @Override
        public int getListeningEvents() {
            return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
        }

        @Override
        public byte[] getMessageDelimiter() {
            return deli;
        }

        @Override
        public boolean delimiterIndicatesEndOfMessage() {
            return true;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
            processMessageEvent( event.getReceivedData() );
        }

        @Override
        public void catchException(Exception e) {
            Logger.error(e);
        }
    }
    protected void processListenerEvent( byte[] data ){
        Logger.debug(id+ " <-- "+Tools.fromBytesToHexString(data));
        Logger.tag("RAW").warn(priority + "\t" + label+"|"+id + "\t" + Tools.fromBytesToHexString(data));

        if( !targets.isEmpty() ){
            try {
                targets.forEach(dt -> eventLoopGroup.submit(()-> {
                    try {
                        if( dt.getID().contains("telnet")) {
                            dt.writeString(Tools.fromBytesToHexString(data)+" ");
                        }else{
                            dt.writeBytes(data);
                        }
                    } catch (Exception e) {
                        Logger.error(id + " -> Something bad while writeLine to " + dt.getID());
                        Logger.error(e);
                    }
                }));
                targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
            }catch(Exception e){
                Logger.error(id+" -> Something bad in serialport");
                Logger.error(e);
            }
        }
    }
    protected void processMessageEvent(byte[] data){
        String msg = new String(data).replace(eol, "");

        // Log anything and everything (except empty strings)
        if( !msg.isBlank() && log ) {        // If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn( label+"|"+id + "\t" + msg);
        }
        if(debug) {
            Logger.info(id + " -> " + msg);
            Logger.info(Tools.fromBytesToHexString(msg.getBytes()));
        }

        // Implement the use of labels
        if( !label.isEmpty() && dQueue !=null ) { // No use adding to queue without label
            dQueue.add( Datagram.build(msg)
                    .label(label)
                    .priority(priority)
                    .timestamp() );
        }

        // Implement the use of store
        if( !rtvals.isEmpty() ){
            var split = msg.trim().split(delimiter);
            if( split.length < rtvals.size()) {
                Logger.error(id + " -> Not enough data after split, got " + split.length + " from " + msg);
            }else{
                for( int a=0;a<rtvals.size();a++){
                    if( rtvals.get(a)!=null)
                        rtvals.get(a).parseValue(split[a]);
                }
            }
        }

        forwardData(msg);

        long p = Instant.now().toEpochMilli() - timestamp; // Calculate the time between 'now' and when the previous
        // message was received
        if (p > 0) { // If this time is valid
            passed = p; // Store it
        }
        timestamp = Instant.now().toEpochMilli(); // Store the timestamp of the received message
    }
    protected void forwardData( String message){
        if( !targets.isEmpty() ){
            try {
                targets.forEach(dt -> eventLoopGroup.submit(()-> {
                    try {
                        dt.writeLine(message);
                    } catch (Exception e) {
                        Logger.error(id + " -> Something bad while writeLine to " + dt.getID());
                        Logger.error(e);
                    }
                }));
                targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
            }catch(Exception e){
                Logger.error(id+" -> Something bad in serialport");
                Logger.error(e);
            }
        }
    }
    public void alterSerialSettings(String settings) {
        if (serialPort == null) {
            return;
        }

        String[] split = settings.split(",");
        int stopbits;
        int parity = SerialPort.NO_PARITY;

        if (split.length == 1)
            split = settings.split(";");

        stopbits = switch (split[2]) {
            case "1.5" -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case "2" -> SerialPort.TWO_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
        if (split.length > 3) {
            parity = switch (split[3]) {
                case "even" -> SerialPort.EVEN_PARITY;
                case "odd" -> SerialPort.ODD_PARITY;
                case "mark" -> SerialPort.MARK_PARITY;
                case "space" -> SerialPort.SPACE_PARITY;
                default -> SerialPort.NO_PARITY;
            };
        }

        serialPort.setBaudRate(Tools.parseInt(split[0], 19200));
        serialPort.setNumDataBits(Tools.parseInt(split[1], 8));
        serialPort.setNumStopBits(stopbits);
        serialPort.setParity(parity);
    }

    public String getSerialSettings() {
        return serialPort.getBaudRate() + "," + serialPort.getNumDataBits() + "," + getStopbits() + "," + getParity();
    }

    private String getParity() {
        return switch (serialPort.getParity()) {
            case SerialPort.EVEN_PARITY -> "even";
            case SerialPort.ODD_PARITY -> "odd";
            case SerialPort.MARK_PARITY -> "mark";
            case SerialPort.SPACE_PARITY -> "space";
            default -> "none";
        };
    }

    private String getStopbits() {
        return switch (serialPort.getNumStopBits()) {
            case SerialPort.ONE_POINT_FIVE_STOP_BITS -> "1.5";
            case SerialPort.TWO_STOP_BITS -> "2";
            default -> "1";
        };
    }

    public void setBaudrate(int baudrate) {
        serialPort.setBaudRate(baudrate);
    }

    public static boolean portExists( String port ){
        for( SerialPort p : SerialPort.getCommPorts() ){
            if( p.getSystemPortName().equalsIgnoreCase(port))
                return true;
        }
        return false;
    }
    public static String portList( ){
        StringJoiner join = new StringJoiner(", ");
        join.setEmptyValue("No serial ports found.");        
        for( SerialPort p : SerialPort.getCommPorts() )
            join.add(p.getSystemPortName());
        return join.toString();
    }
    /* ************************************** W R I T I N G ************************************************************/
    /**
     * Sending data that will be appended by the default newline string.
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeLine(String message) {
        return writeString(message + eol);
    }

    /**
     * Sending data that won't be appended with anything
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeString(String message) {
        return write(message.getBytes());
    }

    /**
     * Sending raw data
     * @param data The bytes to write
     * @return True if succeeded
     */
    @Override
    public synchronized boolean writeBytes(byte[] data) {
         return write(data);
    }
    /**
     * Sending a hexidecimal value
     * 
     * @param value The hex to send
     * @return True If nothing was wrong with the connection
     */
    public synchronized boolean writeHex(int value) {
        byte[] ar = { (byte) value };
        return write(ar);
    }

    /**
     * Sending data that won't be appended with anything
     * 
     * @param data The data to send.
     * @return True If nothing was wrong with the connection
     */
    public synchronized boolean write(byte[] data) {
        Logger.debug(id+" --> "+Tools.fromBytesToHexString(data));
        if (serialPort != null && serialPort.isOpen() && serialPort.bytesAwaitingWrite()<8000) {
            var res=-1;
            try{
                res = serialPort.writeBytes(data, data.length);

            }catch(Exception e) {
                Logger.error(e);
            }
            if( res==-1){
                Logger.error(id+" -> Error writing to port "+serialPort.getSystemPortName());
            }else if( res != data.length ){
                Logger.error(id+" -> The amount of bytes written does not equal expected.");
            }
            return  res == data.length;
        }else if( serialPort==null){
            Logger.error(id+" -> No write done, serialport is null.");

            return false;
        }else if( !serialPort.isOpen()){
            Logger.error(id+" -> No write done, serialport is closed.");
        }
        if( serialPort.bytesAwaitingWrite()<8000 ){
            Logger.error("Data not being read from "+id);
        }
        return false;
    }

    public boolean disconnect() {
        if (serialPort != null && serialPort.isOpen())
            return serialPort.closePort();
        return false;
    }

    @Override
    public boolean isConnectionValid() {
        if (serialPort == null || serialPort.bytesAwaitingWrite()>8000)
            return false;
        return serialPort.isOpen();
    }

    @Override
    public String getID() {
        return id;
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        if (!setPort(XMLtools.getChildStringValueByTag(stream, "port", ""))) {
            return false;
        }
        alterSerialSettings(XMLtools.getChildStringValueByTag(stream, "serialsettings", "19200,8,1,none"));
        return true;
    }
    @Override
    protected boolean writeExtraToXML(XMLfab fab) {
        fab.alterChild("serialsettings",getSerialSettings());
        fab.alterChild("port",serialPort.getSystemPortName());
        return true;
    }
    @Override
    public long getLastTimestamp() {
        return timestamp;
    }


}