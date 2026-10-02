package org.eljah.busradio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public final class Support {
    private Support(){}
    public static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String hash(Path p)throws IOException{
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static boolean same(String a,String b){return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static void atomic(Path path,String text)throws IOException{atomic(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static void atomic(Path path,byte[] data)throws IOException{
        Files.createDirectories(path.toAbsolutePath().getParent());Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),".write-",".tmp");
        try{try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining())ch.write(b);ch.force(true);}move(temp,path);}finally{Files.deleteIfExists(temp);}
    }
    public static void move(Path from,Path to)throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic rename required on this filesystem",e);}}
    public static byte[] limited(InputStream in,int max)throws IOException{byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new IOException("Request too large");return bytes;}
    public static boolean inWindow(LocalTime t,LocalTime start,LocalTime end){if(start.equals(end))return true;return start.isBefore(end)?!t.isBefore(start)&&t.isBefore(end):!t.isBefore(start)||t.isBefore(end);}
    public static final class InstanceLock implements AutoCloseable {
        private final FileChannel channel;private final java.nio.channels.FileLock lock;
        public InstanceLock(Path file)throws IOException{Files.createDirectories(file.toAbsolutePath().getParent());channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);java.nio.channels.FileLock acquired;
            try{acquired=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){channel.close();throw new IOException("Another process uses "+file,e);}if(acquired==null){channel.close();throw new IOException("Another process uses "+file);}lock=acquired;}
        public synchronized void close()throws IOException{if(channel.isOpen()){if(lock.isValid())lock.release();channel.close();}}
    }
    public static final class Config {
        final Properties values=new Properties();
        public Config(){}
        public Config(Path p)throws IOException{try(Reader r=Files.newBufferedReader(p)){values.load(r);}}
        public Config set(String k,Object v){values.setProperty(k,v.toString());return this;}
        public String get(String key,String fallback){return System.getenv().getOrDefault("BUSRADIO_"+key.toUpperCase(Locale.ROOT).replace('.','_'),values.getProperty(key,fallback));}
        public String required(String key){String v=get(key,"");if(v.isBlank())throw new IllegalArgumentException("Configure "+key);return v;}
        public int integer(String key,int fallback){return Integer.parseInt(get(key,""+fallback));}
        public boolean bool(String key,boolean fallback){return Boolean.parseBoolean(get(key,""+fallback));}
        public Path path(String key,String fallback){return Path.of(get(key,fallback)).toAbsolutePath();}
    }
}
