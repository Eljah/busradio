package io.github.eljah.busradio.server;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Model.*;
import static io.github.eljah.busradio.core.Json.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Single-writer filesystem store. Atomic snapshots + fsynced, idempotent event journal. */
public final class Store implements AutoCloseable {
 public final Path root,media;
 private final FileChannel lockChannel;private final FileLock lock;
 private Map<String,Object> db;
 private final PrivateKey signingKey;public final String publicKey;
 private final Set<String> eventIds=new HashSet<>();private final ArrayDeque<Object> recentEvents=new ArrayDeque<>();
 public Store(Path root)throws Exception{
  this.root=root.toAbsolutePath();Files.createDirectories(this.root);this.media=this.root.resolve("media");Files.createDirectories(media);
  lockChannel=FileChannel.open(this.root.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=lockChannel.tryLock();if(lock==null)throw new IllegalStateException("State directory already in use");
  Path snapshot=this.root.resolve("state.json");if(Files.exists(snapshot))db=FilesEx.readObject(snapshot);else{db=new LinkedHashMap<>();for(String k:List.of("assets","playlists","routes","campaigns","buses","releases"))db.put(k,new LinkedHashMap<>());db.put("revisionSeq",0L);FilesEx.json(snapshot,db);}
  Path keys=this.root.resolve("signing-key.json");if(!Files.exists(keys)){var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();FilesEx.json(keys,Map.of("private",Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),"public",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));privateFile(keys);}
  var key=FilesEx.readObject(keys);signingKey=SignedManifest.privateKey(str(key,"private"));publicKey=str(key,"public");
  Path events=this.root.resolve("events.jsonl");if(Files.exists(events))recoverJournal(events);
 }
 /** Ignore only an unterminated final append; a corrupt committed line is a hard error. */
 private void recoverJournal(Path events)throws IOException{
  try(RandomAccessFile f=new RandomAccessFile(events.toFile(),"rw")){
   ByteArrayOutputStream line=new ByteArrayOutputStream();long complete=0;int b;
   while((b=f.read())!=-1){if(b=='\n'){if(line.size()>0)remember(obj(Json.parse(line.toString(StandardCharsets.UTF_8))));line.reset();complete=f.getFilePointer();}else{line.write(b);if(line.size()>20000)throw new IOException("Event journal line too large");}}
   if(f.length()!=complete){f.setLength(complete);f.getFD().sync();System.err.println("Discarded incomplete final journal append after interrupted write");}
  }
 }
 public static void privateFile(Path path)throws IOException{try{Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
 private void remember(Map<String,Object> event){eventIds.add(str(event,"id"));recentEvents.addLast(event);while(recentEvents.size()>200)recentEvents.removeFirst();}
 public synchronized Map<String,Object> snapshot(){return obj(Json.parse(Json.write(db)));}
 public synchronized <T>T edit(Function<Map<String,Object>,T> fn)throws IOException{var next=snapshot();T result=fn.apply(next);FilesEx.json(root.resolve("state.json"),next);db=next;return result;}
 public synchronized List<Object> events(){return List.copyOf(recentEvents);}
 public synchronized List<String> acceptEvents(String busId,List<Object> values)throws IOException{
  if(values.size()>100)throw new IllegalArgumentException("Maximum 100 events per batch");List<Map<String,Object>> checked=new ArrayList<>();
  for(Object v:values){var e=obj(v);String id=str(e,"id");UUID.fromString(id);if(!busId.equals(str(e,"busId")))throw new IllegalArgumentException("Event bus mismatch");Instant.parse(str(e,"at"));FilesEx.id(str(e,"revision"));FilesEx.id(str(e,"assetId"));str(e,"kind");bool(e,"completed",false);if(Json.write(e).length()>8192)throw new IllegalArgumentException("Event too large");checked.add(e);}
  List<String> accepted=new ArrayList<>();for(var e:checked){String id=str(e,"id");if(!eventIds.contains(id)){var saved=new LinkedHashMap<>(e);saved.put("receivedAt",Instant.now().toString());byte[] line=(Json.write(saved)+"\n").getBytes(StandardCharsets.UTF_8);try(FileChannel f=FileChannel.open(root.resolve("events.jsonl"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){ByteBuffer b=ByteBuffer.wrap(line);while(b.hasRemaining())f.write(b);f.force(true);}remember(saved);}accepted.add(id);}return accepted;
 }
 public synchronized Map<String,Object> publish(String busId)throws Exception{
  var s=snapshot();var bus=obj(obj(s.get("buses")).get(FilesEx.id(busId)));var p=Playlist.from(obj(s.get("playlists")).get(str(bus,"playlistId")));var r=Route.from(obj(s.get("routes")).get(str(bus,"routeId")));
  String prefix=r.id().contains(":")?r.id().substring(0,r.id().lastIndexOf(':'))+":":r.id();
  List<Route> alternatives=obj(s.get("routes")).values().stream().map(Route::from).filter(other->!other.id().equals(r.id())&&r.id().contains(":")&&other.id().startsWith(prefix)).toList();
  Set<String> routeIds=new HashSet<>();routeIds.add(r.id());alternatives.forEach(other->routeIds.add(other.id()));
  List<Campaign> ads=obj(s.get("campaigns")).values().stream().map(Campaign::from).filter(a->a.routeIds().stream().anyMatch(routeIds::contains)).toList();Set<String> needed=new LinkedHashSet<>();
  for(var slot:p.slots()){if(slot.type().equals("MUSIC"))needed.add(slot.assetId());if(slot.fallbackAssetId()!=null&&!slot.fallbackAssetId().isBlank())needed.add(slot.fallbackAssetId());}ads.forEach(a->needed.add(a.assetId()));
  var all=obj(s.get("assets"));List<Asset> assets=needed.stream().map(id->Asset.from(all.get(id))).toList();long n=lng(s,"revisionSeq")+1;String rev=String.format(Locale.ROOT,"r-%012d",n);
  var manifest=new Manifest(1,busId,rev,Instant.now(),p,r,alternatives,assets,ads);var envelope=SignedManifest.sign(manifest,signingKey);
  var releases=obj(s.get("releases"));Set<String> allowed=new HashSet<>();if(releases.containsKey(busId))arr(obj(releases.get(busId)).get("allowedHashes")).forEach(x->allowed.add(x.toString()));assets.forEach(a->allowed.add(a.sha256()));
  releases.put(busId,Map.of("current",envelope,"revision",rev,"allowedHashes",allowed.stream().sorted().toList()));s.put("revisionSeq",n);FilesEx.json(root.resolve("state.json"),s);db=s;return Map.of("busId",busId,"revision",rev,"assets",assets.size());
 }
 public boolean authorized(String busId,String token){var b=obj(snapshot().get("buses")).get(busId);return b!=null&&token!=null&&FilesEx.same(str(obj(b),"tokenHash"),FilesEx.sha(token.getBytes(StandardCharsets.UTF_8)));}
 public Object envelope(String bus){var r=obj(snapshot().get("releases")).get(bus);if(r==null)throw new IllegalArgumentException("No published release for bus");return obj(r).get("current");}
 public Path authorizedMedia(String bus,String hash){FilesEx.hash(hash);var r=obj(obj(snapshot().get("releases")).get(bus));if(!arr(r.get("allowedHashes")).contains(hash))throw new SecurityException("Asset not published to this bus");return media.resolve(hash+".pcm");}
 public void put(String collection,String id,Object value)throws IOException{FilesEx.id(id);edit(s->{obj(s.get(collection)).put(id,value);return null;});}
 public Map<String,Object> adminState(){var s=snapshot();List<Object> buses=new ArrayList<>();for(var e:obj(s.get("buses")).entrySet()){var b=new LinkedHashMap<>(obj(e.getValue()));b.remove("tokenHash");Object release=obj(s.get("releases")).get(e.getKey());b.put("publishedRevision",release==null?"":str(obj(release),"revision"));buses.add(b);}Map<String,Object> out=new LinkedHashMap<>();for(String c:List.of("assets","playlists","routes","campaigns"))out.put(c,new ArrayList<>(obj(s.get(c)).values()));out.put("buses",buses);out.put("events",events());out.put("publicKey",publicKey);return out;}
 @Override public void close()throws IOException{lock.release();lockChannel.close();}
}
