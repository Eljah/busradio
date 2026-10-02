package io.github.eljah.busradio.tests;
import java.nio.file.*;
/** Test child process only: records stdin without touching radio hardware. */
public final class PcmProbe {
 public static void main(String[] args)throws Exception{try(var out=Files.newOutputStream(Path.of(args[0]))){System.in.transferTo(out);}}
}
