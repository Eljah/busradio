package org.eljah.busradio;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.eljah.busradio.Model.*;
import org.eljah.busradio.Support.Config;

public final class Player implements Runnable,AutoCloseable {
    private final Cache cache;private final Position position;private final BoardIo io;private final Outbox outbox;private final Geo.Ledger ledger;private final AudioOutput output;private final String busId;private final double speed;
    private volatile boolean running=true;private volatile String status="STARTING";
    private final Path checkpoint;private int index=0,sinceAds=0;private String playlistHash="";private int directionHint=-1;private long nextDirectionCheck=0;
    public Player(Config c,Cache cache,Position position,BoardIo io,Outbox outbox)throws IOException{
        this.cache=cache;this.position=position;this.io=io;this.outbox=outbox;busId=c.required("node.id");speed=Double.parseDouble(c.get("playback.speed","1"));if(!Double.isFinite(speed)||speed<0.1||speed>1000)throw new IllegalArgumentException("Invalid playback speed");
        ledger=new Geo.Ledger(cache.root.resolve("ad-ledger.json"),ZoneId.of(c.get("timezone","Europe/Moscow")));output=new AudioOutput(c,cache.root);checkpoint=cache.root.resolve("player-state.json");
        if(Files.exists(checkpoint)){var m=Json.object(Json.parse(Files.readString(checkpoint)));playlistHash=Json.string(m,"playlistHash");index=(int)Json.number(m,"index");sinceAds=(int)Json.number(m,"sinceAds");}
    }
    public String status(){return status;}
    private void save()throws IOException{Support.atomic(checkpoint,Json.write(Map.of("playlistHash",playlistHash,"index",index,"sinceAds",sinceAds)));}
    private boolean permitted(){return running&&io.ignitionOn()&&!io.muted();}
    private void event(String asset,String campaign,String kind,String detail)throws IOException{outbox.add(new Event(UUID.randomUUID().toString(),busId,Instant.now(),asset,campaign,kind,detail));}
    public void run(){
        while(running&&!Thread.currentThread().isInterrupted()){
            try{
                Manifest m=cache.active();if(m!=null&&directionHint<0)directionHint=m.direction();
                if(m==null||!permitted()){status=m==null?"WAITING_FOR_SYNC":"MUTED_OR_PARKED";io.playing(false);output.close();Thread.sleep(250);continue;}
                String hash=Support.hash(Json.write(m.playlist()).getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!hash.equals(playlistHash)){playlistHash=hash;index=0;sinceAds=0;save();}
                if(sinceAds>=m.playlist().adEveryTracks()){sinceAds=0;save();advertisingWindow(m);continue;}
                Asset a=m.asset(m.playlist().tracks().get(Math.floorMod(index,m.playlist().tracks().size())));play(a,"", "playlist");index=(index+1)%m.playlist().tracks().size();sinceAds++;save();
            }catch(InterruptedException stop){Thread.currentThread().interrupt();break;}
            catch(Exception failure){status="ERROR: "+failure.getClass().getSimpleName();System.err.println("Playback: "+failure.getMessage());io.playing(false);try{output.close();Thread.sleep(1000);}catch(Exception ignored){}}
        }
        io.playing(false);try{output.close();}catch(IOException ignored){}
    }
    private void advertisingWindow(Manifest m)throws Exception{
        long budget=m.playlist().adWindowSeconds()*1000L;Set<String> used=new HashSet<>();
        long deadline=System.nanoTime()+(long)(budget*1_000_000/speed);
        for(int n=0;n<m.playlist().maxAds()&&permitted();n++){
            long remaining=Math.min(budget,Math.max(0,(long)((deadline-System.nanoTime())/1_000_000.0*speed)));
            Instant now=Instant.now();var choice=Geo.choose(m,position.current(),now,remaining,used,ledger,directionHint);
            if(choice.isEmpty()){if(n==0)event("","","SKIPPED","No eligible nearby ad / GPS stale / route mismatch / caps / duration");return;}
            Campaign c=choice.get().campaign();Asset a=m.asset(c.assetId());ledger.reserve(c,now);used.add(c.id());
            play(a,c.id(),String.format(Locale.ROOT,"distance=%.1fm ahead=%.1fm direction=%d geometry=%s",choice.get().distanceMeters(),choice.get().aheadMeters(),choice.get().direction(),m.route().geometry()));budget-=a.durationMs();
        }
    }
    private void play(Asset a,String campaign,String reason)throws Exception{
        Path file=cache.file(a);if(!Support.hash(file).equals(a.sha256()))throw new IOException("Cached audio corrupted: "+a.id());Wave.Info info=Wave.inspect(file);
        event(a.id(),campaign,"STARTED",reason);status=(campaign.isEmpty()?"MUSIC ":"AD ")+a.name();io.playing(true);
        try(InputStream in=Files.newInputStream(file)){
            in.skipNBytes(info.offset());long remaining=info.dataBytes(),done=0,start=System.nanoTime();byte[] b=new byte[4096];
            while(remaining>0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(!permitted())throw new IOException("Playback inhibited by ignition/mute");
                if(System.nanoTime()>nextDirectionCheck){Fix f=position.current();Manifest m=cache.active();if(f!=null&&m!=null&&!f.time().isBefore(Instant.now().minusSeconds(30))){Route route=Geo.resolveRoute(m,f,directionHint);if(route!=null)directionHint=route.direction();}nextDirectionCheck=System.nanoTime()+1_000_000_000;}
                int n=in.readNBytes(b,0,(int)Math.min(b.length,remaining));if(n<=0||n%4!=0)throw new EOFException("Audio truncated");
                // 10 ms fades avoid hard discontinuities without changing the window duration.
                for(int j=0;j<n;j+=4){double f=Math.min(1,Math.min((done+j)/(441.0*4),(info.dataBytes()-done-j)/(441.0*4)));if(f<1)for(int ch=0;ch<4;ch+=2){short s=(short)((b[j+ch]&255)|(b[j+ch+1]<<8));short v=(short)(s*f);b[j+ch]=(byte)v;b[j+ch+1]=(byte)(v>>8);}}
                output.write(b,n);done+=n;remaining-=n;long target=start+(long)(done*1_000_000_000.0/(Wave.RATE*4*speed));
                while(System.nanoTime()<target){LockSupport.parkNanos(Math.min(20_000_000,target-System.nanoTime()));if(Thread.interrupted())throw new InterruptedException();}
            }
            output.flush();event(a.id(),campaign,"COMPLETED","PCM delivered to configured audio backend; not an RF reception proof");
        }catch(Exception e){event(a.id(),campaign,"FAILED",Objects.toString(e.getMessage(),e.getClass().getSimpleName()));throw e;}
        finally{io.playing(false);}
    }
    public void close(){running=false;}
}
