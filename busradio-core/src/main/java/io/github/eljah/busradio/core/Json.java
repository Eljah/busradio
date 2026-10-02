package io.github.eljah.busradio.core;

import java.lang.reflect.RecordComponent;
import java.util.*;

/** Deliberately small, strict JSON codec. No polymorphic deserialization or third-party runtime. */
public final class Json {
 private Json() {}
 public static Object parse(String input) {
  Parser p=new Parser(input); Object value=p.value(0); p.ws();
  if(p.i!=input.length()) throw new IllegalArgumentException("Trailing JSON data"); return value;
 }
 @SuppressWarnings("unchecked") public static Map<String,Object> obj(Object o) {
  if(!(o instanceof Map<?,?>)) throw new IllegalArgumentException("JSON object required"); return (Map<String,Object>)o;
 }
 @SuppressWarnings("unchecked") public static List<Object> arr(Object o) {
  if(!(o instanceof List<?>)) throw new IllegalArgumentException("JSON array required"); return (List<Object>)o;
 }
 public static String str(Map<String,Object> m,String k){Object o=m.get(k);if(!(o instanceof String))throw new IllegalArgumentException("String required: "+k);return (String)o;}
 public static String str(Map<String,Object> m,String k,String d){return m.containsKey(k)?str(m,k):d;}
 public static double num(Map<String,Object> m,String k){Object o=m.get(k);if(!(o instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Number required: "+k);return n.doubleValue();}
 public static double num(Map<String,Object> m,String k,double d){return m.containsKey(k)?num(m,k):d;}
 public static long lng(Map<String,Object> m,String k){double n=num(m,k);if(n!=Math.rint(n)||Math.abs(n)>9_007_199_254_740_991d)throw new IllegalArgumentException("Safe integer required: "+k);return (long)n;}
 public static boolean bool(Map<String,Object> m,String k,boolean d){Object o=m.get(k);if(o==null)return d;if(!(o instanceof Boolean))throw new IllegalArgumentException("Boolean required: "+k);return (Boolean)o;}
 public static String write(Object value){StringBuilder b=new StringBuilder();emit(value,b,0);return b.toString();}
 private static void emit(Object v,StringBuilder b,int depth){
  if(depth>80)throw new IllegalArgumentException("JSON nesting limit");
  if(v==null){b.append("null");return;}
  if(v instanceof String||v instanceof Enum<?>||v instanceof java.time.temporal.TemporalAccessor){quote(v.toString(),b);return;}
  if(v instanceof Number n){if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Non-finite JSON number");b.append(n);return;}
  if(v instanceof Boolean){b.append(v);return;}
  if(v instanceof Map<?,?> m){b.append('{');boolean first=true;for(var e:m.entrySet()){if(!first)b.append(',');first=false;quote(e.getKey().toString(),b);b.append(':');emit(e.getValue(),b,depth+1);}b.append('}');return;}
  if(v instanceof Iterable<?> a){b.append('[');boolean first=true;for(Object e:a){if(!first)b.append(',');first=false;emit(e,b,depth+1);}b.append(']');return;}
  if(v.getClass().isRecord()){Map<String,Object> m=new LinkedHashMap<>();try{for(RecordComponent c:v.getClass().getRecordComponents())m.put(c.getName(),c.getAccessor().invoke(v));}catch(ReflectiveOperationException e){throw new IllegalArgumentException(e);}emit(m,b,depth+1);return;}
  throw new IllegalArgumentException("Unsupported JSON value "+v.getClass());
 }
 private static void quote(String s,StringBuilder b){b.append('"');for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\b'->b.append("\\b");case '\f'->b.append("\\f");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}b.append('"');}
 private static final class Parser {
  final String s;int i;Parser(String s){this.s=Objects.requireNonNull(s);}
  void ws(){while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0)i++;}
  IllegalArgumentException error(){return new IllegalArgumentException("Invalid JSON at offset "+i);}
  Object value(int d){ws();if(d>80||i>=s.length())throw error();char c=s.charAt(i);
   if(c=='"')return string();
   if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();ws();if(take('}'))return m;do{ws();if(i>=s.length()||s.charAt(i)!='"')throw error();String k=string();ws();if(!take(':')||m.containsKey(k))throw error();m.put(k,value(d+1));ws();if(take('}'))return m;}while(take(','));throw error();}
   if(c=='['){i++;List<Object>a=new ArrayList<>();ws();if(take(']'))return a;do{a.add(value(d+1));ws();if(take(']'))return a;}while(take(','));throw error();}
   if(s.startsWith("true",i)){i+=4;return true;}if(s.startsWith("false",i)){i+=5;return false;}if(s.startsWith("null",i)){i+=4;return null;}
   int start=i;if(take('-')&&i==s.length())throw error();if(!take('0')){if(i>=s.length()||s.charAt(i)<'1'||s.charAt(i)>'9')throw error();while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}
   if(take('.')){int n=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(i==n)throw error();}
   if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){i++;if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;int n=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(i==n)throw error();}
   try{String n=s.substring(start,i);if(n.indexOf('.')<0&&n.indexOf('e')<0&&n.indexOf('E')<0)return Long.parseLong(n);double v=Double.parseDouble(n);if(!Double.isFinite(v))throw error();return v;}catch(NumberFormatException e){throw error();}
  }
  boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
  String string(){i++;StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw error();if(c!='\\'){b.append(c);continue;}if(i==s.length())throw error();char e=s.charAt(i++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw error();try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException x){throw error();}i+=4;}default->throw error();}}throw error();}
 }
}
