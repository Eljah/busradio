package io.github.eljah.busradio.core;
/** Service provider loaded only when GPIO is explicitly enabled. Inputs MUST be isolated 3.3 V signals. */
public interface Hardware extends AutoCloseable {
 void open(int ignitionBcm,int ledBcm);
 boolean ignitionOn();
 void playing(boolean value);
 void close();
}
