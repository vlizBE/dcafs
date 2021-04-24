package com.hardware.i2c;

import com.diozero.api.I2CDevice;
import com.stream.Writable;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Extension for the I2CDevice class that adds das relevant functionality
 */
public class ExtI2CDevice extends I2CDevice {
	
	private final String label;
	private final String script;

	private final ArrayList<Writable> targets = new ArrayList<>();
	/**
	 * Extension of the @see I2CDevice class that adds the command functionality
	 * 
	 * @param controller The controller on which this device is connected
	 * @param address The address of the device
	 */
	public ExtI2CDevice (int controller, int address, String script, String label){
		super(controller,address);
		this.label = label;
		this.script=script;
		Logger.info("Connecting to controller:"+controller +" and address:"+address+" with label: "+label);
	}
	public String toString(){
		return "@"+getController()+":0x"+String.format("%02x ", getAddress())
				+" using script "+script
				+(label.isEmpty()?"":" with label "+label);
	}
	public String getLabel(){
		return label;
	}	
	public String getScript(){
		return script;
	}

	/**
	 * Add a @Writable to which data received from this device is send
	 * @param wr Where the data will be send to
	 */
	public void addTarget(Writable wr){
		if( !targets.contains(wr))
			targets.add(wr);
	}
	public boolean removeTarget(Writable wr ){
		return targets.remove(wr);
	}
	/**
	 * Get the list containing the writables
	 * @return The list of writables
	 */
	public List<Writable> getTargets(){
		return targets;
	}
	public String getWritableIDs(){
		StringJoiner join = new StringJoiner(", ");
		join.setEmptyValue("None yet.");
		targets.forEach(wr -> join.add(wr.getID()));
		return join.toString();
	}
}