package org.eljah.busradio;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.*;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProvider;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProvider;
import org.eljah.busradio.Support.Config;

/** BCM numbering. GPIO4 is deliberately untouched: it belongs to PiFmRds. */
public final class PiBoardIo implements BoardIo {
    private final Context context;private final DigitalInput ignition,mute;private final DigitalOutput led;
    public PiBoardIo(Config c){
        int ignitionPin=c.integer("gpio.ignition",17),mutePin=c.integer("gpio.mute",22),ledPin=c.integer("gpio.led",27);
        if(ignitionPin==4||mutePin==4||ledPin==4||ignitionPin==mutePin||ignitionPin==ledPin||mutePin==ledPin)throw new IllegalArgumentException("GPIO conflict / GPIO4 reserved for RF");
        context=Pi4J.newContextBuilder().noAutoDetect().add(GpioDDigitalInputProvider.newInstance(),GpioDDigitalOutputProvider.newInstance()).build();
        ignition=context.create(DigitalInput.newConfigBuilder(context).id("ignition").address(ignitionPin).pull(PullResistance.PULL_DOWN).debounce(20000L).provider("gpiod-digital-input").build());
        mute=context.create(DigitalInput.newConfigBuilder(context).id("mute").address(mutePin).pull(PullResistance.PULL_DOWN).debounce(20000L).provider("gpiod-digital-input").build());
        led=context.create(DigitalOutput.newConfigBuilder(context).id("playing").address(ledPin).initial(DigitalState.LOW).shutdown(DigitalState.LOW).provider("gpiod-digital-output").build());
    }
    public boolean ignitionOn(){return ignition.isHigh();}
    public boolean muted(){return mute.isHigh();}
    public void playing(boolean value){led.state(value?DigitalState.HIGH:DigitalState.LOW);}
    public void close(){context.shutdown();}
}
