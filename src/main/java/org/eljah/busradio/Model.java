package org.eljah.busradio;

import java.time.Instant;
import java.util.*;

public final class Model {
    private Model() {}
    public static String id(String v){if(v==null||!v.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid ID");return v;}
    static void require(boolean ok,String msg){if(!ok)throw new IllegalArgumentException(msg);}
    public static void coordinate(double lat,double lon){require(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=85&&Math.abs(lon)<=180,"Invalid coordinates");}
    public record Point(double lat,double lon){public Point{coordinate(lat,lon);}}
    public record Asset(String id,String name,String sha256,long bytes,long durationMs,String kind){
        public Asset{Model.id(id);require(name!=null&&!name.isBlank()&&name.length()<=200,"Invalid asset name");require(sha256.matches("[a-f0-9]{64}"),"Invalid SHA256");require(bytes>44&&bytes<=268435456&&durationMs>0&&durationMs<=900000,"Invalid audio length");require(Set.of("MUSIC","AD").contains(kind),"Invalid asset kind");}
    }
    public record Playlist(String id,String name,List<String> tracks,int adEveryTracks,int adWindowSeconds,int maxAds){
        public Playlist{Model.id(id);Objects.requireNonNull(name);tracks=List.copyOf(tracks);require(!tracks.isEmpty()&&tracks.size()<=10000,"Playlist must contain tracks");tracks.forEach(Model::id);require(adEveryTracks>=1&&adEveryTracks<=100&&adWindowSeconds>=1&&adWindowSeconds<=300&&maxAds>=1&&maxAds<=10,"Invalid advertising window");}
    }
    public record Route(String id,String number,int direction,String geometry,List<Point> points){
        public Route{Model.id(id);Objects.requireNonNull(number);require(direction>=0&&direction<=9,"Invalid direction");require(Set.of("stop-chain","polyline").contains(geometry),"Invalid geometry type");points=List.copyOf(points);require(points.size()>=2&&points.size()<=20000,"Route requires 2..20000 points");}
        public String key(){return id+"~"+direction;}
    }
    public record Campaign(String id,String name,String assetId,String routeId,int direction,double lat,double lon,double radiusMeters,double aheadMeters,boolean aheadOnly,int cooldownSeconds,int maxPerDay,int priority,Instant startsAt,Instant endsAt){
        public Campaign{Model.id(id);Objects.requireNonNull(name);Model.id(assetId);if(!routeId.isEmpty())Model.id(routeId);require(direction>=-1&&direction<=9,"Invalid direction");coordinate(lat,lon);require(Double.isFinite(radiusMeters)&&radiusMeters>0&&radiusMeters<=10000&&Double.isFinite(aheadMeters)&&aheadMeters>=0&&aheadMeters<=10000,"Invalid target distance");require(cooldownSeconds>=0&&cooldownSeconds<=86400&&maxPerDay>=1&&maxPerDay<=1000&&Math.abs((long)priority)<=1000,"Invalid limits");require(startsAt.isBefore(endsAt),"Invalid campaign dates");}
    }
    public record Assignment(String id,String playlistId,String routeId,int direction,String tokenHash){
        public Assignment{Model.id(id);Model.id(playlistId);Model.id(routeId);require(direction>=0&&direction<=9,"Invalid direction");require(tokenHash.matches("[a-f0-9]{64}"),"Invalid token hash");}
    }
    public record Manifest(int schema,long revision,String busId,String routeId,int direction,Playlist playlist,List<Asset> assets,List<Campaign> campaigns,List<Route> routes){
        public Manifest{require(schema==1&&revision>=0,"Unsupported manifest");Model.id(busId);Model.id(routeId);assets=List.copyOf(assets);campaigns=List.copyOf(campaigns);routes=List.copyOf(routes);require(assets.size()<=20000&&campaigns.size()<=10000,"Manifest too large");Set<String> ids=new HashSet<>();for(Asset a:assets)require(ids.add(a.id()),"Duplicate asset");require(ids.containsAll(playlist.tracks()),"Missing track asset");for(Campaign c:campaigns)require(ids.contains(c.assetId()),"Missing ad asset");require(routes.stream().anyMatch(r->r.id().equals(routeId)&&r.direction()==direction),"Missing assigned route");}
        public Asset asset(String id){return assets.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown asset "+id));}
        public Route route(){return routes.stream().filter(r->r.id().equals(routeId)&&r.direction()==direction).findFirst().orElseThrow();}
    }
    public record Fix(double lat,double lon,double speedMps,double bearing,double accuracyMeters,Instant time){
        public Fix{coordinate(lat,lon);require(Double.isFinite(speedMps)&&speedMps>=0&&speedMps<=80,"Invalid speed");require(Double.isFinite(bearing)&&bearing>=-1&&bearing<360,"Invalid bearing");require(Double.isFinite(accuracyMeters)&&accuracyMeters>=0,"Invalid accuracy");Objects.requireNonNull(time);}
    }
    public record Event(String id,String busId,Instant time,String assetId,String campaignId,String status,String detail){
        public Event{UUID.fromString(id);Model.id(busId);Objects.requireNonNull(time);if(!assetId.isEmpty())Model.id(assetId);if(!campaignId.isEmpty())Model.id(campaignId);require(Set.of("STARTED","COMPLETED","FAILED","SKIPPED").contains(status),"Invalid event status");require(detail.length()<=500,"Event detail too long");}
    }
}
