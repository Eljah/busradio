package org.eljah.busradio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** All cached media uses PCM signed 16-bit LE, stereo, 44100 Hz. */
public final class Wave {
    public static final int RATE=44100, FRAME_BYTES=4;
    private Wave(){}
    public record Info(long offset,long dataBytes){public long durationMs(){return dataBytes*1000/(RATE*FRAME_BYTES);}}
    public static Info inspect(Path path)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(path.toFile(),"r")){
            if(f.length()<44||f.readInt()!=0x52494646)throw new IOException("Not RIFF WAV");
            f.skipBytes(4);if(f.readInt()!=0x57415645)throw new IOException("Not WAVE");boolean format=false;
            while(f.getFilePointer()+8<=f.length()){
                int chunk=f.readInt();long size=Integer.toUnsignedLong(Integer.reverseBytes(f.readInt())),start=f.getFilePointer();
                if(size>f.length()-start)throw new IOException("Truncated WAV chunk");
                if(chunk==0x666d7420){
                    if(size<16)throw new IOException("Short fmt chunk");
                    int codec=Short.toUnsignedInt(Short.reverseBytes(f.readShort())),channels=Short.toUnsignedInt(Short.reverseBytes(f.readShort()));
                    int rate=Integer.reverseBytes(f.readInt()),byteRate=Integer.reverseBytes(f.readInt()),align=Short.toUnsignedInt(Short.reverseBytes(f.readShort())),bits=Short.toUnsignedInt(Short.reverseBytes(f.readShort()));
                    format=codec==1&&channels==2&&rate==RATE&&byteRate==RATE*4&&align==4&&bits==16;
                }
                if(chunk==0x64617461){if(!format||size%4!=0||size==0)throw new IOException("Expected stereo PCM16/44100 WAV");return new Info(start,size);}
                f.seek(start+size+(size&1));
            }
            throw new IOException("No WAV data");
        }
    }
    public static void header(OutputStream out,long dataBytes)throws IOException{
        ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)(dataBytes+36)).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE*4).putShort((short)4).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)dataBytes);out.write(b.array());
    }
    public static void tone(Path path,double hz,int seconds)throws IOException{
        try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(path))){header(out,(long)seconds*RATE*4);for(int i=0;i<seconds*RATE;i++){double fade=Math.min(1,Math.min(i/(RATE*.02),(seconds*RATE-i)/(RATE*.02)));short s=(short)(Math.sin(2*Math.PI*hz*i/RATE)*3000*fade);out.write(s&255);out.write((s>>8)&255);out.write(s&255);out.write((s>>8)&255);}}
    }
    public static void normalize(Path input,Path output,String ffmpeg)throws Exception{
        try{inspect(input);Files.copy(input,output,StandardCopyOption.REPLACE_EXISTING);return;}catch(IOException notCanonical){/* Use the external decoder only for noncanonical audio. */}
        Path errors=Files.createTempFile(output.getParent(),"decode-",".log");
        try{
            Process p=new ProcessBuilder(ffmpeg,"-nostdin","-v","error","-y","-protocol_whitelist","file,pipe","-format_whitelist","wav,mp3,flac,ogg","-i",input.toString(),"-map_metadata","-1","-vn","-t","901","-ar","44100","-ac","2","-c:a","pcm_s16le","-f","wav",output.toString()).redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!p.waitFor(120,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Audio conversion timed out");}
            if(p.exitValue()!=0)throw new IOException("Unsupported/corrupt audio; decoder exit "+p.exitValue());
            inspect(output);
        }finally{Files.deleteIfExists(errors);}
    }
}
