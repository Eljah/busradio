package org.eljah.busradio;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.eljah.busradio.Model.*;
import org.eljah.busradio.Support.Config;

/** Synthetic route and synthesized audio, not a real bus or paid advertising. */
public final class Demo {
    private Demo(){}
    public static Asset upload(Api admin,Path path,String name,String kind)throws Exception{
        try(Api.Response r=admin.request("POST","/api/admin/assets?name="+java.net.URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8)+"&kind="+kind,Files.readAllBytes(path),Map.of("Content-Type","application/octet-stream"))){
            String text=r.text(65536);if(r.raw.statusCode()!=201)throw new IllegalStateException(text);return Json.read(text,Asset.class);
        }
    }
    public record Seed(Config node,Asset music,Asset advert,Route route){}
    public static Seed seed(Api admin,Path dir,String url,int musicSeconds)throws Exception{
        Files.createDirectories(dir);Path song=dir.resolve("demo-music.wav"),ad=dir.resolve("demo-ad.wav");Wave.tone(song,220,musicSeconds);Wave.tone(ad,660,1);
        Asset music=upload(admin,song,"Учебный музыкальный сигнал","MUSIC"),advert=upload(admin,ad,"Демо: магазин рядом","AD");
        Route route=new Route("demo","DEMO (не реальный маршрут)",0,"polyline",List.of(new Point(55.796,49.100),new Point(55.796,49.115),new Point(55.796,49.130)));
        List<Point> reverse=new ArrayList<>(route.points());Collections.reverse(reverse);
        admin.json("PUT","/api/admin/route",route);admin.json("PUT","/api/admin/route",new Route("demo",route.number(),1,"polyline",reverse));
        admin.json("PUT","/api/admin/playlist",new Playlist("daily","Демо / музыка и местная реклама",List.of(music.id()),1,10,2));Instant now=Instant.now();
        admin.json("PUT","/api/admin/campaign",new Campaign("nearby","Демо-магазин впереди",advert.id(),"demo",-1,55.796,49.112,800,1500,true,60,20,0,now.minusSeconds(3600),now.plusSeconds(86400*30)));
        var enrolled=Json.object(admin.json("POST","/api/admin/device",Map.of("id","bus-demo","playlistId","daily","routeId","demo","direction",0)));
        Config node=new Config().set("server.url",url).set("node.id","bus-demo").set("node.token",Json.string(enrolled,"token")).set("node.data",dir.resolve("node")).set("sync.start","00:00").set("sync.end","00:00").set("sync.requireWifi",false).set("sync.requireParked",false).set("sync.pollSeconds",1).set("sync.jitterSeconds",0).set("audio.mode","wav").set("position.mode","none");return new Seed(node,music,advert,route);
    }
    public static void run(Path root,int port)throws Exception{
        if(Files.exists(root.resolve("server/state.json")))throw new IllegalArgumentException("Choose a fresh demo directory to avoid overwriting a previous demonstration");
        String token=Support.token();Config sc=new Config().set("admin.token",token).set("server.port",port).set("server.data",root.resolve("server"));Server server=new Server(sc);String url="http://127.0.0.1:"+server.port();
        Seed seed;try(Api admin=new Api(url,token,"",false)){seed=seed(admin,root.resolve("demo"),url,5);}
        long start=System.nanoTime();Position gps=()->{double seconds=(System.nanoTime()-start)/1e9,phase=seconds%240;boolean outbound=phase<120;double x=outbound?phase/120:(240-phase)/120;return new Fix(55.796,49.105+x*.020,10,outbound?90:270,5,Instant.now());};
        Node node=new Node(seed.node(),gps,BoardIo.create(seed.node()));Runtime.getRuntime().addShutdownHook(new Thread(()->{node.close();server.close();}));
        System.out.println("\nBUSRADIO DEMO — synthetic data; RF OFF\nAdmin: "+url+"\nAdmin token: "+token+"\nCapture files: "+root.resolve("demo/node/captures").toAbsolutePath()+"\nCtrl+C to stop.\n");
        new CountDownLatch(1).await();
    }
}
