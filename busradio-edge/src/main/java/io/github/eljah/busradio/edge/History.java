package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Json.*;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Reserve a play BEFORE sending audio, conservatively retaining caps across a power failure. */
public final class History {
 private final Path path;private Map<String,Object> records;
 public History(Path cache)throws IOException{path=cache.resolve("frequency-caps.json");records=Files.exists(path)?FilesEx.readObject(path):new LinkedHashMap<>();}
 public synchronized boolean allowed(Campaign c,Instant now){Object o=records.get(c.id());if(o==null)return true;var r=obj(o);Instant last=Instant.parse(str(r,"last"));if(now.isBefore(last.plusSeconds(c.cooldownSeconds())))return false;String date=now.atZone(ZoneId.of(c.zone())).toLocalDate().toString();return !date.equals(str(r,"day"))||lng(r,"count")<c.maxPerDay();}
 public synchronized void reserve(Campaign c,Instant now)throws IOException{String date=now.atZone(ZoneId.of(c.zone())).toLocalDate().toString();var old=records.get(c.id());long n=old!=null&&str(obj(old),"day").equals(date)?lng(obj(old),"count")+1:1;var next=new LinkedHashMap<>(records);next.put(c.id(),Map.of("last",now.toString(),"day",date,"count",n));FilesEx.json(path,next);records=next;}
}
