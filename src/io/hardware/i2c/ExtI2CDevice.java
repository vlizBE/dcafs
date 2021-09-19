package io.hardware.i2c;

import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Extension for the I2CDevice class that adds das relevant functionality
 */
public class ExtI2CDevice extends I2CDevice {
	
	private final String label;
	private final String script;
	private Instant timestamp;
	private final ArrayList<Writable> targets = new ArrayList<>();
	private boolean failedProbe=false;
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
	public boolean probeIt() throws RuntimeIOException{
		failedProbe = !this.probe();
		if( failedProbe )
			throw new RuntimeIOException( "Probe failed for "+getAddr() );
		return true;
	}
	public String toString(){
		return "@"+getController()+":0x"+String.format("%02x ", getAddress())
				+" using script "+script
				+(label.isEmpty()?"":" with label "+label);
	}
	public String getStatus(String id){
		String age = getAge()==-1?"Not used yet": TimeTools.convertPeriodtoString(getAge(), TimeUnit.SECONDS);
		return (failedProbe?"!!":"")+"I2C ["+id+"|"+label+"] "+getAddr()+"\t"+age+" [-1]";
	}
	public String getAddr(){
		return "0x"+String.format("%02x", getAddress())+"@"+getController();
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
	public void updateTimestamp(){
		timestamp = Instant.now();
	}
	public long getAge(){
		if( timestamp==null)
			return -1;
		return Duration.between(Instant.now(),timestamp).getSeconds();
	}
}