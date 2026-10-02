package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
public final class EdgeMain {
 public static void main(String[] args)throws Exception{
  Map<String,String> options=new HashMap<>();for(int i=0;i<args.length;i++){String k=args[i];if(k.equals("--once"))options.put(k,"true");else if(k.startsWith("--")&&i+1<args.length)options.put(k,args[++i]);else throw new IllegalArgumentException("Usage: --config path [--once] [--seconds N]");}
  Config c=Config.load(Path.of(options.getOrDefault("--config","edge.properties")));Files.createDirectories(c.cache());
  try(FileChannel channel=FileChannel.open(c.cache().resolve("edge.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
   if(lock==null)throw new IllegalStateException("This device cache is already in use");
   Clock clock=Clock.systemUTC();Outbox outbox=new Outbox(c.cache());NetworkPolicy policy=new NetworkPolicy(c,clock);
   try(SyncClient sync=new SyncClient(c,policy,outbox)){
    if(options.containsKey("--once")){if(!policy.getAsBoolean()){System.out.println("Sync skipped: not on approved Wi-Fi or outside night window");return;}sync.syncOnce();return;}
    if(policy.getAsBoolean())try{sync.syncOnce();}catch(Exception e){System.err.println("Initial sync failed; keeping cached release: "+e.getMessage());}
    Hardware hardware=c.bool("hardware.enabled",false)?ServiceLoader.load(Hardware.class).findFirst().orElseThrow(()->new IllegalStateException("Pi4J provider missing; use the Maven all.jar")):null;
    if(hardware!=null)hardware.open(c.integer("hardware.ignitionBcm",17),c.integer("hardware.ledBcm",27));
    ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();scheduler.scheduleWithFixedDelay(()->{try{if(policy.getAsBoolean())sync.syncOnce();}catch(Exception e){System.err.println("Sync deferred: "+e.getMessage());}},30,60,TimeUnit.SECONDS);
    try(Positions gps=new Positions(c,clock);Player player=new Player(c,sync,outbox,new History(c.cache()),gps,()->hardware==null||hardware.ignitionOn(),clock)){
     Thread shutdown=new Thread(player::close);Runtime.getRuntime().addShutdownHook(shutdown);
     try{if(hardware!=null)hardware.playing(true);player.run(Long.parseLong(options.getOrDefault("--seconds","0")));}finally{try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored){}if(hardware!=null)hardware.playing(false);}
    }finally{scheduler.shutdownNow();scheduler.awaitTermination(10,TimeUnit.SECONDS);if(hardware!=null)hardware.close();}
   }
  }
 }
}
