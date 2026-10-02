package io.github.eljah.busradio.core;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class FilesEx {
 private FilesEx(){}
 public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static String sha(Path file)throws IOException{try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static boolean same(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static void atomic(Path path,byte[] bytes)throws IOException{
  Path dir=path.toAbsolutePath().getParent();Files.createDirectories(dir);Path tmp=Files.createTempFile(dir,".write-",".tmp");
  try{try(FileChannel f=FileChannel.open(tmp,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())f.write(b);f.force(true);}Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(FileChannel f=FileChannel.open(dir,StandardOpenOption.READ)){f.force(true);}catch(IOException|UnsupportedOperationException ignored){/* Some filesystems cannot fsync a directory. */}}finally{Files.deleteIfExists(tmp);}
 }
 public static void json(Path path,Object o)throws IOException{atomic(path,Json.write(o).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static Map<String,Object> readObject(Path p)throws IOException{return Json.obj(Json.parse(Files.readString(p)));}
 public static String id(String id){if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("Invalid ID");return id;}
 public static String hash(String s){if(s==null||!s.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid SHA-256");return s;}
 public static byte[] limited(InputStream in,int max)throws IOException{byte[] b=in.readNBytes(max+1);if(b.length>max)throw new IllegalArgumentException("Request too large");return b;}
}
