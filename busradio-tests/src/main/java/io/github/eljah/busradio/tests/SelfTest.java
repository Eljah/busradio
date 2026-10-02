package io.github.eljah.busradio.tests;

import io.github.eljah.busradio.core.*;
import io.github.eljah.busradio.server.*;
import io.github.eljah.busradio.edge.*;
import static io.github.eljah.busradio.core.Model.*;
import static io.github.eljah.busradio.core.Json.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free deterministic integration suite. Runs during Maven verify, never transmits RF. */
public final class SelfTest {
    @FunctionalInterface interface Check { void run() throws Exception; }
    private static final List<String> successes = new ArrayList<>();
    private static final Map<String,String> failures = new LinkedHashMap<>();
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static Path work;
    private static void test(String name, Check action) {
        try { action.run(); successes.add(name); System.out.println("PASS " + name); }
        catch (Throwable e) { failures.put(name,e.toString()); e.printStackTrace(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Operation should have been rejected");
    }
    private static Fix fix(double lon, double course) {
        return new Fix(new Point(55.8,lon),5,course,3,NOW,"TEST");
    }
    private static Config config(int port,String token,String key,Path cache,Path wav) {
        Properties p=new Properties();
        p.setProperty("server","http://127.0.0.1:"+port);
        p.setProperty("bus.id","demo-bus"); p.setProperty("bus.token",token);
        p.setProperty("server.publicKey",key); p.setProperty("cache.dir",cache.toString());
        p.setProperty("sync.demoLoopback","true"); p.setProperty("audio.mode","wav");
        p.setProperty("audio.file",wav.toString()); p.setProperty("audio.realtime","false");
        p.setProperty("gps.mode","none"); p.setProperty("player.maxSlots","2");
        return new Config(p);
    }
    private static HttpResponse<byte[]> request(HttpClient h,int port,String path,String method,Object body,String... headers) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        for(int i=0;i<headers.length;i+=2)b.header(headers[i],headers[i+1]);
        if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return h.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private static Object json(HttpResponse<byte[]> response) {
        return Json.parse(new String(response.body(),StandardCharsets.UTF_8));
    }
    private static Object routeFixture() {
        return Json.parse("""
            {"nbusstop":{"1":["A",49.10,55.80],"2":["B",49.12,55.80]},
             "bus":{"7":[null,"65",null,null,null,0]},
             "route":{"a":[7,2,0,1],"b":[7,1,0,0],"c":[7,2,1,0],"d":[7,1,1,1]}}
            """);
    }
    public static void main(String[] args) throws Exception {
        work=Files.createTempDirectory("busradio-verification-");
        try { run(); } finally {
            Path reports=Path.of(System.getProperty("busradio.reports","target/test-results"));
            Files.createDirectories(reports);
            StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"busradio\" tests=\"")
                .append(successes.size()+failures.size()).append("\" failures=\"").append(failures.size()).append("\">\n");
            for(String s:successes)xml.append("<testcase name=\"").append(escape(s)).append("\"/>\n");
            failures.forEach((name,error)->xml.append("<testcase name=\"").append(escape(name)).append("\"><failure message=\"").append(escape(error)).append("\"/></testcase>\n"));
            xml.append("</testsuite>\n"); Files.writeString(reports.resolve("TEST-busradio.xml"),xml);
            Files.writeString(reports.resolve("summary.txt"),"Java "+System.getProperty("java.version")+"\nPassed: "+successes.size()+"\nFailed: "+failures.size()+"\n"+String.join("\n",successes)+"\n"+failures);
            System.out.printf("RESULT: %d passed, %d failed. Reports: %s%n",successes.size(),failures.size(),reports.toAbsolutePath());
            try(var files=Files.walk(work)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
        if(!failures.isEmpty())throw new IllegalStateException("Verification failed: "+failures.keySet());
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static void run() throws Exception {
        test("strict JSON round-trip",()->require(str(obj(Json.parse(Json.write(Map.of("text","Татарстан\n\"", "number",42)))),"text").equals("Татарстан\n\""),"UTF-8 round trip"));
        test("JSON duplicate keys rejected",()->rejects(()->Json.parse("{\"a\":null,\"a\":1}")));
        test("JSON trailing input rejected",()->rejects(()->Json.parse("{} false")));
        test("JSON non-finite numbers rejected",()->rejects(()->Json.parse("1e999")));
        test("invalid geographic coordinates rejected",()->rejects(()->new Point(Double.NaN,49)));
        test("overnight window and exclusive end",()->{
            require(Model.inWindow(LocalTime.of(23,30),LocalTime.of(23,0),LocalTime.of(5,0)),"Before midnight");
            require(Model.inWindow(LocalTime.of(2,0),LocalTime.of(23,0),LocalTime.of(5,0)),"After midnight");
            require(!Model.inWindow(LocalTime.of(5,0),LocalTime.of(23,0),LocalTime.of(5,0)),"Exclusive end");
        });
        test("buscrawl tuple order and two directions",()->{
            var routes=BuscrawlAdapter.routes(routeFixture()).routes();require(routes.size()==2,"Two directions");
            require(routes.getFirst().points().getFirst().equals(new Point(55.80,49.10)),"Longitude then latitude in source");
            require(routes.get(1).points().getFirst().lon()==49.12,"Reverse order");
            require(routes.getFirst().geometry().equals("STOP_CHORDS"),"Do not mislabel road geometry");
        });
        test("buscrawl missing order never joins across gap",()->{
            var fixture=obj(routeFixture());obj(fixture.get("route")).put("a",List.of(7,2,0,8));
            require(BuscrawlAdapter.routes(fixture).routes().size()==1,"Broken outbound excluded");
        });
        test("buscrawl movement source time and speed conversion",()->{
            var row=new LinkedHashMap<String,Object>(Map.of("source","navi","plate","test-bus","latitude",55.8,"longitude",49.1,"speed",36,"course",90,"timestamp",NOW.getEpochSecond(),"sourceTimestamp",NOW.minusSeconds(60).getEpochSecond()));
            Fix f=BuscrawlAdapter.movement(row,"test-bus");require(f.speedMps()==10&&!f.fresh(NOW),"Never retime old GPS");
            rejects(()->BuscrawlAdapter.movement(row,"different-bus"));row.remove("sourceTimestamp");rejects(()->BuscrawlAdapter.movement(row,"test-bus"));
        });
        test("GPSD uncertainty and no-fix fail closed",()->{
            require(Positions.parseGpsd(Map.of("class","TPV","mode",1))==null,"No fix");
            Fix f=Positions.parseGpsd(Map.of("class","TPV","mode",3,"time",NOW.toString(),"lat",55.8,"lon",49.1,"speed",0));
            require(!f.fresh(NOW),"Missing accuracy must not become perfect GPS");
        });
        try(Store store=new Store(work.resolve("server"))){
            String token=DemoData.install(store);
            Manifest manifest=SignedManifest.verify(store.envelope("demo-bus"),store.publicKey);
            test("release includes outbound and return",()->require(manifest.allRoutes().size()==2,"Two directions in offline release"));
            test("signed manifest tampering rejected",()->{
                var envelope=new LinkedHashMap<>(obj(store.envelope("demo-bus")));
                byte[] payload=Base64.getDecoder().decode(str(envelope,"payload"));payload[payload.length/2]^=1;
                envelope.put("payload",Base64.getEncoder().encodeToString(payload));rejects(()->SignedManifest.verify(envelope,store.publicKey));
            });
            test("wrong pinned signing key rejected",()->{
                String key=Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
                rejects(()->SignedManifest.verify(store.envelope("demo-bus"),key));
            });
            AdSelector selector=new AdSelector();
            test("nearest forward advertisement wins",()->require(selector.choose(manifest,fix(49.100,90),NOW,8L*SAMPLE_RATE,c->true).orElseThrow().campaign().id().equals("demo-near"),"Nearest ahead"));
            test("passed business is excluded",()->require(selector.choose(manifest,fix(49.107,90),NOW,8L*SAMPLE_RATE,c->true).orElseThrow().campaign().id().equals("demo-far"),"Do not advertise behind"));
            test("opposite direction is excluded",()->require(selector.choose(manifest,fix(49.107,270),NOW,8L*SAMPLE_RATE,c->true).isEmpty(),"Wrong heading"));
            test("stale and off-route GPS suppress advertisements",()->{
                require(selector.choose(manifest,new Fix(new Point(55.8,49.1),5,90,3,NOW.minusSeconds(16),"TEST"),NOW,8L*SAMPLE_RATE,c->true).isEmpty(),"Stale");
                require(selector.choose(manifest,new Fix(new Point(55.85,49.1),5,90,3,NOW,"TEST"),NOW,8L*SAMPLE_RATE,c->true).isEmpty(),"Off route");
            });
            test("advertisement must fit full remaining window",()->require(selector.choose(manifest,fix(49.1,90),NOW,SAMPLE_RATE,c->true).isEmpty(),"Do not cut ad"));
            test("expired campaign is excluded",()->require(selector.choose(manifest,new Fix(new Point(55.8,49.1),5,90,3,Instant.parse("2036-01-01T00:00:00Z"),"TEST"),Instant.parse("2036-01-01T00:00:00Z"),8L*SAMPLE_RATE,c->true).isEmpty(),"Expired"));
            test("direction reverses and is retained at stops",()->{
                DirectionResolver d=new DirectionResolver();require(d.resolve(manifest,new Fix(new Point(55.8,49.1),0,0,3,NOW,"TEST"),NOW).isEmpty(),"Stationary startup unknown");
                require(d.resolve(manifest,fix(49.11,270),NOW).orElseThrow().id().equals("demo:1"),"Return heading");
                require(d.resolve(manifest,new Fix(new Point(55.8,49.11),0,0,3,NOW,"TEST"),NOW).orElseThrow().id().equals("demo:1"),"Stop retains direction");
            });
            test("frequency caps survive edge restart",()->{
                Path cache=work.resolve("caps");Files.createDirectories(cache);History h=new History(cache);Campaign ad=manifest.campaigns().getFirst();
                require(h.allowed(ad,NOW),"Initially allowed");h.reserve(ad,NOW);require(!new History(cache).allowed(ad,NOW.plusSeconds(2)),"Cooldown persisted");
            });
            try(RadioServer server=new RadioServer(store,"127.0.0.1",0,"test-password-long-enough","",System.getProperty("busradio.test.ffmpeg","/usr/bin/ffmpeg"));HttpClient h=HttpClient.newHttpClient()){
                server.start();int port=server.port();String auth="Bearer "+token;
                test("admin authentication required",()->require(request(h,port,"/api/admin/state","GET",null).statusCode()==401,"Unauthorized"));
                var login=request(h,port,"/api/login","POST",Map.of("username","admin","password","test-password-long-enough"));
                String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];String csrf=str(obj(json(login)),"csrf");
                test("admin session and CSRF enforcement",()->{
                    require(request(h,port,"/api/admin/state","GET",null,"Cookie",cookie).statusCode()==200,"Session read");
                    require(request(h,port,"/api/admin/publish","POST",Map.of("busId","demo-bus"),"Cookie",cookie).statusCode()==403,"CSRF required");
                    require(request(h,port,"/api/admin/publish","POST",Map.of("busId","demo-bus"),"Cookie",cookie,"X-CSRF-Token",csrf,"Origin","https://evil.example").statusCode()==403,"Origin rejected");
                });
                test("device token cannot access another bus",()->require(request(h,port,"/api/bus/other/manifest","GET",null,"Authorization",auth).statusCode()==401,"Bus binding"));
                Asset music=manifest.assetMap().get("demo-music");
                test("HTTP range download and unsatisfiable range",()->{
                    var r=request(h,port,"/api/bus/demo-bus/media/"+music.sha256(),"GET",null,"Authorization",auth,"Range","bytes=8-15");
                    require(r.statusCode()==206&&r.body().length==8,"Partial content");
                    require(request(h,port,"/api/bus/demo-bus/media/"+music.sha256(),"GET",null,"Authorization",auth,"Range","bytes=99999999-").statusCode()==416,"Out of bounds");
                });
                test("unpublished content is not accessible to devices",()->require(request(h,port,"/api/bus/demo-bus/media/"+"a".repeat(64),"GET",null,"Authorization",auth).statusCode()==403,"Not authorized hash"));
                test("real FFmpeg audio normalization",()->{
                    byte[] pcm=Files.readAllBytes(store.media.resolve(music.sha256()+".pcm"));ByteArrayOutputStream wav=new ByteArrayOutputStream();wav.write(AudioSinks.header(pcm.length));wav.write(pcm);
                    Asset result=AudioImporter.upload(store,new ByteArrayInputStream(wav.toByteArray()),"Normalization test","MUSIC","Original synthetic tone",System.getProperty("busradio.test.ffmpeg","/usr/bin/ffmpeg"));
                    require(result.bytes()==pcm.length&&result.sha256().equals(music.sha256()),"Exact canonical PCM");
                });
                Path cache=work.resolve("edge");Config cfg=config(port,token,store.publicKey,cache,work.resolve("aircheck.wav"));
                Outbox outbox=new Outbox(cache);AtomicBoolean connected=new AtomicBoolean(true);
                try(SyncClient sync=new SyncClient(cfg,connected::get,outbox)){
                    test("resume partial download and atomic activation",()->{
                        Path part=sync.media(music).resolveSibling(music.sha256()+".pcm.part");Files.write(part,Arrays.copyOf(Files.readAllBytes(store.media.resolve(music.sha256()+".pcm")),10000));
                        require(sync.syncOnce(),"Initial activation");require(FilesEx.sha(sync.media(music)).equals(music.sha256()),"Hash after resume");require(!Files.exists(part),"Part atomically moved");
                    });
                    test("offline playback preserves exact window and ad order",()->{
                        connected.set(false);try(Player player=new Player(cfg,sync,outbox,new History(cache),()->fix(49.1,90),()->true,CLOCK)){player.run(0);}
                        byte[] wav=Files.readAllBytes(work.resolve("aircheck.wav"));require(wav.length==44+12*BYTES_PER_SECOND,"4-second track plus exact 8-second window");
                        List<Map<String,Object>> ads=outbox.batch().stream().map(Json::obj).filter(e->str(e,"kind").equals("AD")&&bool(e,"completed",false)).toList();
                        require(ads.size()==2,"Two complete ads");require(ads.stream().anyMatch(e->str(e,"campaignId").equals("demo-near")),"Nearest played");
                        require(ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40)==12*BYTES_PER_SECOND,"Valid WAV header");
                        require(outbox.count()>=8,"Durable offline events");connected.set(true);
                    });
                    test("at-least-once reports produce no duplicate server events",()->{
                        List<Object> batch=outbox.batch();store.acceptEvents("demo-bus",batch);long previous=store.events().size();
                        sync.syncOnce();require(outbox.count()==0,"Acknowledged queue cleared");require(store.events().size()==previous,"Duplicates deduplicated");
                    });
                    Object previousEnvelope=store.envelope("demo-bus");
                    test("corrupt replacement cannot replace last good release",()->{
                        String rev=sync.active().revision();Asset replacement=DemoData.tone(store,"replacement","Replacement",1,777,"MUSIC");
                        Playlist p=new Playlist("replacement-list","Replacement",List.of(new Slot("MUSIC",replacement.id(),0,0,"")));store.put("playlists",p.id(),p);
                        store.edit(s->{obj(obj(s.get("buses")).get("demo-bus")).put("playlistId",p.id());return null;});store.publish("demo-bus");
                        Path file=store.media.resolve(replacement.sha256()+".pcm");byte[] good=Files.readAllBytes(file);byte[] bad=good.clone();bad[200]^=1;Files.write(file,bad);
                        rejects(sync::syncOnce);require(sync.active().revision().equals(rev),"Old release remains active");Files.write(file,good);require(sync.syncOnce(),"Retry activates correct replacement");
                    });
                    test("validly signed old release cannot roll back cache",()->{
                        Object current=store.envelope("demo-bus");store.edit(s->{obj(obj(s.get("releases")).get("demo-bus")).put("current",previousEnvelope);return null;});
                        rejects(sync::syncOnce);store.edit(s->{obj(obj(s.get("releases")).get("demo-bus")).put("current",current);return null;});
                    });
                }
                test("missing cached file repairs without discarding signing trust",()->{
                    Manifest current=SignedManifest.verify(store.envelope("demo-bus"),store.publicKey);Path missing=cache.resolve("media").resolve(current.assets().getFirst().sha256()+".pcm");Files.delete(missing);
                    try(SyncClient repaired=new SyncClient(cfg,()->true,outbox)){require(repaired.active()==null,"Corrupt cache not playable");repaired.syncOnce();require(repaired.active()!=null&&Files.exists(missing),"Cache repaired");}
                });
                test("missing GPS substitutes filler, never unrelated advertisements",()->{
                    store.edit(s->{obj(obj(s.get("buses")).get("demo-bus")).put("playlistId","demo-playlist");return null;});store.publish("demo-bus");
                    Path isolated=work.resolve("no-gps");Config noGps=config(port,token,store.publicKey,isolated,work.resolve("no-gps.wav"));Outbox queue=new Outbox(isolated);
                    try(SyncClient sync=new SyncClient(noGps,()->true,queue)){sync.syncOnce();try(Player p=new Player(noGps,sync,queue,new History(isolated),()->null,()->true,CLOCK)){p.run(0);}}
                    require(queue.batch().stream().map(Json::obj).noneMatch(e->str(e,"kind").equals("AD")),"No targeted ad without fix");
                    require(Files.size(work.resolve("no-gps.wav"))==44+12*BYTES_PER_SECOND,"Exact fallback duration");
                });
                test("network policy fails closed outside configured night hours",()->{
                    Properties p=new Properties();p.setProperty("server","http://127.0.0.1:"+port);p.setProperty("bus.id","demo-bus");p.setProperty("bus.token",token);p.setProperty("server.publicKey",store.publicKey);
                    p.setProperty("sync.start","01:00");p.setProperty("sync.end","05:00");require(!new NetworkPolicy(new Config(p),CLOCK).getAsBoolean(),"No day sync");
                });
                test("FM output requires explicit authorization and real hardware",()->rejects(()->{
                    Properties p=new Properties();p.setProperty("server","http://127.0.0.1:"+port);p.setProperty("bus.id","demo-bus");p.setProperty("bus.token",token);p.setProperty("server.publicKey",store.publicKey);p.setProperty("audio.mode","fm");AudioSinks.open(new Config(p));
                }));
            }
        }
        test("journal reopens and discards only incomplete final append",()->{
            Path events=work.resolve("server/events.jsonl");long before=Files.size(events);Files.writeString(events,"{\"incomplete\":",StandardOpenOption.APPEND);
            try(Store reopened=new Store(work.resolve("server"))){require(!reopened.events().isEmpty(),"Complete events retained");require(Files.size(events)==before,"Incomplete suffix truncated");}
        });
        test("one continuous native-process PCM stream",()->{
            Path capture=work.resolve("native-pcm.wav");String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
            String cp=Path.of(PcmProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            try(AudioSinks.ProcessSink sink=new AudioSinks.ProcessSink(List.of(java,"-cp",cp,PcmProbe.class.getName(),capture.toString()),true)){sink.write(new byte[882],882);sink.write(new byte[882],882);}
            byte[] bytes=Files.readAllBytes(capture);require(bytes.length==44+1764,"Exactly one WAV header across two segments");require(new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF"),"WAV bridge");
        });
    }
}
