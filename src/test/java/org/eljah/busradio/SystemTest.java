package org.eljah.busradio;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.eljah.busradio.Model.*;
import org.eljah.busradio.Support.Config;

/** Framework-free acceptance suite, executed by Maven's test phase and the offline build. */
public final class SystemTest {
    interface Checked {void run()throws Exception;}
    record Result(String name,long millis,String error){}
    static final List<Result> results=new ArrayList<>();
    static void test(String name,Checked body){long start=System.nanoTime();String error="";try{body.run();System.out.println("PASS "+name);}catch(Throwable e){error=e.toString();System.err.println("FAIL "+name+": "+e);e.printStackTrace();}results.add(new Result(name,(System.nanoTime()-start)/1000000,error));}
    static void yes(boolean condition){if(!condition)throw new AssertionError("Condition failed");}
    static void fails(Checked body)throws Exception{boolean thrown=false;try{body.run();}catch(Exception e){thrown=true;}yes(thrown);}
    static void await(BooleanSupplier condition,long millis)throws Exception{long until=System.currentTimeMillis()+millis;while(!condition.getAsBoolean()){if(System.currentTimeMillis()>until)throw new AssertionError("Timed out waiting for condition");Thread.sleep(30);}}
    static Fix fix(Instant now,double lon,double bearing){return new Fix(55.796,lon,10,bearing,5,now);}
    static Campaign campaign(String id,String asset,double lon,int direction,Instant now){return new Campaign(id,id,asset,"route",direction,55.796,lon,1000,1500,true,120,2,0,now.minusSeconds(60),now.plusSeconds(3600));}
    public static void main(String[] args)throws Exception{
        results.clear();Path temp=Files.createTempDirectory("busradio-test-");Instant now=Instant.now();
        Asset music=new Asset("music","music","a".repeat(64),176444,1000,"MUSIC"),ad=new Asset("ad","ad","b".repeat(64),176444,1000,"AD");
        Route route=new Route("route","50",0,"polyline",List.of(new Point(55.796,49.100),new Point(55.796,49.130)));
        Route reverse=new Route("route","50",1,"polyline",List.of(new Point(55.796,49.130),new Point(55.796,49.100)));
        Campaign near=campaign("near","ad",49.112,0,now),behind=campaign("behind","ad",49.108,0,now),far=campaign("far","ad",49.119,0,now),back=campaign("back","ad",49.108,1,now);
        Manifest manifest=new Manifest(1,1,"bus","route",0,new Playlist("p","p",List.of("music"),1,10,2),List.of(music,ad),List.of(far,near,behind,back),List.of(route,reverse));
        Geo.Ledger ledger=new Geo.Ledger(temp.resolve("ledger.json"),ZoneId.of("Europe/Moscow"));
        test("JSON record roundtrip and Unicode",()->{yes(Json.read(Json.write(manifest),Manifest.class).equals(manifest));yes(Json.parse(Json.write("текст\n\"\\😀")).equals("текст\n\"\\😀"));});
        test("JSON duplicate keys / trailing data / invalid numbers rejected",()->{fails(()->Json.parse("{\"x\":1,\"x\":2}"));fails(()->Json.parse("[]garbage"));fails(()->Json.parse("01"));fails(()->Json.parse("1."));fails(()->Json.parse("1e9999"));fails(()->Json.parse("[".repeat(70)));});
        test("Invalid coordinates and unsafe IDs rejected",()->{fails(()->new Point(100,49));fails(()->Model.id("../../etc/passwd"));fails(()->new Fix(55,49,10,Double.NaN,5,now));});
        test("Night window including midnight and exclusive end",()->{yes(Support.inWindow(LocalTime.of(23,0),LocalTime.of(22,0),LocalTime.of(5,0)));yes(Support.inWindow(LocalTime.of(2,0),LocalTime.of(22,0),LocalTime.of(5,0)));yes(!Support.inWindow(LocalTime.of(5,0),LocalTime.of(22,0),LocalTime.of(5,0)));yes(!Support.inWindow(LocalTime.NOON,LocalTime.of(1,0),LocalTime.of(5,0)));});
        test("Nearest eligible object ahead selected, object behind excluded",()->yes(Geo.choose(manifest,fix(now,49.110,90),now,5000,Set.of(),ledger).orElseThrow().campaign().id().equals("near")));
        test("Automatic reverse direction uses reverse campaign",()->yes(Geo.choose(manifest,fix(now,49.110,270),now,5000,Set.of(),ledger).orElseThrow().campaign().id().equals("back")));
        test("GPS missing / stale / future / inaccurate suppresses ads",()->{yes(Geo.choose(manifest,null,now,5000,Set.of(),ledger).isEmpty());yes(Geo.choose(manifest,fix(now.minusSeconds(31),49.110,90),now,5000,Set.of(),ledger).isEmpty());yes(Geo.choose(manifest,fix(now.plusSeconds(6),49.110,90),now,5000,Set.of(),ledger).isEmpty());yes(Geo.choose(manifest,new Fix(55.796,49.110,10,90,51,now),now,5000,Set.of(),ledger).isEmpty());});
        test("Off-route and transverse heading suppresses ads",()->{yes(Geo.choose(manifest,new Fix(55.82,49.110,10,90,5,now),now,5000,Set.of(),ledger).isEmpty());yes(Geo.choose(manifest,fix(now,49.110,0),now,5000,Set.of(),ledger).isEmpty());});
        test("Creative is never truncated to fit window",()->yes(Geo.choose(manifest,fix(now,49.110,90),now,999,Set.of(),ledger).isEmpty()));
        test("Campaign expiration respected offline",()->yes(Geo.choose(manifest,fix(now.plusSeconds(4000),49.110,90),now.plusSeconds(4000),5000,Set.of(),ledger).isEmpty()));
        test("Cooldown and daily caps survive restart",()->{ledger.reserve(near,now);yes(!new Geo.Ledger(temp.resolve("ledger.json"),ZoneId.of("Europe/Moscow")).allowed(near,now.plusSeconds(5)));ledger.reserve(near,now.plusSeconds(121));yes(!ledger.allowed(near,now.plusSeconds(242)));});
        test("Buscrawl stop ordering, longitude/latitude and directions",()->{String raw="{\"bus\":{\"7\":[0,\"50\",0,0,0,0]},\"nbusstop\":{\"10\":[\"A\",49.10,55.796],\"11\":[\"B\",49.12,55.796]},\"route\":{\"b\":[7,11,0,1],\"a\":[7,10,0,0]}}";Route r=Buscrawl.routes(raw).getFirst();yes(r.id().equals("7")&&r.points().getFirst().lon()==49.10&&r.points().getFirst().lat()==55.796);});
        test("Buscrawl measurement timestamp and km/h conversion",()->{Fix f=Buscrawl.position("{\"plate\":\"TEST\",\"latitude\":55.796,\"longitude\":49.11,\"speed\":36,\"course\":90,\"timestamp\":2000,\"sourceTimestamp\":1000}","TEST");yes(f.time().equals(Instant.ofEpochSecond(1000))&&f.speedMps()==10);});
        test("Canonical WAV generation and inspection",()->{Path f=temp.resolve("tone.wav");Wave.tone(f,300,1);yes(Wave.inspect(f).dataBytes()==176400&&Wave.inspect(f).durationMs()==1000);Files.write(f,new byte[44]);fails(()->Wave.inspect(f));});
        test("FFmpeg converts a real MP3 to canonical PCM",()->{Path wav=temp.resolve("source.wav"),mp3=temp.resolve("source.mp3"),out=temp.resolve("converted.wav");Wave.tone(wav,440,1);Process ff=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-y","-i",wav.toString(),mp3.toString()).inheritIO().start();yes(ff.waitFor()==0);Wave.normalize(mp3,out,"ffmpeg");yes(Wave.inspect(out).durationMs()>=990);});
        test("Missing depot Wi-Fi blocks unattended sync",()->{Config c=new Config().set("sync.start","00:00").set("sync.end","00:00").set("sync.requireParked",false).set("sync.requireWifi",true).set("sync.interface","busradio-test-no-such-interface").set("sync.ssids","depot");yes(!SyncGate.allowed(c,BoardIo.create(c),now));});
        test("RF backend refuses default settings before touching hardware",()->{try(AudioOutput o=new AudioOutput(new Config().set("audio.mode","pifmrds"),temp)){fails(()->o.write(new byte[4],4));}});
        test("HTTP outside loopback requires explicit TLS",()->fails(()->new Api("http://example.com","token","bus",false)));
        test("Process lock excludes a second writer",()->{try(var lock=new Support.InstanceLock(temp.resolve("test.lock"))){fails(()->new Support.InstanceLock(temp.resolve("test.lock")));}});
        httpTests(temp.resolve("http"));
        Path report=Path.of("build/test-results.json");Files.createDirectories(report.getParent());Support.atomic(report,Json.write(Map.of("tests",results.size(),"failures",results.stream().filter(r->!r.error().isEmpty()).count(),"results",results)));
        long errors=results.stream().filter(r->!r.error().isEmpty()).count();StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"BusRadio\" tests=\""+results.size()+"\" failures=\""+errors+"\">");for(Result r:results){xml.append("<testcase name=\"").append(escape(r.name())).append("\" time=\"").append(r.millis()/1000.0).append("\">");if(!r.error().isEmpty())xml.append("<failure message=\"").append(escape(r.error())).append("\"/>");xml.append("</testcase>");}xml.append("</testsuite>");Support.atomic(Path.of("build/test-results.xml"),xml.toString());
        System.out.println("RESULT: "+results.size()+" tests, "+errors+" failures. Artifacts: build/test-results.{json,xml}");if(errors!=0)throw new AssertionError("Acceptance test failures: "+errors);
    }
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    static void httpTests(Path root)throws Exception{
        String token=Support.token();Config serverConfig=new Config().set("admin.token",token).set("server.port",0).set("server.data",root.resolve("server"));Server server=new Server(serverConfig);
        String url="http://127.0.0.1:"+server.port();int port=server.port();
        try(Api admin=new Api(url,token,"",false)){
            Demo.Seed seed=Demo.seed(admin,root.resolve("seed"),url,1);String deviceToken=seed.node().required("node.token");
            try(Api device=new Api(url,deviceToken,"bus-demo",false)){
                test("Admin and device authentication enforced",()->{try(Api bad=new Api(url,"wrong","bus-demo",false);Api.Response a=bad.request("GET","/api/admin/state",null,Map.of());Api.Response d=bad.request("GET","/api/device/manifest",null,Map.of())){yes(a.raw.statusCode()==401&&d.raw.statusCode()==401);}});
                test("HTTP manifest includes all route directions",()->{Manifest m=(Manifest)Json.convert(device.json("GET","/api/device/manifest",null),Manifest.class);yes(m.routes().size()==2&&m.assets().size()==2);});
                test("Byte Range serves the exact suffix and validators",()->{Asset a=seed.music();try(Api.Response r=device.request("GET","/api/device/assets/"+a.sha256(),null,Map.of("Range","bytes=1000-"))){yes(r.raw.statusCode()==206);yes(r.raw.headers().firstValue("Content-Range").orElseThrow().equals("bytes 1000-"+(a.bytes()-1)+"/"+a.bytes()));byte[] actual=r.raw.body().readAllBytes();byte[] expected=Files.readAllBytes(root.resolve("server/media/"+a.sha256()+".wav"));yes(Arrays.equals(actual,Arrays.copyOfRange(expected,1000,expected.length)));}});
                Cache cache=new Cache(root.resolve("cache"),device,"bus-demo",32*1048576L);
                test("Interrupted download resumes and atomically installs manifest",()->{byte[] bytes=Files.readAllBytes(root.resolve("server/media/"+seed.music().sha256()+".wav"));Files.write(cache.media.resolve(seed.music().sha256()+".part"),Arrays.copyOf(bytes,10000));cache.sync();yes(cache.active()!=null&&Support.hash(cache.file(seed.music())).equals(seed.music().sha256()));yes(!Files.exists(cache.media.resolve(seed.music().sha256()+".part")));});
                test("Corrupt download never promotes a new manifest",()->{
                    long revision=cache.active().revision();Path wav=root.resolve("new.wav");Wave.tone(wav,400,2);Asset bad=Demo.upload(admin,wav,"new song","MUSIC");admin.json("PUT","/api/admin/playlist",new Playlist("daily","new",List.of(bad.id()),1,10,2));Path remote=root.resolve("server/media/"+bad.sha256()+".wav");byte[] original=Files.readAllBytes(remote),broken=original.clone();broken[100]^=1;Files.write(remote,broken);fails(cache::sync);yes(cache.active().revision()==revision);yes(Support.hash(cache.file(seed.music())).equals(seed.music().sha256()));Files.write(remote,original);admin.json("PUT","/api/admin/playlist",new Playlist("daily","restored",List.of(seed.music().id()),1,10,2));cache.sync();
                });
                test("Per-event idempotency survives duplicate delivery",()->{Event e=new Event(UUID.randomUUID().toString(),"bus-demo",Instant.now(),seed.music().id(),"","COMPLETED","idempotency test");device.json("POST","/api/device/events",List.of(e));device.json("POST","/api/device/events",List.of(e));try(var list=Files.list(root.resolve("server/events"))){yes(list.filter(p->p.getFileName().toString().contains(e.id())).count()==1);}});
                test("Device cannot forge another bus event",()->fails(()->device.json("POST","/api/device/events",List.of(new Event(UUID.randomUUID().toString(),"other-bus",Instant.now(),"","","SKIPPED","forged")))));
                test("Route reassignment preserves the device credential",()->{admin.json("PUT","/api/admin/assignment",Map.of("id","bus-demo","playlistId","daily","routeId","demo","direction",0));yes(Json.object(device.json("GET","/api/device/manifest",null)).get("busId").equals("bus-demo"));});
            }
            Config nc=seed.node().set("node.data",root.resolve("offline-node")).set("playback.speed",10).set("audio.mode","wav");Position gps=()->fix(Instant.now(),49.110,90);
            try(Node node=new Node(nc,gps,BoardIo.create(nc))){
                test("E2E music and geoad produce COMPLETED events",()->await(()->{try{return node.outbox.pending(1000).stream().anyMatch(e->e.campaignId().equals("nearby")&&e.status().equals("COMPLETED"))||server.store.recent("events",200).stream().map(Json::object).anyMatch(e->"nearby".equals(e.get("campaignId"))&&"COMPLETED".equals(e.get("status")));}catch(Exception e){return false;}},10000));
                server.close();
                test("Playback continues with server disconnected",()->{Thread.sleep(250);long start=System.currentTimeMillis();await(()->{try{return node.outbox.pending(1000).stream().anyMatch(e->e.status().equals("COMPLETED")&&e.campaignId().isEmpty()&&e.time().toEpochMilli()>start);}catch(Exception e){return false;}},5000);yes(node.cache.active()!=null);});
                try(Server restored=new Server(serverConfig.set("server.port",port))){
                    test("Reconnect uploads the offline event queue",()->await(()->{try{return restored.store.recent("events",200).size()>10;}catch(Exception e){return false;}},10000));
                }
            }
            test("Captured continuous PCM is a valid WAV after shutdown",()->{try(var paths=Files.list(root.resolve("offline-node/captures"))){Path p=paths.filter(x->x.toString().endsWith(".wav")).findFirst().orElseThrow();yes(Wave.inspect(p).durationMs()>2000);}});
        }finally{server.close();}
    }
}
