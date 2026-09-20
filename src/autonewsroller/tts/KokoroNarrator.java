package autonewsroller.tts;

import autonewsroller.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class KokoroNarrator implements NarrationEngine {
    private final Path root;
    private final double speed;

    public KokoroNarrator(Path root){this(root,1.0);}
    public KokoroNarrator(Path root,double speed){
        this.root=root;
        this.speed=Math.max(0.75,Math.min(1.25,speed));
    }

    public String name(){return "Kokoro";}

    public NarrationResult narrate(String text,String voice,Path output)throws Exception{
        Files.createDirectories(output.getParent());
        Path txt=output.resolveSibling(output.getFileName()+".txt");
        Files.writeString(txt,text,StandardCharsets.UTF_8);
        String py=Files.isRegularFile(root.resolve(".venv-kokoro/Scripts/python.exe"))
                ?root.resolve(".venv-kokoro/Scripts/python.exe").toString():"python";
        ProcessBuilder b=new ProcessBuilder(
                py,root.resolve("tools/kokoro_tts.py").toString(),
                "--text-file",txt.toString(),
                "--output",output.toString(),
                "--voice",voice,
                "--speed",String.format(Locale.ROOT,"%.3f",speed)
        );
        ProcessRunner.run(b,600);
        if(!Files.isRegularFile(output)||Files.size(output)==0)throw new IllegalStateException("Kokoro did not create WAV");
        Path meta=output.resolveSibling(output.getFileName()+".json");
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("engine","Kokoro");m.put("voice",voice);m.put("speed",speed);m.put("wav",output.toString());
        Json.write(meta,m);
        System.out.println("TTS engine used: Kokoro voice="+voice+" speed="+speed);
        return new NarrationResult("Kokoro",voice,output,meta);
    }
}
