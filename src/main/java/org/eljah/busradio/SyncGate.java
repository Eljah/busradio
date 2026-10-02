package org.eljah.busradio;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eljah.busradio.Support.Config;

public final class SyncGate {
    private SyncGate(){}
    public static boolean allowed(Config c,BoardIo io,Instant now){
        ZoneId zone=ZoneId.of(c.get("timezone","Europe/Moscow"));
        if(!Support.inWindow(now.atZone(zone).toLocalTime(),LocalTime.parse(c.get("sync.start","01:00")),LocalTime.parse(c.get("sync.end","05:00"))))return false;
        if(c.bool("sync.requireParked",true)&&io.ignitionOn())return false;
        if(!c.bool("sync.requireWifi",true))return true;
        String iface=c.get("sync.interface","wlan0");if(!iface.matches("[A-Za-z0-9_.-]{1,32}"))throw new IllegalArgumentException("Invalid network interface");
        String ssids=c.get("sync.ssids","");if(ssids.isBlank())return false;
        if(!Files.isDirectory(Path.of("/sys/class/net",iface,"wireless")))return false;
        try{
            Process p=new ProcessBuilder("iwgetid",iface,"-r").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();return false;}if(p.exitValue()!=0)return false;
            String ssid=new String(Support.limited(p.getInputStream(),4096),StandardCharsets.UTF_8).strip();return Arrays.stream(ssids.split(",")).map(String::strip).anyMatch(ssid::equals);
        }catch(Exception e){return false;}
    }
}
