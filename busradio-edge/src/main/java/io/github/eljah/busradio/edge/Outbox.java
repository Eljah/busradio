package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** A separate atomic file per event: an uncertain HTTP acknowledgement never loses an event. */
public final class Outbox {
 private final Path dir;
 public Outbox(Path root)throws IOException{dir=root.resolve("outbox");Files.createDirectories(dir);}
 public synchronized void add(Map<String,Object> event)throws IOException{if(count()>=20000)throw new IOException("Outbox full; synchronize before recording more playout");String id=Json.str(event,"id");UUID.fromString(id);FilesEx.json(dir.resolve(id+".json"),event);}
 public synchronized List<Object> batch()throws IOException{try(var s=Files.list(dir)){List<Object> result=new ArrayList<>();for(Path p:s.filter(x->x.getFileName().toString().endsWith(".json")).sorted().limit(100).toList())result.add(FilesEx.readObject(p));return result;}}
 public synchronized void acknowledge(List<Object> ids,Set<String> submitted)throws IOException{for(Object id:ids){String s=id.toString();UUID.fromString(s);if(!submitted.contains(s))throw new IOException("Server acknowledged an event not in this batch");Files.deleteIfExists(dir.resolve(s+".json"));}}
 public synchronized long count()throws IOException{try(var s=Files.list(dir)){return s.filter(x->x.getFileName().toString().endsWith(".json")).count();}}
}
