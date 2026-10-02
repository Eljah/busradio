package org.eljah.busradio;

import org.eljah.busradio.Support.Config;

/** Pi4J is loaded only on the Pi profile; server/demo builds do not touch GPIO. */
public interface BoardIo extends AutoCloseable {
    boolean ignitionOn();
    boolean muted();
    void playing(boolean value);
    default void close(){}
    static BoardIo create(Config c)throws Exception{
        if(c.get("io.mode","none").equals("pi4j"))return (BoardIo)Class.forName("org.eljah.busradio.PiBoardIo").getConstructor(Config.class).newInstance(c);
        if(!c.get("io.mode","none").equals("none"))throw new IllegalArgumentException("Unknown io.mode");
        return new BoardIo(){public boolean ignitionOn(){return c.bool("simulation.ignition",true);}public boolean muted(){return false;}public void playing(boolean value){}};
    }
}
