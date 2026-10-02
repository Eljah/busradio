package org.eljah.busradio;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eljah.busradio.Model.Fix;
import org.eljah.busradio.Support.Config;

public interface Position extends AutoCloseable {
    Fix current();
    default void close(){}
    static Position create(Config c){
        return switch(c.get("position.mode","gpsd")){
            case "gpsd"->new Gpsd(c.get("gpsd.host","127.0.0.1"),c.integer("gpsd.port",2947));
            case "file"->()->{try{return Json.read(Files.readString(c.path("position.file","var/fix.json")),Fix.class);}catch(Exception e){return null;}};
            case "none"->()->null;
            default->throw new IllegalArgumentException("Unknown position.mode");
        };
    }
    final class Gpsd implements Position {
        private final AtomicReference<Fix> latest=new AtomicReference<>();private volatile boolean running=true;private volatile Socket socket;private final Thread worker;
        public Gpsd(String host,int port){worker=Thread.ofVirtual().name("gpsd").start(()->{
            while(running){
                try(Socket s=new Socket()){
                    socket=s;s.connect(new InetSocketAddress(host,port),3000);s.setSoTimeout(5000);
                    s.getOutputStream().write("?WATCH={\"enable\":true,\"json\":true};\n".getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
                    BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
                    while(running){String line=readLine(reader);if(line==null)break;try{var m=Json.object(Json.parse(line));if(!"TPV".equals(m.get("class")))continue;
                        if(!m.containsKey("mode")||Json.number(m,"mode")<2){latest.set(null);continue;}
                        double error=m.containsKey("epx")&&m.containsKey("epy")?Math.hypot(Json.number(m,"epx"),Json.number(m,"epy")):1000;
                        latest.set(new Fix(Json.number(m,"lat"),Json.number(m,"lon"),m.containsKey("speed")?Json.number(m,"speed"):0,m.containsKey("track")?Json.number(m,"track"):-1,error,Instant.parse(Json.string(m,"time"))));
                    }catch(RuntimeException badFix){latest.set(null);}}
                }catch(IOException failure){/* A stale fix is rejected by the ad selector even between reconnects. */}
                finally{socket=null;}
                try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        });}
        private static String readLine(Reader r)throws IOException{StringBuilder b=new StringBuilder();for(int c;(c=r.read())>=0;){if(c=='\n')return b.toString();if(b.length()>=65536)throw new IOException("GPSD line too long");b.append((char)c);}return b.isEmpty()?null:b.toString();}
        public Fix current(){return latest.get();}
        public void close(){running=false;worker.interrupt();try{if(socket!=null)socket.close();}catch(IOException ignored){}}
    }
}
