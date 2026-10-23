package autonewsroller.video;

import java.nio.file.Path;
import java.util.*;

/** Two original synthesized newsroom tones are mixed at t=0, underneath speech. */
public final class NewsIntroAudioMixer {
    public static final String STING_ID="synthetic-two-tone-news-sting-v1";
    public Path mix(Path voice,Path mixed,String ffmpeg,double gainDb)throws Exception{
        // Input 0 starts at timestamp zero. Neither trimming nor delay is applied to speech.
        String filter="[1:a]volume="+String.format(Locale.ROOT,"%.2fdB",gainDb)
                +",afade=t=out:st=0.7:d=0.6[a];"
                +"[2:a]volume="+String.format(Locale.ROOT,"%.2fdB",gainDb-5)
                +",afade=t=out:st=0.7:d=0.6[b];"
                +"[0:a][a][b]amix=inputs=3:duration=first:normalize=0,"
                +"alimiter=limit=0.92:attack=5:release=50[out]";
        FfmpegRunner.run(List.of(ffmpeg,"-y","-hide_banner","-loglevel","error",
                "-i",voice.toString(),"-f","lavfi","-i","sine=frequency=784:duration=1.3:sample_rate=24000",
                "-f","lavfi","-i","sine=frequency=1046:duration=1.3:sample_rate=24000",
                "-filter_complex",filter,"-map","[out]","-c:a","pcm_s16le",mixed.toString()),100);
        return mixed;
    }
}
