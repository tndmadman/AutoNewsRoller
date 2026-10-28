package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import autonewsroller.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Mandatory local scanner, video frame audit, and content-addressed approval.
 * No detector result, timeout, missing model, or malformed JSON ever means SAFE.
 */
public final class ContentSafetyService {
    public static final String POLICY=ContentSafetyPolicy.VERSION;
    private final Path root;
    private final NewsConfig cfg;
    public ContentSafetyService(Path root,NewsConfig cfg){this.root=root;this.cfg=cfg;}

    public Map<String,Object> inspectImage(Path image){
        return scan("image",image);
    }
    public Map<String,Object> inspectVideo(Path video){
        return scan("video",video);
    }
    public Map<String,Object> requireSafeImage(Path image){
        Map<String,Object>r=inspectImage(image);
        if(!"SAFE".equals(r.get("status")))throw new IllegalStateException("CONTENT_SAFETY_IMAGE_"+r.get("status")+": "+r.getOrDefault("reason","unknown"));
        return r;
    }

    private Map<String,Object> scan(String kind,Path input){
        try{
            if(!Files.isRegularFile(input)||Files.size(input)==0)throw new IOException("empty or missing input");
            String beforeHash=hash(input);
            int limit=kind.equals("video")?cfg.getInt("contentSafetyVideoTimeoutSeconds",3600):
                    cfg.getInt("contentSafetyImageTimeoutSeconds",120);
            // Independent, mandatory models. Neither can be skipped by a
            // worker configuration flag. Any model failure fails the whole job.
            Map<String,Object>anatomy=runDetector("tools/content_safety_scan.py",kind,input,limit);
            Map<String,Object>context=runDetector("tools/content_context_scan.py",kind,input,limit);
            if(!"NudeNet".equals(anatomy.get("detector"))||!"3.4.2".equals(anatomy.get("version")))
                throw new IOException("unrecognized exposed-anatomy detector");
            if(!"OpenNSFW2".equals(context.get("detector"))||!"0.19.0".equals(context.get("version")))
                throw new IOException("unrecognized independent context classifier");
            String a=String.valueOf(anatomy.getOrDefault("status",""));
            String c=String.valueOf(context.getOrDefault("status",""));
            if(!Set.of("SAFE","UNSAFE","REVIEW_REQUIRED").contains(a)
                    ||!Set.of("SAFE","UNSAFE","REVIEW_REQUIRED").contains(c))
                throw new IOException("unrecognized safety decision");
            if(kind.equals("video")){
                long frames=frameCount(anatomy,"framesDecoded");
                if(frames<1||frameCount(anatomy,"framesInspected")!=frames
                        ||frameCount(anatomy,"framesExpected")!=frames
                        ||frameCount(context,"framesDecoded")!=frames
                        ||frameCount(context,"framesInspected")!=frames
                        ||frameCount(context,"framesExpected")!=frames)
                    throw new IOException("independent detectors failed complete-frame coverage");
                if("SAFE".equals(a)&&(!Boolean.TRUE.equals(anatomy.get("disclosureVerified"))
                        ||frameCount(anatomy,"disclosureFramesVerified")!=frames))
                    throw new IOException("missing final-frame AI disclosure");
            }
            String afterHash=hash(input);
            if(!beforeHash.equals(afterHash))throw new IOException("content changed during safety inspection");
            Map<String,Object>result=new LinkedHashMap<>(anatomy);
            result.put("contextAudit",context);
            result.put("status","UNSAFE".equals(a)||"UNSAFE".equals(c)?"UNSAFE":
                    ("REVIEW_REQUIRED".equals(a)||"REVIEW_REQUIRED".equals(c)?"REVIEW_REQUIRED":"SAFE"));
            result.put("sha256",afterHash);
            result.put("policyVersion",POLICY);
            return result;
        }catch(Exception e){return failure("SCANNER_UNAVAILABLE",crop(e.getMessage(),320));}
    }

    private static long frameCount(Map<String,Object>result,String name)throws IOException{
        Object value=result.get(name);
        if(!(value instanceof Number n)||n.longValue()<0)
            throw new IOException("missing or invalid frame coverage: "+name);
        return n.longValue();
    }

    private Map<String,Object> runDetector(String scriptName,String kind,Path input,int limit)throws Exception{
        String python=cfg.get("contentSafetyPython","python");
        Path script=root.resolve(scriptName).toAbsolutePath();
        if(!Files.isRegularFile(script))throw new IOException("missing mandatory scanner: "+scriptName);
        Path log=Files.createTempFile("anr-content-scan-", ".stderr.log");
        try{
            ProcessBuilder pb=new ProcessBuilder(python,script.toString(),"--"+kind,input.toAbsolutePath().toString());
            pb.directory(root.toAbsolutePath().toFile());
            pb.environment().put("PYTHONUNBUFFERED","1");
            pb.environment().put("TF_CPP_MIN_LOG_LEVEL","2");
            pb.redirectError(log.toFile());
            Process process=pb.start();
            if(!process.waitFor(Math.max(1,limit),TimeUnit.SECONDS)){
                process.destroyForcibly();
                process.waitFor(15,TimeUnit.SECONDS);
                throw new IOException("mandatory scanner timed out: "+scriptName);
            }
            String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            if(process.exitValue()!=0){
                String stderr=Files.readString(log);
                throw new IOException("mandatory scanner failed "+scriptName+" exit "+process.exitValue()+
                        ": "+crop(stderr,240));
            }
            Map<String,Object>result=Json.object(Json.parse(stdout));
            if(!Set.of("SAFE","UNSAFE","REVIEW_REQUIRED").contains(String.valueOf(result.get("status"))))
                throw new IOException("malformed scanner decision: "+scriptName);
            return result;
        }finally{
            Files.deleteIfExists(log);
        }
    }
    private static String crop(String s,int max){if(s==null)return "unknown";return s.length()>max?s.substring(0,max):s;}
    private static Map<String,Object>failure(String status,String reason){
        return new LinkedHashMap<>(Map.of("status",status,"reason",reason,"policyVersion",POLICY));
    }
    public static String hash(Path file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)){
            byte[]b=new byte[131072];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Call ONLY after rendering, before copying into a publishable directory. */
    public Map<String,Object> approve(Path video,List<Path>assets,Path report)throws Exception{
        List<Map<String,Object>>inputs=new ArrayList<>();
        for(Path asset:assets){
            if(asset==null)continue;
            inputs.add(Map.of("path",asset.toAbsolutePath().toString(),"sha256",hash(asset)));
        }
        Map<String,Object>frames=inspectVideo(video);
        Map<String,Object>manifest=new LinkedHashMap<>();
        manifest.put("policyVersion",POLICY);
        manifest.put("videoSha256",hash(video));
        manifest.put("inputs",inputs);
        manifest.put("finalVideoAudit",frames);
        manifest.put("createdAt",Instant.now().toString());
        manifest.put("status","SAFE".equals(frames.get("status"))?"APPROVED_FOR_EXPORT":String.valueOf(frames.get("status")));
        Json.write(report,manifest);
        if(!"APPROVED_FOR_EXPORT".equals(manifest.get("status")))
            throw new IllegalStateException("CONTENT_SAFETY_EXPORT_BLOCKED: "+frames.get("status")+" "+frames.getOrDefault("reason",""));
        verify(video,manifest,true);
        return manifest;
    }

    /** Revalidate current on-disk content, rather than trusting a historical name or approval flag. */
    public static void verify(Path video,Map<String,Object>manifest,boolean checkInputs)throws Exception{
        if(!"APPROVED_FOR_EXPORT".equals(manifest.get("status"))
                ||!POLICY.equals(manifest.get("policyVersion")))
            throw new IllegalStateException("CONTENT_SAFETY_APPROVAL_MISSING_OR_STALE");
        if(!hash(video).equals(manifest.get("videoSha256")))
            throw new IllegalStateException("CONTENT_SAFETY_VIDEO_HASH_CHANGED");
        Map<String,Object>frames=Json.object(manifest.get("finalVideoAudit"));
        if(!"SAFE".equals(frames.get("status"))||!POLICY.equals(frames.get("policyVersion")))
            throw new IllegalStateException("CONTENT_SAFETY_FINAL_AUDIT_MISSING");
        if(!(frames.get("framesDecoded") instanceof Number f) || f.longValue()<1
                ||!(frames.get("framesInspected") instanceof Number n) || f.longValue()!=n.longValue()
                ||!(frames.get("framesExpected") instanceof Number expected) || f.longValue()!=expected.longValue()
                ||!(frames.get("disclosureFramesVerified") instanceof Number disclosed) || f.longValue()!=disclosed.longValue()
                ||!Boolean.TRUE.equals(frames.get("disclosureVerified")))
            throw new IllegalStateException("CONTENT_SAFETY_INCOMPLETE_FRAME_AUDIT");
        if(!"NudeNet".equals(frames.get("detector"))||!"3.4.2".equals(frames.get("version"))
                ||!"every decoded frame; full and three overlapping crops".equals(frames.get("coverage"))
                ||!(frames.get("threshold") instanceof Number nt)||nt.doubleValue()>0.25)
            throw new IllegalStateException("CONTENT_SAFETY_DETECTOR_EVIDENCE_MISSING");
        if(!(frames.get("contextAudit") instanceof Map<?,?>))
            throw new IllegalStateException("CONTENT_SAFETY_INDEPENDENT_CONTEXT_AUDIT_MISSING");
        Map<String,Object>context=Json.object(frames.get("contextAudit"));
        if(!"SAFE".equals(context.get("status"))||!"OpenNSFW2".equals(context.get("detector"))
                ||!"0.19.0".equals(context.get("version"))
                ||!(context.get("reviewThreshold") instanceof Number rt)||rt.doubleValue()>0.20
                ||!(context.get("hardBlockThreshold") instanceof Number ht)||ht.doubleValue()>0.70
                ||!"every decoded frame; complete frame; upper/lower crops on still images".equals(context.get("coverage"))
                ||!(context.get("framesDecoded") instanceof Number cf)||cf.longValue()!=f.longValue()
                ||!(context.get("framesInspected") instanceof Number ci)||ci.longValue()!=f.longValue()
                ||!(context.get("framesExpected") instanceof Number ce)||ce.longValue()!=f.longValue())
            throw new IllegalStateException("CONTENT_SAFETY_INDEPENDENT_CONTEXT_AUDIT_MISSING");
        if(!manifest.get("videoSha256").equals(frames.get("sha256")))
            throw new IllegalStateException("CONTENT_SAFETY_SCANNED_HASH_MISMATCH");
        if(checkInputs){
            Object inputs=manifest.get("inputs");
            if(!(inputs instanceof List<?>))throw new IllegalStateException("CONTENT_SAFETY_INPUT_HASHES_MISSING");
            for(Object entry:(List<?>)inputs){
                Map<String,Object>i=Json.object(entry);
                if(!hash(Path.of(String.valueOf(i.get("path")))).equals(i.get("sha256")))
                    throw new IllegalStateException("CONTENT_SAFETY_INPUT_HASH_CHANGED");
            }
        }
    }
}