package io.github.eljah.busradio.server;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Model.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Synthetic fixture: neither a real bus route nor real business advertisements. */
public final class DemoData {
 private DemoData(){}
 public static Asset tone(Store s,String id,String name,int seconds,double hz,String kind)throws Exception{
  ByteBuffer b=ByteBuffer.allocate(seconds*BYTES_PER_SECOND).order(ByteOrder.LITTLE_ENDIAN);int frames=seconds*SAMPLE_RATE;
  for(int i=0;i<frames;i++){double fade=Math.min(1,Math.min(i,frames-1-i)/(SAMPLE_RATE*.02));double value=Math.sin(2*Math.PI*hz*i/SAMPLE_RATE)*.18*fade;b.putShort((short)(32767*value));}
  byte[] bytes=b.array();String hash=FilesEx.sha(bytes);FilesEx.atomic(s.media.resolve(hash+".pcm"),bytes);Asset a=new Asset(id,name,hash,bytes.length,kind,"Original synthesized test tone; no third-party music");s.put("assets",id,a);return a;
 }
 public static String install(Store s)throws Exception{
  if(!Json.obj(s.snapshot().get("buses")).isEmpty())return null;
  tone(s,"demo-music","Демо: музыкальный тон",4,261.63,"MUSIC");tone(s,"demo-filler","Демо: заполнитель окна",3,196,"FILLER");tone(s,"demo-ad-near","Демо: ближайший объект",2,523.25,"AD");tone(s,"demo-ad-far","Демо: следующий объект",2,659.25,"AD");
  Route r=new Route("demo:0","Синтетический маршрут / восток","0","SHAPE",List.of(new Point(55.80,49.10),new Point(55.80,49.12),new Point(55.80,49.14)));s.put("routes",r.id(),r);List<Point> reversed=new ArrayList<>(r.points());Collections.reverse(reversed);Route back=new Route("demo:1","Синтетический маршрут / запад","1","SHAPE",reversed);s.put("routes",back.id(),back);
  Playlist p=new Playlist("demo-playlist","Музыка → геореклама → музыка",List.of(new Slot("MUSIC","demo-music",0,0,""),new Slot("AD_WINDOW","",8,2,"demo-filler")));s.put("playlists",p.id(),p);
  Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=Instant.parse("2035-01-01T00:00:00Z");
  for(int i=0;i<2;i++){String id=i==0?"demo-near":"demo-far",asset=i==0?"demo-ad-near":"demo-ad-far";Campaign c=new Campaign(id,i==0?"Условная пекарня впереди":"Условное кафе дальше",asset,List.of(r.id()),new Point(55.80,i==0?49.104:49.109),1000,1000,180,10,30,100,from,to,"Europe/Moscow","00:00","00:00",true);s.put("campaigns",id,c);}
  Campaign backAd=new Campaign("demo-return","Условный объект на обратном пути","demo-ad-near",List.of("demo:1"),new Point(55.80,49.104),1000,1000,180,10,30,100,from,to,"Europe/Moscow","00:00","00:00",true);s.put("campaigns",backAd.id(),backAd);
  String token=FilesEx.token();s.put("buses","demo-bus",Map.of("id","demo-bus","playlistId",p.id(),"routeId",r.id(),"tokenHash",FilesEx.sha(token.getBytes(StandardCharsets.UTF_8))));s.publish("demo-bus");return token;
 }
}
