package autonewsroller.video;

import autonewsroller.model.*;
import autonewsroller.visuals.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

/** Offline synthetic short meme video. Does NOT exercise actual Ollama, TTS or GPU. */
public final class MemeSmoke {
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("autonews-meme-smoke-");
        Path voice=dir.resolve("synthetic-voice.wav"),mixed=dir.resolve("comedy-mixed.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","sine=frequency=260:sample_rate=24000",
                "-t","4","-c:a","pcm_s16le",voice.toString()),60);
        new ComedyIntroAudioMixer().mix(voice,mixed,"ffmpeg",-10);
        if(Arrays.equals(Files.readAllBytes(voice),Files.readAllBytes(mixed)))
            throw new AssertionError("Comedy sting did not alter narration audio");
        if(ComedyIntroAudioMixer.STING_ID.equals(NewsIntroAudioMixer.STING_ID))
            throw new AssertionError("Comedy intro cannot use news sound identity");
        try(AudioInputStream audio=AudioSystem.getAudioInputStream(mixed.toFile())){
            byte[]bytes=audio.readAllBytes();
            for(int i=0;i+1<bytes.length;i+=2){
                int x=(short)((bytes[i]&255)|((bytes[i+1]&255)<<8));
                if(x>=32767||x<=-32768)throw new AssertionError("Comedy audio clipped");
            }
        }
        var items=List.of(
                new VisualPlan.Item(0,"MEME_HOOK","THE CITY DID WHAT?","A surprising headline",1.5,
                        "Satirical cartoon of a city council buying novelty traffic signs",
                        Map.of("segmentIndex",0,"factIds",List.of("F1"))),
                new VisualPlan.Item(1,"MEME_BEAT","THE RECEIPTS","A short punchline",2.5,
                        "Satirical illustration of novelty street signs",
                        Map.of("segmentIndex",1,"factIds",List.of("F1"))));
        List<Path>images=new ArrayList<>();
        for(var item:items){
            Path p=dir.resolve("meme-"+item.index()+".png");
            new MemeCardRenderer().render(item,p,360,640);
            images.add(p);
        }
        BufferedImage first=ImageIO.read(images.get(0).toFile());
        if(first==null||first.getWidth()!=360||first.getHeight()!=640)
            throw new AssertionError("Meme frame geometry");
        Path mp4=dir.resolve("synthetic-meme.mp4");
        VideoRenderer renderer=new VideoRenderer("ffmpeg","ffprobe",360,640,15);
        renderer.render(images,List.of(1.5,2.5),mixed,mp4,"x264","off",
                "The city did what? That's one way to buy traffic signs.");
        double seconds=renderer.probeDuration(mp4);
        if(seconds<3.5||seconds>4.5)throw new AssertionError("Short meme runtime "+seconds);
        Path decoded=dir.resolve("decoded-opening.png");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-i",mp4.toString(),"-frames:v","1",decoded.toString()),60);
        BufferedImage shot=ImageIO.read(decoded.toFile());
        if(shot==null||shot.getWidth()!=360||shot.getHeight()!=640)
            throw new AssertionError("Decoded frame missing or wrong dimensions");
        // The opaque bottom safety area is distinct from an empty/black frame.
        if((shot.getRGB(180,620)&0xFFFFFF)==(shot.getRGB(180,180)&0xFFFFFF))
            throw new AssertionError("Meme first frame missing visible hook region");
        if("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))){
            Path artifact=Path.of("test-results","v5","meme");
            Files.createDirectories(artifact);
            Files.copy(mp4,artifact.resolve("synthetic-meme.mp4"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(decoded,artifact.resolve("opening-frame.png"),StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("MEME SYNTHETIC MP4 PASS: "+seconds+
                " seconds, own comedy sting, frame-zero hook and footer; NO REAL GPU OR TTS");
    }
}
