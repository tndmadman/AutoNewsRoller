package autonewsroller.video;

import autonewsroller.model.*;
import autonewsroller.script.*;
import autonewsroller.visuals.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Offline integrated framing visual + audio + MP4 fixture, NOT a real speech test. */
public final class PoliticalFramingSmoke {
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("framing-smoke-");
        Instant now=Instant.parse("2026-10-17T00:00:00Z");
        Article a=new Article("a","Publisher A","Senate election bill sparks debate over voting rules",
                "https://a.example/1","https://a.example/1",now,now,"","politics",
                "Senators discussed a voting bill.","","en",1,false);
        StoryCluster cluster=new StoryCluster("political-test",a.title(),List.of(a),Set.of("Senate"),"fixture");
        FactPackage facts=new FactPackage(cluster.id,a.title(),"The Senate debated a voting bill.",
                List.of(),List.of(),List.of(Map.of("publisher","Publisher A")),
                1,1,.5,false);
        Map<String,Object>raw=Map.of("politicalRelevance",.4,"overallClassification","uncertain",
                "overallConfidence",.6,"overallWeights",Map.of("left",.3,"center",.4,"right",.3),
                "summary","Analyze the headline's wording without presuming partisan intent.");
        var political=PoliticalVisualData.from(true,Map.of(),raw,"COMPLETE","");
        var evidence=new PoliticalFramingEvidenceBuilder().build(cluster,facts,political);
        NewsScript script=new PoliticalFramingScriptGenerator(null,1).generate(facts,evidence,75,true);
        if(!script.narration().startsWith(a.title()+" "+PoliticalFramingScriptGenerator.TEASER))
            throw new AssertionError("Opening altered");
        VisualPlan plan=new PoliticalFramingVisualPlanner().plan(script,evidence);
        if(evidence.articles().size()!=1||!script.sourceLabels().equals(List.of("Publisher A"))
                ||!"LANGUAGE_HIGHLIGHT".equals(plan.items().get(3).type()))
            throw new AssertionError("Experiment must only expose one article's framing");
        List<Path>images=new ArrayList<>();
        for(VisualPlan.Item item:plan.items()){
            Path image=dir.resolve("frame-"+item.index()+".png");
            new PoliticalFramingCardRenderer().render(item,image,360,640,false);
            Path baseline=dir.resolve("baseline-"+item.index()+".png");
            new CardRenderer().render(item,baseline,360,640);
            BufferedImage real=ImageIO.read(image.toFile()),original=ImageIO.read(baseline.toFile());
            for(int y=570;y<640;y+=10)if(real.getRGB(180,y)!=original.getRGB(180,y))
                throw new AssertionError("Framing card changed TikTok footer");
            images.add(image);
        }
        Path voice=dir.resolve("synthetic-voice.wav"),mixed=dir.resolve("intro-mixed.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error","-f","lavfi",
                "-i","sine=frequency=240:sample_rate=24000","-t","8","-c:a","pcm_s16le",voice.toString()),40);
        new NewsIntroAudioMixer().mix(voice,mixed,"ffmpeg",-4.46);
        // Verify the input sting really increased ~3x (9.54 dB), not merely
        // that the configured gain changed on paper. Use silent speech to
        // avoid interference from the test narrator waveform.
        Path silent=dir.resolve("silent.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","anullsrc=r=24000:cl=mono","-t","3",
                "-c:a","pcm_s16le",silent.toString()),40);
        Path quiet=dir.resolve("sting-former-minus14.wav");
        Path loud=dir.resolve("sting-new-minus4point46.wav");
        new NewsIntroAudioMixer().mix(silent,quiet,"ffmpeg",-14.0);
        new NewsIntroAudioMixer().mix(silent,loud,"ffmpeg",-4.46);
        double ratio=firstHalfSecondRms(loud)/Math.max(1e-8,firstHalfSecondRms(quiet));
        if(ratio<2.80||ratio>3.20)
            throw new AssertionError("Intro news sting input was not approximately 3x louder: "+ratio);
        if(Arrays.equals(Files.readAllBytes(voice),Files.readAllBytes(mixed)))
            throw new AssertionError("Sting did not change voice audio");
        Path mp4=dir.resolve("experiment.mp4");
        VideoRenderer renderer=new VideoRenderer("ffmpeg","ffprobe",360,640,15);
        renderer.render(images,plan.items().stream().map(VisualPlan.Item::duration).toList(),
                mixed,mp4,"x264","sentence",script.narration());
        double seconds=renderer.probeDuration(mp4);
        if(seconds<7.5||seconds>8.5)throw new AssertionError("MP4 duration "+seconds);
        Path opening=dir.resolve("opening.png");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-i",mp4.toString(),"-frames:v","1",opening.toString()),60);
        BufferedImage decoded=ImageIO.read(opening.toFile());
        if(decoded.getWidth()!=360||decoded.getHeight()!=640)throw new AssertionError("Decoded MP4 frame dimensions");
        int rgb=decoded.getRGB(180,620);
        if(((rgb>>16)&255)>70||((rgb>>8)&255)>70||((rgb)&255)>110)
            throw new AssertionError("MP4 lacks the dark bottom safe footer");
        if("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))){
            Path d=Path.of("test-results","v5","framing-exposure");Files.createDirectories(d);
            Files.copy(mp4,d.resolve("synthetic-framing.mp4"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(opening,d.resolve("opening-frame.png"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(images.get(3),d.resolve("headline-framing.png"),StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("FRAMING EXPOSURE SYNTHETIC MP4 PASS: "+seconds+" seconds, original sting, cards, footer; NO REAL TTS");
    }

    private static double firstHalfSecondRms(Path wav)throws Exception{
        try(AudioInputStream stream=AudioSystem.getAudioInputStream(wav.toFile())){
            if(stream.getFormat().getSampleSizeInBits()!=16||stream.getFormat().isBigEndian()
                    ||stream.getFormat().getChannels()!=1)
                throw new AssertionError("Expected 16-bit mono PCM for news sting amplitude test");
            int frames=(int)(stream.getFormat().getSampleRate()*.5);
            byte[]bytes=stream.readNBytes(frames*2);
            double sum=0;int samples=0;
            for(int i=0;i+1<bytes.length;i+=2){
                int value=(short)((bytes[i]&255)|((bytes[i+1]&255)<<8));
                double normalized=value/32768.0;
                sum+=normalized*normalized;samples++;
            }
            return Math.sqrt(sum/Math.max(1,samples));
        }
    }
}
