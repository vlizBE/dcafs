package io.mqtt;

import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class MqttPool implements Commandable, MqttWriting {

    Map<String, MqttWorker> mqttWorkers = new HashMap<>();
    Path settingsFile;
    static final String UNKNOWN_CMD = "unknown command";
    RealtimeValues rtvals;
    BlockingQueue<Datagram> dQueue;

    public MqttPool(Path settingsFile, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue ){
        this.settingsFile=settingsFile;
        this.rtvals=rtvals;
        this.dQueue=dQueue;

        readXMLsettings();
    }
    public boolean sendToBroker( String id, String device, String param, double value) {
        MqttWorker worker = mqttWorkers.get(id);

        if (worker != null) {
            if (value != -999) {
                return worker.addWork( new MqttWork(device, param, value) );
            }
        }
        return false;
    }
    /**
     * Get The @see MQTTWorker based on the given id
     *
     * @param id The id of the MQTT worker requested
     * @return The worder requested or null if not found
     */
    public Optional<MqttWorker> getMqttWorker(String id) {
        return Optional.ofNullable( mqttWorkers.get(id) );
    }

    /**
     * Get a list of all the MQTTWorker id's
     *
     * @return List of all the id's
     */
    public Set<String> getMqttWorkerIDs() {
        return mqttWorkers.keySet();
    }

    /**
     * Get a descriptive listing of the current brokers/workers and their
     * subscriptions
     *
     * @return The earlier mentioned descriptive listing
     */
    public String getMqttBrokersInfo() {
        StringJoiner join = new StringJoiner("\r\n", "id -> broker -> online?\r\n", "");
        join.setEmptyValue("No brokers yet");
        mqttWorkers.forEach((id, worker) -> join
                .add(id + " -> " + worker.getBrokerAddress() + " -> " + (worker.isConnected() ? "online" : "offline"))
                .add(worker.getSubscriptions("\r\n")));
        return join.toString();
    }

    /**
     * Adds a subscription to a certain MQTTWorker
     *
     * @param id    The id of the worker to add it to
     * @param label The label associated wit the data, this will be given to @see
     *              BaseWorker when data is recevied
     * @param topic The topic to subscribe to
     * @return True if a subscription was successfully added
     */
    public boolean addMQTTSubscription(String id, String label, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.addSubscription(topic, label);
    }
    public boolean addBroker( String id, String address, String defTopic){
        mqttWorkers.put( id, new MqttWorker(address,defTopic,dQueue) );
        return updateMQTTsettings(id);
    }
    /**
     * Remove a subscription from a certain MQTTWorker
     *
     * @param id    The id of the worker
     * @param topic The topic to remove
     * @return True if it was removed, false if it wasn't either because not found
     *         or no such worker
     */
    public boolean removeMQTTSubscription(String id, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.removeSubscription(topic);
    }

    /**
     * Update the settings in the xml for a certain MQTTWorker based on id
     *
     * @param id The worker of which the settings need to be altered
     * @return True if updated
     */
    public boolean updateMQTTsettings(String id) {
        XMLfab fab = XMLfab.withRoot(settingsFile,"settings")
                            .selectOrCreateParent("mqtt")
                            .down();

        MqttWorker worker = mqttWorkers.get(id);
        if (worker != null ){
            worker.updateXMLsettings(fab, true);
            return true;
        }
        return false;
    }

    /**
     * Reload the settings for a certain MQTTWorker from the settings.xml
     *
     * @param id The worker for which the settings need to be reloaded
     * @return True if this was succesful
     */
    public boolean reloadMQTTsettings(String id) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;

        Element mqtt;
        var settingsDoc = XMLtools.readXML(settingsFile);
        if ((mqtt = XMLtools.getFirstElementByTag(settingsDoc, "mqtt")) != null) {
            for (Element broker : XMLtools.getChildElements(mqtt, "broker")) {
                if (XMLtools.getStringAttribute(broker, "id", "general").equals(id)) {
                    worker.readSettings(broker);
                    return true;
                }
            }
        }
        return false;
    }
    /**
     * Reload the settings from the settings.xml
     *
     * @return True if this was successful
     */
    public boolean readXMLsettings() {

        var settingsDoc = XMLtools.readXML(settingsFile);
        var mqtt = XMLtools.getFirstElementByTag(settingsDoc, "mqtt");
        if (mqtt != null) {
            for (Element broker : XMLtools.getChildElements(mqtt, "broker")) {
                String id = XMLtools.getStringAttribute(broker, "id", "general");
                Logger.info("Adding MQTT broker called " + id);
                mqttWorkers.put(id, new MqttWorker(broker, dQueue));
            }
            return true;
        }
        return false;
    }


    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        String[] cmd = request[1].split(",");
        String nl = html ? "<br>" : "\r\n";

        switch( cmd[0] ){
            //mqtt:brokers
            case "brokers": return getMqttBrokersInfo();
            //mqtt:subscribe,ubidots,aanderaa,outdoor_hub/1844_temperature
            case "addbroker":
                if( cmd.length!=4)
                    return "Wrong amount of arguments: mqtt:addbroker,id,address,deftopic";
                if( addBroker(cmd[1],cmd[2],cmd[3]) )
                    return "Broker added";
                return "Failed to add broker";
            case "subscribe":
                if( cmd.length == 4){
                    addMQTTSubscription(cmd[1], cmd[2], cmd[3]);
                    return nl+"Subscription added, send 'mqtt:store,"+cmd[1]+"' to save settings to xml";
                }else{
                    return nl+"Incorrect amount of cmd: mqtt:subscribe,brokerid,label,topic";
                }
            case "unsubscribe":
                if( cmd.length == 3){
                    if( removeMQTTSubscription(cmd[1], cmd[2]) ){
                        return nl+"Subscription removed, send 'mqtt:store,"+cmd[1]+"' to save settings to xml";
                    }else{
                        return nl+"Failed to remove subscription, probably typo?";
                    }
                }else{
                    return nl+"Incorrect amount of cmd: mqtt:unsubscribe,brokerid,topic";
                }
            case "reload":
                if( cmd.length == 2){
                    reloadMQTTsettings(cmd[1]);
                    return nl+"Settings for "+cmd[1]+" reloaded.";
                }else{
                    return "Incorrect amount of cmd: mqtt:reload,brokerid";
                }
            case "store":
                if( cmd.length == 2){
                    updateMQTTsettings(cmd[1]);
                    return nl+"Settings updated";
                }else{
                    return "Incorrect amount of cmd: mqtt:store,brokerid";
                }
            case "forward":
                if( cmd.length == 2){
                    getMqttWorker(cmd[1]).ifPresent( x -> x.registerWritable(wr));
                    return "Forward requested";
                }else{
                    return "Incorrect amount of cmd: mqtt:forward,brokerid";
                }
            case "send":
                if( cmd.length != 3){
                    Logger.warn( "Not enough arguments, expected mqtt:send,brokerid,topic:value" );
                    return "Not enough arguments, expected mqtt:send,brokerid,topic:value";
                }else if( !cmd[2].contains(":") ){
                    return "No proper topic:value given, got "+cmd[2]+" instead.";
                }
                if( getMqttWorker(cmd[1]).isEmpty() ){
                    Logger.warn("No such mqttworker to so send command "+cmd[1]);
                    return "No such MQTTWorker: "+cmd[1];
                }
                String[] topVal = cmd[2].split(":");
                double val = rtvals.getDouble(topVal[1], -999);
                getMqttWorker(cmd[1]).ifPresent( w -> w.addWork(topVal[0],""+val));
                return "Data send to "+cmd[1];
            case "?":
                StringJoiner join = new StringJoiner(nl);
                join.add(TelnetCodes.TEXT_MAGENTA+"The MQTT manager manages the workers that connect to brokers");
                join.add(TelnetCodes.TEXT_GREEN+"General"+TelnetCodes.TEXT_YELLOW)
                        .add( "   mqtt:addbroker,brokerid,address -> Add a new broker with the given id found at the address")
                        .add( "   mqtt:brokers -> Get a listing of the current registered brokers")
                        .add( "   mqtt:reload,brokerid -> Reload the settings for the broker from the xml.")
                        .add( "   mqtt:store,brokerid -> Store the current settings of the broker to the xml.")
                        .add( "   mqtt:? -> Show this message");
                join.add(TelnetCodes.TEXT_GREEN+"Subscriptions"+TelnetCodes.TEXT_YELLOW)
                        .add( "   mqtt:subscribe,brokerid,label,topic -> Subscribe to a topic with given label on given broker")
                        .add( "   mqtt:unsubscribe,brokerid,topic -> Unsubscribe from a topic on given broker")
                        .add( "   mqtt:unsubscribe,brokerid,all -> Unsubscribe from all topics on given broker");
                join.add(TelnetCodes.TEXT_GREEN+"Send & Receive"+TelnetCodes.TEXT_YELLOW)
                        .add( "   mqtt:forward,brokerid -> Forwards the data received from the given broker to the issuing writable")
                        .add( "   mqtt:send,brokerid,topic:value -> Sends the value to the topic of the brokerid");
                return join.toString();
            default: return UNKNOWN_CMD+": "+cmd[0];
        }
    }

    @Override
    public boolean removeWritable(Writable wr) {
        int cnt=0;
        for( MqttWorker worker:mqttWorkers.values()){
            cnt += worker.removeWritable(wr)?1:0;
        }
        return cnt!=0;
    }
}
