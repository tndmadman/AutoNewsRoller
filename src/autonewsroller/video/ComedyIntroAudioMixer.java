package autonewsroller.video;

import java.nio.file.Path;
import java.util.*;

/** Original synthesized short ba-dum-tss; no licensed stand-up sample. Speech starts at zero. */
public final class ComedyIntroAudioMixer {
    public static final String STING_ID="original-synthetic-comedy-rimshot-v1";
    public Path mix(Path voice,Path mixed,String ffmpeg,double gainDb)throws Exception{
        String level=String.format(Locale.ROOT,"%.2fdB",gainDb);
        String filter="[1:a]volume="+level+",afade=t=out:st=0.09:d=0.07[drum1];"
                +"[2:a]adelay=145|145,volume="+level+",afade=t=out:st=0.1:d=0.08[drum2];"
                +"[3:a]adelay=310|310,highpass=f=1900,volume="+level+
                ",afade=t=out:st=0.05:d=0.24[cymbal];"
                +"[0:a][drum1][drum2][cymbal]amix=inputs=4:duration=first:normalize=0,"
                +"alimiter=limit=0.90:attack=5:release=80[out]";
        FfmpegRunner.run(List.of(ffmpeg,"-y","-hide_banner","-loglevel","error",
                "-i",voice.toString(),
                "-f","lavfi","-i","sine=frequency=130:duration=0.18:sample_rate=24000",
                "-f","lavfi","-i","sine=frequency=175:duration=0.20:sample_rate=24000",
                "-f","lavfi","-i","anoisesrc=color=white:duration=0.35:sample_rate=24000",
                "-filter_complex",filter,"-map","[out]","-c:a","pcm_s16le",mixed.toString()),100);
        return mixed;
    }
}
