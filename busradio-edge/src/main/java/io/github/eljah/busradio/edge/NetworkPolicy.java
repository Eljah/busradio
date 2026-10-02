package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.Model;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
/** A conservative preflight, NOT a substitute for per-UID egress firewall enforcement. */
public final class NetworkPolicy implements BooleanSupplier {
 private final Config c;private final Clock clock;private long checkedAt;private boolean last;
 public NetworkPolicy(Config c,Clock clock){this.c=c;this.clock=clock;}
 public boolean inTimeWindow(){return Model.inWindow(LocalTime.now(clock.withZone(ZoneId.of(c.get("sync.zone","Europe/Moscow")))),LocalTime.parse(c.get("sync.start","01:00")),LocalTime.parse(c.get("sync.end","05:00")));}
 @Override public synchronized boolean getAsBoolean(){
  if(c.bool("sync.demoLoopback",false))return c.isLoopback();if(!inTimeWindow())return false;
  long now=System.nanoTime();if(now-checkedAt<2_000_000_000L)return last;checkedAt=now;last=false;
  try{
   String iface=c.get("wifi.interface","wlan0");if(!iface.matches("[A-Za-z0-9_.-]{1,20}"))return false;
   Set<String> allowed=new HashSet<>(Arrays.asList(c.get("wifi.ssids","").split("\\|",-1)));allowed.remove("");if(allowed.isEmpty())return false;
   String wifi=command("nmcli","-t","--escape","no","-f","ACTIVE,SSID","device","wifi","list","ifname",iface,"--rescan","no");boolean connected=wifi.lines().anyMatch(s->s.startsWith("yes:")&&allowed.contains(s.substring(4)));if(!connected)return false;
   for(InetAddress address:InetAddress.getAllByName(c.server().getHost())){String route=address instanceof Inet6Address?command("ip","-6","route","get",address.getHostAddress()):command("ip","route","get",address.getHostAddress());if(!(" "+route.replace('\n',' ')+" ").contains(" dev "+iface+" "))return false;}
   last=true;
  }catch(Exception e){System.err.println("Wi-Fi sync denied: "+e.getMessage());}return last;
 }
 private static String command(String... args)throws Exception{Process p=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();try{if(!p.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Network probe timed out");if(p.exitValue()!=0)throw new IllegalStateException("Network probe failed");return new String(p.getInputStream().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);}finally{if(p.isAlive())p.destroyForcibly();}}
}
