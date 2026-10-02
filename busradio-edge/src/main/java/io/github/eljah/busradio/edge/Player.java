package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

public final class Player implements AutoCloseable {
 private final Config c;private final SyncClient sync;private final Outbox outbox;private final History history;private final Supplier<Fix> gps;private final BooleanSupplier enabled;private final Clock clock;
 private final AdSelector selector=new AdSelector();private volatile boolean running=true;private AudioSinks.Sink sink;private long pacingDeadline,stopAt;private final DirectionResolver direction=new DirectionResolver();
 public Player(Config c,SyncClient sync,Outbox outbox,History history,Supplier<Fix> gps,BooleanSupplier enabled,Clock clock){this.c=c;this.sync=sync;this.outbox=outbox;this.history=history;this.gps=gps;this.enabled=enabled;this.clock=clock;}
 public void run(long maxSeconds)throws Exception{
  stopAt=maxSeconds>0?System.nanoTime()+maxSeconds*1_000_000_000L:Long.MAX_VALUE;String revision="";int index=0,processed=0;int maxSlots=c.integer("player.maxSlots",0);
  try{while(alive()&&(maxSlots<=0||processed<maxSlots)){
   Manifest m=sync.active();if(m==null||!enabled.getAsBoolean()){closeSink();Thread.sleep(250);continue;}
   if(sink==null){sink=AudioSinks.open(c);pacingDeadline=System.nanoTime();}
   if(!revision.equals(m.revision())){revision=m.revision();index=0;}
   Slot slot=m.playlist().slots().get(index);index=(index+1)%m.playlist().slots().size();processed++;
   if(slot.type().equals("MUSIC")){Asset a=m.assetMap().get(slot.assetId());emit(m,a,a.frames(),"MUSIC",null);}
   else window(m,slot);
  }}finally{closeSink();}
 }
 private void window(Manifest m,Slot slot)throws Exception{
  long remaining=(long)slot.seconds()*SAMPLE_RATE;Set<String> used=new HashSet<>();var assets=m.assetMap();
  while(remaining>0&&alive()&&enabled.getAsBoolean()){
   Instant now=clock.instant();Fix fix=gps.get();var selectedRoute=direction.resolve(m,fix,now);Manifest effective=selectedRoute.map(m::onRoute).orElse(m);var candidate=used.size()<slot.maxAds()&&selectedRoute.isPresent()?selector.choose(effective,fix,now,remaining,a->!used.contains(a.id())&&history.allowed(a,now)):Optional.<AdSelector.Candidate>empty();
   if(candidate.isPresent()){
    Campaign ad=candidate.get().campaign();Asset a=assets.get(ad.assetId());history.reserve(ad,now);used.add(ad.id());
    System.out.println(Json.write(Map.of("decision","AD_SELECTED","campaignId",ad.id(),"aheadM",candidate.get().aheadM(),"etaSeconds",candidate.get().etaSeconds())));
    long sent=emit(effective,a,a.frames(),"AD",ad.id());remaining-=sent;if(sent<a.frames())break;
   }else{
    Asset filler=assets.get(slot.fallbackAssetId());long n=filler==null?remaining:Math.min(remaining,filler.frames());long sent=emit(m,filler,n,"FILLER",null);remaining-=sent;if(sent<n)break;
   }
  }
 }
 private boolean alive(){return running&&!Thread.currentThread().isInterrupted()&&System.nanoTime()<stopAt;}
 private long emit(Manifest m,Asset asset,long frames,String kind,String campaign)throws Exception{
  String playback=UUID.randomUUID().toString();Fix startFix=gps.get();Instant started=clock.instant();String id=asset==null?"silence":asset.id();
  record(m,playback,id,kind,campaign,"STARTED",false,0,startFix,started);
  long sent=0;Exception failure=null;
  try(InputStream in=asset==null?InputStream.nullInputStream():new BufferedInputStream(Files.newInputStream(sync.media(asset)))){
   byte[] buffer=new byte[882]; // exactly 20 ms at 22.05 kHz, PCM16 mono
   while(sent<frames&&alive()&&enabled.getAsBoolean()){
    int count=(int)Math.min(buffer.length,(frames-sent)*2);Arrays.fill(buffer,(byte)0);if(asset!=null){int n=in.readNBytes(buffer,0,count);if(n!=count)throw new EOFException("Cached PCM truncated");}
    // Five-millisecond ramps at segment edges, including a clipped filler, avoid hard PCM discontinuities.
    ByteBuffer samples=ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<count/2;i++){long frame=sent+i;double gain=Math.min(1,Math.min(frame,frames-1-frame)/110d);short v=samples.getShort(i*2);samples.putShort(i*2,(short)(v*Math.max(0,gain)));}
    sink.write(buffer,count);sent+=count/2;
    if(c.bool("audio.realtime",true)){pacingDeadline+=(count/2)*1_000_000_000L/SAMPLE_RATE;long wait=pacingDeadline-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);else if(wait < -500_000_000L)pacingDeadline=System.nanoTime();}
   }
  }catch(Exception e){failure=e;}finally{record(m,playback,id,kind,campaign,sent==frames&&failure==null?"COMPLETED":"ABORTED",sent==frames&&failure==null,sent,startFix,started);}
  if(failure!=null)throw failure;return sent;
 }
 private void record(Manifest m,String playback,String asset,String kind,String campaign,String status,boolean completed,long frames,Fix fix,Instant started)throws IOException{
  Map<String,Object> event=new LinkedHashMap<>();event.put("id",UUID.randomUUID().toString());event.put("playbackId",playback);event.put("busId",c.bus());event.put("revision",m.revision());event.put("routeId",m.route().id());event.put("at",clock.instant().toString());event.put("startedAt",started.toString());event.put("assetId",asset);event.put("kind",kind);event.put("campaignId",campaign);event.put("status",status);event.put("completed",completed);event.put("frames",frames);event.put("fix",fix);event.put("output",c.get("audio.mode","null"));event.put("simulation",!c.get("audio.mode","null").equals("fm"));outbox.add(event);System.out.println(Json.write(event));
 }
 private void closeSink()throws IOException{if(sink!=null){try{sink.close();}finally{sink=null;}}}
 @Override public void close(){running=false;}
}
