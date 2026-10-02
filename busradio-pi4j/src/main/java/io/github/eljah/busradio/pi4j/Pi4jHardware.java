package io.github.eljah.busradio.pi4j;
import io.github.eljah.busradio.core.Hardware;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.*;
/** GPIO only for slow I/O. GPIO4/21 are reserved for the separately managed RF clock backend. */
public final class Pi4jHardware implements Hardware {
 private Context context;private DigitalInput ignition;private DigitalOutput led;
 public void open(int ignitionBcm,int ledBcm){
  if(ignitionBcm<0||ignitionBcm>27||ledBcm<0||ledBcm>27||ignitionBcm==ledBcm||ignitionBcm==4||ignitionBcm==21||ledBcm==4||ledBcm==21)throw new IllegalArgumentException("Invalid or RF-reserved GPIO assignment");
  context=Pi4J.newAutoContext();
  try{
   ignition=context.create(DigitalInput.newConfigBuilder(context).id("ignition").name("Isolated ignition input").address(ignitionBcm).provider("gpiod-digital-input").pull(PullResistance.PULL_DOWN).debounce(50000L).build());
   led=context.create(DigitalOutput.newConfigBuilder(context).id("status-led").name("Agent running").address(ledBcm).provider("gpiod-digital-output").initial(DigitalState.LOW).shutdown(DigitalState.LOW).build());
  }catch(RuntimeException e){context.shutdown();throw e;}
 }
 public boolean ignitionOn(){return ignition!=null&&ignition.isHigh();}
 public void playing(boolean value){if(led!=null)led.state(value?DigitalState.HIGH:DigitalState.LOW);}
 public void close(){if(context!=null)context.shutdown();}
}
