package org.eljah.busradio;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.eljah.busradio.Support.Config;

public final class Node implements AutoCloseable {
    private final Config config;private final Api api;private final Position position;private final BoardIo io;private final Player player;private final Thread playback,sync;
    public final Cache cache;public final Outbox outbox;
    private final Support.InstanceLock instanceLock;
    private volatile boolean running=true;private volatile String lastSync="never";
    public Node(Config config)throws Exception{this(config,Position.create(config),BoardIo.create(config));}
    public Node(Config config,Position position,BoardIo io)throws Exception{
        this.config=config;this.position=position;this.io=io;
        String id=Model.id(config.required("node.id"));api=new Api(config.required("server.url"),config.required("node.token"),id,config.bool("transport.allowHttp",false));
        Path root=config.path("node.data","var/node");instanceLock=new Support.InstanceLock(root.resolve("node.lock"));cache=new Cache(root,api,id,config.integer("cache.maxMiB",4096)*1048576L);outbox=new Outbox(root.resolve("outbox"));player=new Player(config,cache,position,io,outbox);
        playback=Thread.ofVirtual().name("playback").start(player);sync=Thread.ofVirtual().name("night-sync").start(()->{
            while(running){
                try{
                    if(SyncGate.allowed(config,io,Instant.now())){
                        cache.sync();lastSync=Instant.now().toString();outbox.flush(api);api.json("POST","/api/device/heartbeat",status());
                    }
                    Support.atomic(root.resolve("status.json"),Json.write(status()));
                }catch(Exception e){if(running)System.err.println("Sync deferred; cached playback retained: "+e.getMessage());}
                try{int seconds=Math.max(1,config.integer("sync.pollSeconds",30)),jitter=Math.max(0,config.integer("sync.jitterSeconds",10));Thread.sleep(1000L*(seconds+(jitter==0?0:ThreadLocalRandom.current().nextInt(jitter+1))));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        });
    }
    public Map<String,Object> status(){Map<String,Object> m=new LinkedHashMap<>();m.put("time",Instant.now());m.put("lastSync",lastSync);m.put("revision",cache.active()==null?-1:cache.active().revision());m.put("playback",player.status());m.put("gps",position.current());m.put("ignition",io.ignitionOn());m.put("muted",io.muted());m.put("audioMode",config.get("audio.mode","wav"));return m;}
    public void close(){running=false;sync.interrupt();player.close();playback.interrupt();position.close();api.close();try{playback.join(5000);sync.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}io.close();try{instanceLock.close();}catch(IOException e){System.err.println("Node lock close: "+e.getMessage());}}
}
