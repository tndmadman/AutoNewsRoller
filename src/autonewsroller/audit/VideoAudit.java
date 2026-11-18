package autonewsroller.audit;

import autonewsroller.util.Json;
import autonewsroller.video.FfmpegRunner;
import java.nio.file.*;
import java.util.*;

/** Validates the encoded MP4, not just the narration WAV or renderer settings. */
public final class VideoAudit {
    private static final double FPS_TOLERANCE=0.05;
    private static final double AV_SYNC_TOLERANCE_SECONDS=0.35;
    private final String ffprobe,ffmpeg;

    public VideoAudit(String ffprobe){this(ffprobe,"ffmpeg");}
    public VideoAudit(String ffprobe,String ffmpeg){this.ffprobe=ffprobe;this.ffmpeg=ffmpeg;}

    /** Compatibility overload. Production passes its configured frame rate explicitly. */
    public Map<String,Object>audit(Path video,Path audio,int width,int height)throws Exception{
        return audit(video,audio,width,height,30);
    }

    public Map<String,Object>audit(Path video,Path audio,int width,int height,int expectedFps)throws Exception{
        if(width<1||height<1||expectedFps<1)
            throw new IllegalArgumentException("Invalid media audit expectations");
        if(!Files.isRegularFile(video)||Files.size(video)<1024)
            throw new IllegalStateException("Video missing or empty");
        if(!Files.isRegularFile(audio)||Files.size(audio)<128)
            throw new IllegalStateException("Source audio missing or empty");

        Map<String,Object>probe=probe(video);
        double duration=number(Json.object(probe.get("format")).get("duration"));
        if(!Double.isFinite(duration)||duration<=0)
            throw new IllegalStateException("Invalid encoded MP4 duration");

        Map<String,Object>videoStream=null,audioStream=null;
        Object rawStreams=probe.get("streams");
        if(!(rawStreams instanceof List<?> streams))
            throw new IllegalStateException("No decodable media streams");
        for(Object raw:streams){
            if(!(raw instanceof Map<?,?>))continue;
            Map<String,Object>stream=Json.object(raw);
            if("video".equals(stream.get("codec_type"))&&videoStream==null)videoStream=stream;
            if("audio".equals(stream.get("codec_type"))&&audioStream==null)audioStream=stream;
        }
        if(videoStream==null||audioStream==null)
            throw new IllegalStateException("MP4 requires both video and audio streams");
        int actualWidth=integer(videoStream.get("width")),actualHeight=integer(videoStream.get("height"));
        if(actualWidth!=width||actualHeight!=height)
            throw new IllegalStateException("Wrong MP4 resolution: "+actualWidth+"x"+actualHeight+
                    ", expected "+width+"x"+height);
        if(blank(videoStream.get("codec_name"))||blank(audioStream.get("codec_name")))
            throw new IllegalStateException("Encoded stream codec information is missing");

        double averageFps=rate(videoStream.get("avg_frame_rate"));
        double nominalFps=rate(videoStream.get("r_frame_rate"));
        if(!Double.isFinite(averageFps)||!Double.isFinite(nominalFps)
                ||Math.abs(averageFps-expectedFps)>FPS_TOLERANCE
                ||Math.abs(nominalFps-expectedFps)>FPS_TOLERANCE
                ||Math.abs(averageFps-nominalFps)>FPS_TOLERANCE)
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Incorrect or variable MP4 frame rate: avg=%.4f nominal=%.4f expected=%d",
                    averageFps,nominalFps,expectedFps));

        double videoSeconds=number(videoStream.get("duration"));
        double audioSeconds=number(audioStream.get("duration"));
        if(!Double.isFinite(videoSeconds)||videoSeconds<=0
                ||!Double.isFinite(audioSeconds)||audioSeconds<=0)
            throw new IllegalStateException("Encoded MP4 audio/video stream duration is missing");
        double difference=Math.abs(videoSeconds-audioSeconds);
        if(difference>AV_SYNC_TOLERANCE_SECONDS)
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "MP4 audio/video duration mismatch: video=%.3fs audio=%.3fs",
                    videoSeconds,audioSeconds));

        // Inspect encoded output audio. Checking only the input WAV can approve a silent MP4.
        String volume=FfmpegRunner.run(List.of(ffmpeg,"-hide_banner","-nostdin","-i",
                video.toString(),"-map","0:a:0","-vn","-af","volumedetect",
                "-f","null","-"),90).toLowerCase(Locale.ROOT);
        if(!volume.contains("mean_volume:")||volume.contains("mean_volume: -inf"))
            throw new IllegalStateException("Encoded MP4 audio is silent or could not be measured");

        Map<String,Object>sourceProbe=probe(audio);
        double sourceSeconds=number(Json.object(sourceProbe.get("format")).get("duration"));
        if(!Double.isFinite(sourceSeconds)||sourceSeconds<=0)
            throw new IllegalStateException("Cannot measure source narration audio");
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status","approved");
        result.put("videoDuration",duration);
        result.put("resolution",actualWidth+"x"+actualHeight);
        result.put("videoCodec",videoStream.get("codec_name"));
        result.put("audioCodec",audioStream.get("codec_name"));
        result.put("videoStreamDuration",videoSeconds);
        result.put("audioStreamDuration",audioSeconds);
        result.put("sourceAudioDuration",sourceSeconds);
        result.put("avDurationDifferenceSeconds",difference);
        result.put("avSyncToleranceSeconds",AV_SYNC_TOLERANCE_SECONDS);
        result.put("averageFps",averageFps);
        result.put("nominalFps",nominalFps);
        result.put("configuredFps",expectedFps);
        result.put("cfrVerified",true);
        result.put("audioNonSilent",true);
        return result;
    }

    private Map<String,Object>probe(Path path)throws Exception{
        String response=FfmpegRunner.run(List.of(ffprobe,"-v","error",
                "-show_entries","format=duration:stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate,duration",
                "-of","json",path.toString()),30);
        return Json.object(Json.parse(response));
    }

    private static boolean blank(Object value){return value==null||String.valueOf(value).isBlank();}
    private static int integer(Object value){
        return value instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(value));
    }
    private static double number(Object value){
        if(value==null)return Double.NaN;
        try{return Double.parseDouble(String.valueOf(value));}
        catch(NumberFormatException bad){return Double.NaN;}
    }
    private static double rate(Object value){
        if(value==null)return Double.NaN;
        String text=String.valueOf(value);
        try{
            if(text.contains("/")){
                String[]parts=text.split("/",2);
                double denominator=Double.parseDouble(parts[1]);
                return denominator==0?Double.NaN:Double.parseDouble(parts[0])/denominator;
            }
            return Double.parseDouble(text);
        }catch(NumberFormatException bad){return Double.NaN;}
    }
}
