package org.eljah.busradio;

import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import org.eljah.busradio.Support.Config;

public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception{
        if(args.length==0){System.out.println("BusRadio Java 21\n  server [config/server.properties]\n  node [config/node.properties]\n  demo [fresh-directory] [port]\n  token\n  import-buscrawl <routes.json> <converted-routes.json>");return;}
        switch(args[0]){
            case "token"->System.out.println(Support.token());
            case "demo"->Demo.run(Path.of(args.length>1?args[1]:"var/demo-"+System.currentTimeMillis()),args.length>2?Integer.parseInt(args[2]):8080);
            case "server"->{System.setProperty("sun.net.httpserver.maxReqTime","300");System.setProperty("sun.net.httpserver.maxRspTime","300");Server server=new Server(new Config(Path.of(args.length>1?args[1]:"config/server.properties")));Runtime.getRuntime().addShutdownHook(new Thread(server::close));System.out.println("BusRadio server listening on port "+server.port());new CountDownLatch(1).await();}
            case "node"->{Node node=new Node(new Config(Path.of(args.length>1?args[1]:"config/node.properties")));Runtime.getRuntime().addShutdownHook(new Thread(node::close));System.out.println("BusRadio node started; see its status.json and event outbox");new CountDownLatch(1).await();}
            case "import-buscrawl"->{if(args.length!=3)throw new IllegalArgumentException("import-buscrawl requires input and output paths");var routes=Buscrawl.routes(Files.readString(Path.of(args[1])));Support.atomic(Path.of(args[2]),Json.write(routes));System.out.println("Imported "+routes.size()+" directed stop chains");}
            default->throw new IllegalArgumentException("Unknown command: "+args[0]);
        }
    }
}
