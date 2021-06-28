package io.mqtt;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.tinylog.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class MqttWork {

	Map<String, Variable> datapoints = new HashMap<>();

	String device;
	int qos=0;
	int attempt = 0;
	long lastTimestamp=-1;
	String lastKey="";

	public MqttWork( String device ) {
		this.device=device;
	}
	/**
	 * Constructor that also adds a value 
	 * @param device The device this data is comming from
	 * @param parameter The parameter to update
	 * @param value The new value
	 */
	public MqttWork( String device, String parameter, Object value) {
		this.device=device;
		add(parameter,value);
	}
	public MqttWork( String topic, Object value) {
		int index = topic.indexOf("/");
		if( index == -1 ){
			Logger.error( "No topic given in mqttwork: "+topic);
		}else{
			this.device = topic.substring(0,index);
			add( topic.substring(index+1),value);
		}
		
	}
	/*  SETTINGS */
	/**
	 * Change te QoS for this message
	 * @param qos The new QoS value to use
	 */
	public void alterQos( int qos ) {
		this.qos=qos;
	}
	/**
	 * Get the device name given 
	 * @return The name of the device this data relates to
	 */
	public String getDevice() {
		return device;
	}
	/* ********************************* ADDING DATA ******************************************************** */
	/**
	 * Add a datapoint to this work but use a given timestamp instead of the curent time
	 * @param key The id of this variable
	 * @param value The value of this variable
	 * @param timestamp The timestamp of the value
	 * @return This object
	 */
	public MqttWork addOldPoint( String key, Object value, long timestamp) {
		datapoints.put(key, new Variable(value, timestamp));
		return this;
	}
	/**
	 * Add a pair to the work with current time as timestamp
	 * @param key Name of the variable/parameter
	 * @param value Value of the variable/parameter
	 * @return This object
	 */
	public MqttWork add( String key, Object value ) {
		lastTimestamp= Instant.now().toEpochMilli();
		lastKey=key;
		datapoints.put(key, new Variable(value, lastTimestamp));
		return this;
	}
	/**
	 * Add a pair to the work with the same timestamp as the previously added
	 * @param key Name of the variable/parameter
	 * @param value Value of the variable/parameter
	 * @return This object
	 */
	public MqttWork and( String key, Object value ) {
		lastKey=key;
		datapoints.put(key, new Variable(value, lastTimestamp));
		return this;
	}
	public MqttWork context( String key, Object value){
		Variable v = datapoints.get(lastKey);
		if( v != null ){
			v.context(key, value);
		}else{
			Logger.error("Trying to add context to unknown key");
		}
		return this;
	}
	public MqttWork qos(int qos){
		this.qos=qos;
		return this;
	}
	/* ******************************************************************************************************** */
	public void incrementAttempt() {
		attempt++;
	}
	public boolean isNotEmpty() {
		return !datapoints.isEmpty();
	}
	public boolean isEmpty() {
		return datapoints.isEmpty();
	}
	/* ******************************************************************************************************** */
	/**
	 * Get the message to send to the broker if the broker is Ubidots
	 * @param qos The QoS setting for this message
	 * @return The message in Ubidots format
	 */
	public MqttMessage getUbidotsMessage( int qos ) {
		StringBuilder content=new StringBuilder();
		content.append("{");
		for( Entry<String,Variable> dp : datapoints.entrySet() ) {
			Variable v = dp.getValue();

			Long timestamp = v.timestamp;
			
			if( content.length()!=1)
				content.append(",");
			
			content.append('"').append(dp.getKey()).append('"').append(":");
			
			if( timestamp == -1 ) {
				content.append( v.value );
			}else{
				content.append("{\"value\":").append( v.value );
				content.append(",\"timestamp\":").append(timestamp);
				if( v.hasContext() ){
					content.append(",\"context\":{");
					boolean first =false;
					for( Pair<String,Object> p : v.context ){
						if( first ){
							content.append(',');
						}
						content.append('"').append(p.getLeft()).append("\":");
						if( p.getRight().getClass() == String.class){
							content.append('"').append(p.getRight()).append('"');
						}else{
							content.append(p.getRight());
						}
						first=true;
					}
					content.append("}");
				}
				content.append("}");
			}
		}
		content.append("}");
		MqttMessage message = new MqttMessage(content.toString().getBytes());
		message.setQos(qos);
		return message;
	}

	public static class Variable{
		Object value;
		long timestamp;
		ArrayList<Pair<String,Object>> context = new ArrayList<>();

		public Variable( Object value, long timestamp){
			this.value=value;
			this.timestamp=timestamp;
		}
		public Variable context( String key, Object value){
			context.add( Pair.of(key,value));
			return this;
		}
		public boolean hasContext(){
			return !context.isEmpty();
		}
	}
}
