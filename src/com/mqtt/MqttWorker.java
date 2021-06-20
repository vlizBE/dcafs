package com.mqtt;

import com.stream.Writable;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.*;

/**
 * Non-blocking worker that handles the connection to a broker. How publish/subscribe works:
 * 1) Add the work to the queue
 * 2) Check if there's an active connection.
 * 		a) If not, start the doConnect thread and add the work to the queue
 * 		b) If so, go to 3 if publish
 * 3) Check if the worker is currently publishing
 *     a) If not, start the doPublish thread
 *     b) If so, do nothing
 * 
 * If a connection is established all subscriptions will be subscribed to and
 * doPublish will be started if any work is to be done.
 *
 * For now nothing happens with the connection when no work is present and no subscriptions are made, an
 * option is to disconnect.
 */
public class MqttWorker implements MqttCallbackExtended {

	private final BlockingQueue<MqttWork> mqttQueue = new LinkedBlockingQueue<>(); // Queue that holds the messages to
																				// publish

	private MqttClient client = null;
	MemoryPersistence persistence = new MemoryPersistence();

	MqttConnectOptions connOpts = null;

	String id = ""; // Name/if/title for this worker
	String broker = ""; // The address of the broker
	String clientId = ""; // Client id to use for the broker
	String defTopic = ""; // The defaulttopic for this broker, will be prepended to publish/subscribe etc

	enum MQTT_FLAVOUR {
		UBIDOTS, MOSQUITO
	}

	MQTT_FLAVOUR flavour = MQTT_FLAVOUR.UBIDOTS; // Incase the message layout is different depending on te broker

	private boolean publishing = false; // Flag that shows if the worker is publishing data
	private boolean connecting = false; // Flag that shows if the worker is trying to connect to the broker

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // Scheduler for the publish and
																						// connect class
	private BlockingQueue<Datagram> dQueue; // Queue for a BaseWorker

	private final Map<String, String> subscriptions = new HashMap<>(); // Map containing all the subscriptions

	private boolean settingsChanged = false; // Flag that shows if the settings were changed from what was read

	private final ArrayList<Writable> targets = new ArrayList<>();

	/**
	 * Constructor to create a worker without the xml
	 * 
	 * @param broker   The address of the broker
	 * @param clientId The client id to connect to the broker
	 * @param topic    The default topic (will be added to all subscribe/publish
	 *                 requests)
	 */
	public MqttWorker(String broker, String clientId, String topic) {
		this.broker = broker;
		this.clientId = clientId;
		this.defTopic = topic;
		applySettings();
	}

	/**
	 * Constructor that uses an xml element that contain the settings for connection
	 * and possible subscriptions
	 * 
	 * @param mqtt The element containing the settings
	 */
	public MqttWorker(Element mqtt, BlockingQueue<Datagram> dQueue) {
		this.readSettings(mqtt);
		this.dQueue = dQueue;
	}

	/**
	 * Set the queue from a BaseWorker so that message are processsed
	 * 
	 * @param dQueue The queue for a BaseWorker
	 */
	public void setDataQueue(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
	}

	/**
	 * Set the id of this worker, this is added to the work given to the
	 * baseworker
	 * 
	 * @param id The id for this worker
	 */
	public void setID(String id) {
		this.id = id;
	}

	/**
	 * Get the address of the broker
	 * 
	 * @return the address of the broker
	 */
	public String getBroker() {
		return this.broker;
	}

	/* ************************************ Q U E U E ************************************************************* **/
	/**
	 * Give work to the worker, it will be placed in the queue
	 * 
	 * @param work The work to do
	 * @return true if the work was accepted
	 */
	public boolean addWork(MqttWork work) {
		if( work.isEmpty() )
			return false;
		this.mqttQueue.add(work);
		if (!client.isConnected()) { // If not connected, try to connect
			if (!connecting) {
				connecting = true;
				this.scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS);
			}
		} else if (!publishing) { // If currently not publishing ( there's a 30s timeout) enable publishing
			publishing = true;
			scheduler.schedule( new Publisher(), 0, TimeUnit.SECONDS);
		}
		return true;
	}
	public boolean addWork( String topic, String value){
		return addWork( new MqttWork(topic,value));
	}
	/* ************************************** S E T T I N G S ****************************************************** */
	/**
	 * Read the settings for the worker from the xml element
	 * 
	 * @param mqtt The element containing the info
	 */
	public void readSettings(Element mqtt) {
		this.broker = XMLtools.getChildValueByTag(mqtt, "address", "");
		this.clientId = XMLtools.getChildValueByTag(mqtt, "clientid", "");
		this.defTopic = XMLtools.getChildValueByTag(mqtt, "defaulttopic", "");
		this.id = XMLtools.getStringAttribute(mqtt, "id", "general");

		if (broker.contains("ubidots")) {
			flavour = MQTT_FLAVOUR.UBIDOTS;
		} else {
			flavour = MQTT_FLAVOUR.MOSQUITO;
		}
		
		subscriptions.clear();

		for (Element e : XMLtools.getChildElements(mqtt, "subscribe")) {
			String topic = e.getTextContent();
			String label = e.getAttribute("label");			

			if (topic != null) {
				this.addSubscription(topic, label);
			}
		}
		settingsChanged = false; // Because this was set to true by the earlier
		applySettings();
	}

	/**
	 * Alter the element to reflect current settings
	 * 
	 * @param xmlDoc The doc in which the element resides (needed to make Elements)
	 * @param mqtt   The element currently used
	 * @return True if something was changed
	 */
	public boolean updateXMLsettings(Document xmlDoc, Element mqtt) {
		if (!settingsChanged)
			return false;

		while (mqtt.hasChildNodes()) // Remove all the current children
			mqtt.removeChild(mqtt.getFirstChild());

		XMLtools.createChildTextElement(xmlDoc, mqtt, "address", broker);
		XMLtools.createChildTextElement(xmlDoc, mqtt, "clientid", clientId);
		XMLtools.createChildTextElement(xmlDoc, mqtt, "defaulttopic", defTopic);

		subscriptions.forEach(
			(topic,label) -> {
				Element sub = xmlDoc.createElement("subscribe");
				sub.setTextContent(topic);
				sub.setAttribute("label", label);
				mqtt.appendChild(sub);
			}
		);
		settingsChanged=false;
		return true;
	}
	/**
	 * Apply the settings read from the xml element
	 */
	private void applySettings() {

		connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(false);
		if( !clientId.isBlank() )
			connOpts.setUserName(clientId);
		connOpts.setAutomaticReconnect(true); //works
		
		Logger.info("Creating client for "+this.broker);

		try {
			if( client != null ){
				client.disconnect();
			}
			client = new MqttClient( this.broker, MqttClient.generateClientId(), persistence);
			client.setTimeToWait(10000);
			client.setCallback(this);
			
		} catch (MqttException e) {
			Logger.error(e);
		}
		// Subscriptions
		for( String key : subscriptions.keySet() ){
			subscribe( key );
		}	
	}	
	/* ****************************************** S U B S C R I B E  *********************************************** */
	/**
	 * Subscribe to a given topic on the associated broker
	 * @param topic The topic so subscribe to
	 * @param label The label used by the BaseWorker to process the received data
	 * @return True if added
	 */
	public boolean addSubscription( String topic, String label ){
		settingsChanged=true;
		if( defTopic.endsWith("/") && topic.startsWith("/") )
			topic = topic.substring(1);			
		
		subscriptions.put(topic, label);
		return subscribe( topic );
	}
	/**
	 * Unsubscribe from a topic
	 * @param topic The topic to unsubscribe from
	 * @return True if a subscription to that topic existed and was removed
	 */
	public boolean removeSubscription( String topic ){
		settingsChanged=true;
		if( topic.equals("all")){
			subscriptions.keySet().removeIf(this::unsubscribe);
			return subscriptions.isEmpty();
		}else{
			if( subscriptions.remove(topic) != null){
				unsubscribe( topic );
				return true;
			}
		}
		return false;
	}
	/**
	 * Obtain a list of all current subscriptions
	 * @param nl The delimiter to use for a newline
	 * @return Listing of the topics subscribed with the associated label
	 */
	public String getSubscriptions( String nl ){
		if( subscriptions.isEmpty() )
			return "No active subscriptions"+nl;
		StringJoiner join = new StringJoiner(nl,"",nl);
		subscriptions.forEach(
			(topic,label) -> join.add("==> "+topic+" -> "+label)
		);
		return join.toString();
	}
	/**
	 * Private method used to subscribe to the given topic
	 * @param topic The topic to subscribe to
	 */	
	private boolean subscribe( String topic ){	
		if( client == null)
			return false;			
		if (!client.isConnected() ) { // If not connected, try to connect
			if( !connecting ){
				connecting=true;
				this.scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS );
			}
		} else{
			try {
				Logger.info("Subscribing to "+defTopic+topic);
				client.subscribe( defTopic + topic );
				return true;
			} catch (MqttException e) {
				Logger.error(e);
			}
		}
		return false;
	}
	/**
	 * Unsubscribe from the given topic
	 * @param topic The topic to unsubscribe from
	 * @return True if this was successful
	 */	
	private boolean unsubscribe( String topic ){
		try {
			Logger.info("Unsubscribing from "+defTopic+topic);
			client.unsubscribe(defTopic + topic);
		} catch (MqttException e) {
			Logger.error(e);
			return false;
		}
		return true;
	}
	/* ****************************************** R E C E I V I N G  ************************************************ */

	@Override
	public void messageArrived(String topic, MqttMessage message) {

		String load = new String(message.getPayload());	// Get the payload
		String label = subscriptions.get(topic.substring(defTopic.length()));

		Logger.tag("RAW").warn("1\t"+label+"\t"+load);  // Store it like any other received data

		if( dQueue != null ){	// If there's a valid BaseWorker queue
			dQueue.add( Datagram.build(load).label(label).origin(id) );

			if( !targets.isEmpty() ){
                targets.removeIf(dt -> !dt.writeLine( load ) );
			}
		}
	}	
	public void registerWritable(Writable wr ){
		if(!targets.contains(wr))
			targets.add(wr);
	}
	public boolean removeWritable( Writable wr ){
		return targets.remove(wr);
	}
  	/* ***************************************** C O N N E C T  ***************************************************** */
	/**
	 * Checks whether or not there's a connection to the broker
	 * @return True if connected
	 */
	public boolean isConnected() {
		if (client == null)
			return false;
		return client.isConnected();
		
	}

	@Override
	public void connectionLost(Throwable cause) {
		if (!mqttQueue.isEmpty()) {
			Logger.debug("Connection lost but still work to do, reconnecting");
			this.scheduler.schedule(new Connector(0), 0, TimeUnit.SECONDS);
		}
	}
	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		if( reconnect ){
			Logger.info("Reconnected to "+serverURI );
		}else{
			Logger.debug("First connection to "+serverURI);
		}		
		connecting=false;
		String subs="";
		try {
			for( String sub:subscriptions.keySet() ){
				subs=sub;
				client.subscribe( defTopic+sub );
			}
		} catch (MqttException e) {
			Logger.error("Failed to subscribe to: "+defTopic+subs);
		}
		if( !mqttQueue.isEmpty() )
			scheduler.schedule( new Publisher(),0,TimeUnit.SECONDS);
	}
	/**
	 * Small class that handles connection to the broker so it's not blocking 
	 */
	private class Connector implements Runnable {
		int attempt;

		public Connector(int attempt) {
			this.attempt = attempt;
		}

		@Override
		public void run() {
			if (!client.isConnected()) {
				try {					
					client.connect(connOpts);
					Logger.debug("Connected to broker: " + broker + " ...");
				} catch (MqttException me) {					
					attempt++;
					Logger.warn("Failed to connect to "+broker+", trying again later");
					scheduler.schedule( new Connector(attempt), Math.max(attempt * 5, 60), TimeUnit.SECONDS );
				}
			}
		}
	}
	/* ***************************************** P U B L I S H  ******************************************************/
	private class Publisher implements Runnable {
		@Override
		public void run() {
			boolean goOn=true;
			while (goOn) {
				MqttWork work=null;
				try {
					work = mqttQueue.poll(30, TimeUnit.SECONDS);
					if( work == null ){
						goOn=false;
						continue;
					}
					if( flavour==MQTT_FLAVOUR.UBIDOTS){
						client.publish(defTopic + work.getDevice(), work.getUbidotsMessage(1));
					}else if( flavour==MQTT_FLAVOUR.MOSQUITO){
						Logger.warn("Still to implement");
					}
				} catch (InterruptedException e) {
					Logger.error(e);
					// Restore interrupted state...
    				Thread.currentThread().interrupt();
				} catch (MqttException e) {
					Logger.error(e.getMessage());
					goOn=false;
					mqttQueue.add(work);
				} 
			}
			publishing=false;
		}
	}
	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		Logger.warn("This shouldn't be called...");
	}
}
