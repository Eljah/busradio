package org.eljah.busradio;

import java.time.*;
import java.util.*;
import org.eljah.busradio.Model.*;

/** Local equirectangular projection; stop chains are NOT street-level map matching. */
public final class Geo {
    private Geo(){}
    static final double R=6371008.8;
    public static double distance(double a,double b,double c,double d){double x=Math.toRadians(c-a),y=Math.toRadians(d-b);double h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(y/2)*Math.sin(y/2);return 2*R*Math.asin(Math.sqrt(Math.min(1,h)));}
    public record Projection(double along,double crossTrack,double heading){}
    public static Projection project(Route route,double lat,double lon){
        double best=Double.POSITIVE_INFINITY,along=0,bestAlong=0,bearing=0;
        List<Point> points=route.points();
        for(int i=1;i<points.size();i++){
            Point a=points.get(i-1),b=points.get(i);double k=Math.cos(Math.toRadians((a.lat()+b.lat()+lat)/3));
            double dx=R*Math.toRadians(b.lon()-a.lon())*k,dy=R*Math.toRadians(b.lat()-a.lat());
            double px=R*Math.toRadians(lon-a.lon())*k,py=R*Math.toRadians(lat-a.lat());double len=Math.hypot(dx,dy);
            if(len<0.01)continue;double t=Math.max(0,Math.min(1,(px*dx+py*dy)/(len*len)));double off=Math.hypot(px-t*dx,py-t*dy);
            if(off<best){best=off;bestAlong=along+t*len;bearing=(Math.toDegrees(Math.atan2(dx,dy))+360)%360;}
            along+=len;
        }
        return new Projection(bestAlong,best,bearing);
    }
    public static double angle(double a,double b){return Math.abs((a-b+540)%360-180);}
    public record Choice(Campaign campaign,double distanceMeters,double aheadMeters,int direction){}
    public static Route resolveRoute(Manifest m,Fix fix,int stationaryHint){
        if(fix==null)return null;
        if(fix.speedMps()<=1)return m.routes().stream().filter(r->r.id().equals(m.routeId())&&r.direction()==stationaryHint).findFirst().orElse(m.route());
        if(fix.bearing()<0)return null;
        return m.routes().stream().filter(r->r.id().equals(m.routeId())).filter(r->{Projection p=project(r,fix.lat(),fix.lon());return p.crossTrack()<=200&&angle(p.heading(),fix.bearing())<=75;}).min(Comparator.comparingDouble(r->{Projection p=project(r,fix.lat(),fix.lon());return p.crossTrack()+2*angle(p.heading(),fix.bearing());})).orElse(null);
    }
    public static Optional<Choice> choose(Manifest m,Fix fix,Instant now,long remainingMs,Set<String> used,Ledger ledger){
        return choose(m,fix,now,remainingMs,used,ledger,m.direction());
    }
    public static Optional<Choice> choose(Manifest m,Fix fix,Instant now,long remainingMs,Set<String> used,Ledger ledger,int stationaryHint){
        if(fix==null || fix.time().isBefore(now.minusSeconds(30)) || fix.time().isAfter(now.plusSeconds(5)) || fix.accuracyMeters()>50)return Optional.empty();
        Route route=resolveRoute(m,fix,stationaryHint);if(route==null)return Optional.empty();Projection bus=project(route,fix.lat(),fix.lon());
        if(bus.crossTrack()>200)return Optional.empty();
        if(fix.speedMps()>1&&(fix.bearing()<0||angle(fix.bearing(),bus.heading())>75))return Optional.empty();
        List<Choice> options=new ArrayList<>();
        for(Campaign c:m.campaigns()){
            if(used.contains(c.id())||(!c.routeId().isEmpty()&&!c.routeId().equals(m.routeId()))||(c.direction()!=-1&&c.direction()!=route.direction()))continue;
            if(now.isBefore(c.startsAt())||!now.isBefore(c.endsAt())||!ledger.allowed(c,now)||m.asset(c.assetId()).durationMs()>remainingMs)continue;
            double d=distance(fix.lat(),fix.lon(),c.lat(),c.lon());if(d>c.radiusMeters())continue;
            Projection target=project(route,c.lat(),c.lon());double ahead=target.along()-bus.along();
            if(target.crossTrack()>c.radiusMeters()||(c.aheadOnly()&&ahead< -25)||ahead>c.aheadMeters())continue;
            options.add(new Choice(c,d,ahead,route.direction()));
        }
        return options.stream().min(Comparator.comparingDouble(Choice::distanceMeters).thenComparing((Choice c)->-c.campaign().priority()).thenComparing(c->c.campaign().id()));
    }
    /** Reserve before playback: a crash may under-deliver, but cannot reset the per-bus cap. */
    public static final class Ledger {
        private final java.nio.file.Path file;private final ZoneId zone;
        private final Map<String,Object> entries;
        public Ledger(java.nio.file.Path file,ZoneId zone)throws java.io.IOException{this.file=file;this.zone=zone;entries=java.nio.file.Files.exists(file)?Json.object(Json.parse(java.nio.file.Files.readString(file))):new LinkedHashMap<>();}
        public synchronized boolean allowed(Campaign c,Instant now){
            if(!entries.containsKey(c.id()))return true;Map<String,Object> e=Json.object(entries.get(c.id()));Instant last=Instant.parse(Json.string(e,"last"));
            if(now.isBefore(last.plusSeconds(c.cooldownSeconds())))return false;
            return !Json.string(e,"day").equals(now.atZone(zone).toLocalDate().toString())||Json.number(e,"count")<c.maxPerDay();
        }
        public synchronized void reserve(Campaign c,Instant now)throws java.io.IOException{
            String day=now.atZone(zone).toLocalDate().toString();int count=0;
            if(entries.containsKey(c.id())){var e=Json.object(entries.get(c.id()));if(day.equals(Json.string(e,"day")))count=(int)Json.number(e,"count");}
            Map<String,Object> copy=new LinkedHashMap<>(entries);copy.put(c.id(),Map.of("day",day,"last",now.toString(),"count",count+1));
            Support.atomic(file,Json.write(copy));entries.clear();entries.putAll(copy);
        }
    }
}
