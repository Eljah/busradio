package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Model.*;
import static io.github.eljah.busradio.core.Json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
public final class Positions implements Supplier<Fix>,AutoCloseable {
 private final Config c;private final Clock clock;private volatile Fix fix;private volatile boolean running=true;private volatile Socket socket;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"position-source");t.setDaemon(true);return t;});private final Instant demoStart;
 public Positions(Config c,Clock clock){this.c=c;this.clock=clock;demoStart=clock.instant();String mode=c.get("gps.mode","gpsd");if(mode.equals("gpsd"))worker.submit(this::gpsd);else if(mode.equals("buscrawl"))worker.submit(this::buscrawl);else if(!Set.of("demo","none").contains(mode))throw new IllegalArgumentException("Unknown GPS mode");if(mode.equals("demo")&&c.get("audio.mode","null").equals("fm"))throw new IllegalArgumentException("Synthetic positions cannot arm RF");}
 @Override public Fix get(){if(c.get("gps.mode","gpsd").equals("demo")){double sec=Duration.between(demoStart,clock.instant()).toMillis()/1000d;return new Fix(new Point(55.8,49.1+(sec%400)*0.00006),5,90,3,clock.instant(),"SYNTHETIC_DEMO");}return fix;}
 public static Fix parseGpsd(Object value){var m=obj(value);if(!str(m,"class","").equals("TPV")||num(m,"mode",0)<2)return null;if(!m.containsKey("time")||!m.containsKey("lat")||!m.containsKey("lon"))return null;double speed=num(m,"speed",0);if(speed>=2&&!m.containsKey("track"))return null;double accuracy=m.containsKey("eph")?num(m,"eph"):Math.max(num(m,"epx",1000),num(m,"epy",1000));return new Fix(new Point(num(m,"lat"),num(m,"lon")),speed,((num(m,"track",0)%360)+360)%360,accuracy,Instant.parse(str(m,"time")),"GPSD");}
 private void gpsd(){while(running){try(Socket s=new Socket()){socket=s;s.connect(new InetSocketAddress(c.get("gpsd.host","127.0.0.1"),c.integer("gpsd.port",2947)),3000);s.setSoTimeout(15000);s.getOutputStream().write("?WATCH={\"enable\":true,\"json\":true};\n".getBytes(StandardCharsets.UTF_8));try(var reader=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8))){String line;while(running&&(line=reader.readLine())!=null){if(line.length()>16384)throw new IOException("Oversized GPSD record");try{Object v=Json.parse(line);var m=obj(v);if(str(m,"class","").equals("TPV"))fix=parseGpsd(v);}catch(IllegalArgumentException e){fix=null;}}}}catch(Exception e){fix=null;if(running)System.err.println("GPSD reconnect: "+e.getMessage());}finally{socket=null;}pause();}}
 private void buscrawl(){Path file=Path.of(c.get("gps.buscrawlFile","var/vehicle.jsonl"));while(running){try(RandomAccessFile f=new RandomAccessFile(file.toFile(),"r")){long from=Math.max(0,f.length()-65536);f.seek(from);byte[] bytes=new byte[(int)(f.length()-from)];f.readFully(bytes);String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\n");Fix latest=null;for(int i=lines.length-1;i>=(from>0?1:0);i--){if(lines[i].isBlank())continue;try{latest=BuscrawlAdapter.movement(Json.parse(lines[i]),c.get("gps.vehicle",""));break;}catch(IllegalArgumentException ignored){}}fix=latest;}catch(IOException e){fix=null;}pause();}}
 private void pause(){try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();running=false;}}
 @Override public void close(){running=false;try{if(socket!=null)socket.close();}catch(IOException ignored){}worker.shutdownNow();}
}
