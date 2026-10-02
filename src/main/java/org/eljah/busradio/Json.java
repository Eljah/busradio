package org.eljah.busradio;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Small, dependency-free JSON codec. Network callers must also bound input size. */
public final class Json {
    private Json() {}
    public static Object parse(String text) {
        Parser p = new Parser(text); Object v = p.value(0); p.ws();
        if (p.i != text.length()) throw p.error("Trailing data");
        return v;
    }
    public static <T> T read(String text, Class<T> type) { return type.cast(convert(parse(text), type)); }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object o) {
        if (!(o instanceof Map<?,?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) o;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        if (!(o instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) o;
    }
    public static String string(Map<String,Object> m, String key) { return Objects.toString(Objects.requireNonNull(m.get(key), key)); }
    public static double number(Map<String,Object> m, String key) { return Double.parseDouble(string(m, key)); }
    public static String write(Object value) { StringBuilder b = new StringBuilder(); append(b,value); return b.toString(); }
    private static void append(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String || v instanceof Instant || v instanceof Enum<?>) {
            b.append('"');
            for (char c:v.toString().toCharArray()) switch(c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c<32 || Character.isSurrogate(c)) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c); }
            }
            b.append('"'); return;
        }
        if (v instanceof Number n) { if (!Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Non-finite number"); b.append(n); return; }
        if (v instanceof Boolean) { b.append(v); return; }
        if (v.getClass().isRecord()) {
            Map<String,Object> map=new LinkedHashMap<>();
            try { for (RecordComponent r:v.getClass().getRecordComponents()) map.put(r.getName(),r.getAccessor().invoke(v)); }
            catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
            append(b,map); return;
        }
        if (v instanceof Map<?,?> m) {
            b.append('{'); boolean first=true;
            for (var e:m.entrySet()) { if(!first)b.append(','); first=false; append(b,e.getKey().toString()); b.append(':'); append(b,e.getValue()); }
            b.append('}'); return;
        }
        if (v instanceof Iterable<?> it) {
            b.append('['); boolean first=true;
            for(Object o:it) { if(!first)b.append(','); first=false; append(b,o); }
            b.append(']'); return;
        }
        throw new IllegalArgumentException("Unsupported JSON type: "+v.getClass());
    }
    static Object convert(Object o, Type type) {
        if (type instanceof ParameterizedType p) {
            if(p.getRawType()==List.class) return array(o).stream().map(x->convert(x,p.getActualTypeArguments()[0])).toList();
            throw new IllegalArgumentException("Unsupported generic type");
        }
        Class<?> c=(Class<?>)type;
        Objects.requireNonNull(o,"Missing "+c.getSimpleName());
        if(c==String.class) { if(!(o instanceof String))throw new IllegalArgumentException("Expected string"); return o; }
        if(c==Instant.class) return Instant.parse((String)o);
        if(c==boolean.class || c==Boolean.class) { if(!(o instanceof Boolean))throw new IllegalArgumentException("Expected boolean"); return o; }
        if(c==double.class) return ((Number)o).doubleValue();
        if(c==long.class || c==int.class) {
            Number n=(Number)o; long l=n.longValue();
            if(n.doubleValue()!=l)throw new IllegalArgumentException("Expected integer");
            if(c==long.class)return l; return Math.toIntExact(l);
        }
        if(c.isRecord()) {
            Map<String,Object> m=object(o); RecordComponent[] fields=c.getRecordComponents();
            if(m.size()!=fields.length)throw new IllegalArgumentException("Unexpected or missing fields for "+c.getSimpleName());
            Object[] args=new Object[fields.length]; Class<?>[] types=new Class<?>[fields.length];
            for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();args[i]=convert(m.get(fields[i].getName()),fields[i].getGenericType());}
            try{return c.getDeclaredConstructor(types).newInstance(args);}
            catch(InvocationTargetException e){throw new IllegalArgumentException(e.getCause().getMessage(),e.getCause());}
            catch(ReflectiveOperationException e){throw new IllegalArgumentException(e);}
        }
        throw new IllegalArgumentException("Unsupported target "+type);
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s){this.s=Objects.requireNonNull(s);}
        IllegalArgumentException error(String msg){return new IllegalArgumentException(msg+" at "+i);}
        void ws(){while(i<s.length() && " \r\n\t".indexOf(s.charAt(i))>=0)i++;}
        boolean take(char c){ws();if(i<s.length() && s.charAt(i)==c){i++;return true;}return false;}
        Object value(int depth){
            if(depth>64)throw error("JSON nesting limit"); ws(); if(i==s.length())throw error("Unexpected EOF");
            char c=s.charAt(i);
            if(c=='"')return str();
            if(c=='{'){
                i++; Map<String,Object> m=new LinkedHashMap<>(); if(take('}'))return m;
                do{ws();String k=str();if(!take(':'))throw error("Expected colon");if(m.containsKey(k))throw error("Duplicate key");m.put(k,value(depth+1));}while(take(','));
                if(!take('}'))throw error("Expected closing brace");return m;
            }
            if(c=='['){i++;List<Object>a=new ArrayList<>();if(take(']'))return a;do{a.add(value(depth+1));}while(take(','));if(!take(']'))throw error("Expected closing bracket");return a;}
            for(String word:List.of("true","false","null"))if(s.startsWith(word,i)){i+=word.length();return word.equals("null")?null:word.equals("true");}
            int start=i; if(s.charAt(i)=='-')i++;
            if(i>=s.length())throw error("Bad number");
            if(s.charAt(i)=='0')i++;else{int n=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(n==i)throw error("Bad value");}
            boolean floating=false;
            if(i<s.length()&&s.charAt(i)=='.'){floating=true;i++;int n=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(n==i)throw error("Missing fraction");}
            if(i<s.length()&&"eE".indexOf(s.charAt(i))>=0){floating=true;i++;if(i<s.length()&&"+-".indexOf(s.charAt(i))>=0)i++;int n=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(n==i)throw error("Missing exponent");}
            String number=s.substring(start,i);
            try{if(!floating)return Long.parseLong(number);double d=Double.parseDouble(number);if(!Double.isFinite(d))throw error("Non-finite number");return d;}catch(NumberFormatException e){throw error("Bad number");}
        }
        String str(){
            if(i>=s.length()||s.charAt(i++)!='"')throw error("Expected string");StringBuilder b=new StringBuilder();
            while(i<s.length()){
                char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw error("Control character");
                if(c!='\\'){b.append(c);continue;}if(i==s.length())throw error("Bad escape");
                char e=s.charAt(i++);switch(e){case '"','\\','/'->b.append(e);case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'b'->b.append('\b');case 'f'->b.append('\f');case 'u'->{if(i+4>s.length())throw error("Bad Unicode escape");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){throw error("Bad Unicode escape");}i+=4;}default->throw error("Bad escape");}
            }
            throw error("Unterminated string");
        }
    }
}
