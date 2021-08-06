package io.mqtt;

public interface MqttWriting {
    boolean sendToBroker( String id, String device, String param, double value);
}
