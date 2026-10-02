package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Json.*;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class SyncClient implements AutoCloseable {
 private final Config c;private final BooleanSupplier allowed;private final HttpClient http;private final Outbox outbox;
 private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"download-watchdog");t.setDaemon(true);return t;});
 private volatile Manifest active;private String minimumRevision="";
 public SyncClient(Config c,BooleanSupplier allowed,Outbox outbox)throws Exception{this.c=c;this.allowed=allowed;this.outbox=outbox;http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();Files.createDirectories(c.cache().resolve("media"));Path pointer=c.cache().resolve("active.json");if(Files.exists(pointer)){Manifest m=SignedManifest.verify(FilesEx.readObject(pointer),c.get("server.publicKey",""));checkBus(m);minimumRevision=m.revision();try{verifyAssets(m);active=m;}catch(IOException e){System.err.println("Cache requires repair: "+e.getMessage());}}}
 public Manifest active(){return active;}public Path media(Asset a){return c.cache().resolve("media").resolve(a.sha256()+".pcm");}
 private void guard()throws IOException{if(!allowed.getAsBoolean())throw new IOException("Outside approved Wi-Fi/time window");}
 private HttpRequest.Builder request(String suffix)throws IOException{guard();return HttpRequest.newBuilder(c.server().resolve("/api/bus/"+c.bus()+suffix)).timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+c.get("bus.token",""));}
 private Object json(String suffix,Object body)throws Exception{var b=request(suffix);if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));else b.GET();var r=http.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());try(InputStream in=r.body()){if(r.statusCode()!=200)throw new IOException("Device API HTTP "+r.statusCode());ScheduledFuture<?> timeout=watchdog.schedule(()->{try{in.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);try{return Json.parse(new String(FilesEx.limited(in,6_000_000),StandardCharsets.UTF_8));}finally{timeout.cancel(false);}}}
 private void checkBus(Manifest m){if(!m.busId().equals(c.bus())||!m.revision().matches("r-[0-9]{12}"))throw new IllegalArgumentException("Manifest bus or revision mismatch");}
 public synchronized boolean syncOnce()throws Exception{
  guard();flushEvents();Object envelope=json("/manifest",null);Manifest next=SignedManifest.verify(envelope,c.get("server.publicKey",""));checkBus(next);
  if(next.revision().compareTo(minimumRevision)<0)throw new GeneralSecurityException("Refusing signed rollback");
  boolean changed=active==null||!next.revision().equals(active.revision());
  if(changed){long needed=0;Map<String,Long> sizes=new HashMap<>();for(Asset a:next.assets()){Long other=sizes.put(a.sha256(),a.bytes());if(other!=null&&other!=a.bytes())throw new IOException("Inconsistent content-addressed lengths");if(!Files.exists(media(a)))needed+=a.bytes();}
   long used;try(var walk=Files.list(c.cache().resolve("media"))){used=walk.mapToLong(p->{try{return Files.size(p);}catch(IOException e){return 0;}}).sum();}
   long budget=Long.parseLong(c.get("cache.maxBytes","4294967296"));if(needed+used>budget||needed+64*1024*1024L>Files.getFileStore(c.cache()).getUsableSpace())throw new IOException("Insufficient cache space; keeping previous release");
   for(Asset a:next.assets())download(a);verifyAssets(next);guard();FilesEx.json(c.cache().resolve("active.json"),envelope);active=next;minimumRevision=next.revision();System.out.println("Activated complete release "+next.revision());
  }
  json("/heartbeat",Map.of("revision",active.revision(),"queuedEvents",outbox.count(),"mode",c.get("audio.mode","null")));return changed;
 }
 private void verifyAssets(Manifest m)throws IOException{for(Asset a:m.assets()){Path path=media(a);if(!Files.exists(path)||Files.size(path)!=a.bytes()||!FilesEx.sha(path).equals(a.sha256()))throw new IOException("Missing or corrupt cached asset "+a.id());}}
 private void download(Asset a)throws Exception{
  guard();Path dest=media(a),part=dest.resolveSibling(dest.getFileName()+".part");if(Files.exists(dest)&&Files.size(dest)==a.bytes()&&FilesEx.sha(dest).equals(a.sha256()))return;
  if(Files.exists(part)&&Files.size(part)>=a.bytes())Files.delete(part);long offset=Files.exists(part)?Files.size(part):0;
  var b=request("/media/"+a.sha256()).GET();if(offset>0)b.header("Range","bytes="+offset+"-").header("If-Range","\""+a.sha256()+"\"");
  var r=http.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());try(InputStream in=r.body()){
   if(r.statusCode()!=200&&r.statusCode()!=206)throw new IOException("Media HTTP "+r.statusCode());if(r.statusCode()==200)offset=0;
   if(r.statusCode()==206&&!r.headers().firstValue("Content-Range").orElse("").startsWith("bytes "+offset+"-"))throw new IOException("Invalid resume range");
   long start=offset;ScheduledFuture<?> timeout=watchdog.schedule(()->{try{in.close();}catch(IOException ignored){}},180,TimeUnit.SECONDS);
   try(FileChannel f=FileChannel.open(part,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){f.truncate(start);f.position(start);byte[] bytes=new byte[65536];int n;long count=start;while((n=in.read(bytes))>=0){guard();count+=n;if(count>a.bytes())throw new IOException("Media exceeds signed length");ByteBuffer buffer=ByteBuffer.wrap(bytes,0,n);while(buffer.hasRemaining())f.write(buffer);}f.force(true);if(count!=a.bytes())throw new EOFException("Partial download retained for resume");}finally{timeout.cancel(false);}
  }
  if(!FilesEx.sha(part).equals(a.sha256())){Files.deleteIfExists(part);throw new IOException("SHA-256 mismatch; release not activated");}Files.move(part,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
 private void flushEvents()throws Exception{for(int i=0;i<20;i++){var batch=outbox.batch();if(batch.isEmpty())return;var response=obj(json("/events",Map.of("events",batch)));Set<String> submitted=new HashSet<>();batch.forEach(e->submitted.add(str(obj(e),"id")));outbox.acknowledge(arr(response.get("accepted")),submitted);}}
 @Override public void close(){watchdog.shutdownNow();http.close();}
}
