package io.hardware.gpio;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import org.tinylog.Logger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

public class InterruptPins implements DeviceEventConsumer<DigitalInputEvent> {

    private final ArrayList<InterruptCmd> pinCmds = new ArrayList<>();

    public static final int INTERRUPT_GPIO_NOT_SET = -1;
    private final BlockingQueue<Datagram> dQueue;
    private final Path settings;

    public InterruptPins(BlockingQueue<Datagram> dQueue, Path settings){
        this.dQueue=dQueue;
        this.settings=settings;
        readFromXml();
    }
    public void readFromXml(){
        var fab = XMLfab.withRoot(settings,"dcafs","gpio");
        fab.getChildren("interrupt").forEach( isr ->
        {
            String edge = XMLtools.getStringAttribute(isr,"edge","falling");
            int pin = XMLtools.getIntAttribute(isr,"pin",-1);
            Optional<InterruptCmd> ic = switch (edge) {
                case "falling" -> addFalling(pin);
                case "rising" -> addRising(pin);
                case "both" -> addBoth(pin);
                default -> Optional.empty();
            };
            ic.ifPresent(interruptCmd -> XMLtools.getChildElements(isr, "cmd").forEach(
                    cmd -> interruptCmd.addCmd(cmd.getTextContent())
            ));
        });
    }
    public Optional<InterruptCmd> addFalling(int pinNr ){
        return addPin(pinNr,GpioEventTrigger.FALLING);
    }
    public Optional<InterruptCmd> addRising(int pinNr ){
        return addPin(pinNr,GpioEventTrigger.RISING);
    }
    public Optional<InterruptCmd> addBoth(int pinNr ){
        return addPin(pinNr,GpioEventTrigger.BOTH);
    }
    public Optional<InterruptCmd> addPin(int pinNr, GpioEventTrigger event ){
        Logger.info( "Trying to add "+pinNr+" as interrupt");
        if (pinNr != INTERRUPT_GPIO_NOT_SET) {
            try {
                var device = new DigitalInputDevice(pinNr, GpioPullUpDown.NONE, event);
                device.addListener(this);
                Logger.info("Setting interruptGpio ({}) consumer", device.getGpio());
                var isr = new InterruptCmd(device);
                pinCmds.add(isr);
                return Optional.of(isr);
            }catch( RuntimeIOException e  ){
                Logger.error(e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
    @Override
    public void accept(DigitalInputEvent event) {
        Logger.info("accept({})", event);
        Logger.info( "Interrupt on pin:" +event.getGpio());

        // Check the event is for one of the interrupt gpio's
        pinCmds.stream().filter(x -> x.device.getGpio()==event.getGpio()).map(x->x.cmds).forEach(
                cmd -> cmd.forEach( x->dQueue.add(Datagram.system(x)))
        );
    }
    private static class InterruptCmd {
        DigitalInputDevice device;
        ArrayList<String> cmds;
        public InterruptCmd(DigitalInputDevice device){
            this.device=device;
        }
        public void addCmd( String cmd ){
            if( cmds==null)
                cmds=new ArrayList<>();
            cmds.add(cmd);
        }
    }
}
