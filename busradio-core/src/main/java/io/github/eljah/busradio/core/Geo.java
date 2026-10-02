package io.github.eljah.busradio.core;
import java.util.*;
import static io.github.eljah.busradio.core.Model.*;
public final class Geo {
 private Geo(){} private static final double R=6371008.8;
 public record Projection(double alongM,double crossM,double bearing,int segment){}
 public static double distance(Point a,Point b){double x=Math.toRadians(b.lat()-a.lat()),y=Math.toRadians(b.lon()-a.lon());double h=Math.pow(Math.sin(x/2),2)+Math.cos(Math.toRadians(a.lat()))*Math.cos(Math.toRadians(b.lat()))*Math.pow(Math.sin(y/2),2);return 2*R*Math.asin(Math.sqrt(Math.min(1,h)));}
 public static double angle(double a,double b){double d=Math.abs(a-b)%360;return Math.min(d,360-d);}
 public static List<Projection> projections(Route route,Point p){List<Projection> out=new ArrayList<>();double along=0;for(int i=1;i<route.points().size();i++){Point a=route.points().get(i-1),b=route.points().get(i);double scale=Math.cos(Math.toRadians((a.lat()+b.lat()+p.lat())/3));double dx=Math.toRadians(b.lon()-a.lon())*R*scale,dy=Math.toRadians(b.lat()-a.lat())*R,px=Math.toRadians(p.lon()-a.lon())*R*scale,py=Math.toRadians(p.lat()-a.lat())*R;double den=dx*dx+dy*dy;double length=distance(a,b);if(den<.01){along+=length;continue;}double t=Math.max(0,Math.min(1,(px*dx+py*dy)/den));out.add(new Projection(along+t*length,Math.hypot(px-t*dx,py-t*dy),(Math.toDegrees(Math.atan2(dx,dy))+360)%360,i-1));along+=length;}return out;}
 public static Optional<Projection> match(Route route,Fix fix){
  double corridor=route.geometry().equals("SHAPE")?100:250;
  var candidates=projections(route,fix.point()).stream().filter(p->p.crossM()<=corridor&& (fix.speedMps()<2||angle(p.bearing(),fix.course())<=75)).sorted(Comparator.comparingDouble(Projection::crossM)).toList();
  if(candidates.isEmpty())return Optional.empty();Projection best=candidates.getFirst();
  // Do not guess which lap/branch at a crossing when geometry cannot distinguish them.
  if(candidates.stream().skip(1).anyMatch(p->p.crossM()<best.crossM()+15&&Math.abs(p.alongM()-best.alongM())>250))return Optional.empty();return Optional.of(best);
 }
}
