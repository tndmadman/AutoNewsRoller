package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import autonewsroller.util.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Optional inspection of an existing local-only procedural draft. Never creates
 * an export manifest, uploads media, or changes the original draft's status.
 */
public final class LocalDraftInspection {
    private LocalDraftInspection(){}

    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException(
                "Usage: LocalDraftInspection <controller URL> <worker-local draft MP4>");
        Path root=Path.of("").toAbsolutePath();
        Path draft=validate(root,Path.of(args[1]));
        requireScanningEnabled(args[0]);
        Map<String,Object>meta=Json.object(Json.read(draft.resolveSibling(draft.getFileName()+".json")));
        String expected=String.valueOf(meta.get("videoSha256"));
        Map<String,Object>detectorReport=new ContentSafetyService(root,NewsConfig.load(root)).inspectVideo(draft);
        if(!expected.equals(ContentSafetyService.hash(draft)))
            throw new IllegalStateException("Local draft changed during inspection");
        String status=String.valueOf(detectorReport.getOrDefault("status","SCANNER_UNAVAILABLE"));
        Map<String,Object>report=new LinkedHashMap<>();
        report.put("inspectionState","SCANNER_UNAVAILABLE".equals(status)?"SCANNER_UNAVAILABLE":"COMPLETE");
        report.put("detectorStatus",status);
        report.put("videoSha256",expected);
        report.put("inspectedAt",Instant.now().toString());
        report.put("publicationAllowed",false);
        report.put("purpose","LOCAL_DRAFT_DIAGNOSTIC_ONLY");
        report.put("detectorReport",detectorReport);
        Path out=draft.resolveSibling(draft.getFileName()+".inspection.json");
        Json.write(out,report);
        System.out.println("LOCAL DRAFT INSPECTION: "+status+"; export remains prohibited. Report: "+out);
    }

    private static void requireScanningEnabled(String controller)throws Exception{
        URI base=URI.create(controller.replaceAll("/+$",""));
        if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null
                ||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)
            throw new IllegalArgumentException("Use the trusted controller's HTTP(S) address");
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+"/api/settings/content-safety"))
                .GET().timeout(Duration.ofSeconds(15));
        String token=System.getenv("AUTONEWS_TOKEN");
        if(token!=null&&!token.isBlank())request.header("X-AutoNews-Token",token);
        HttpResponse<String> response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||!Boolean.TRUE.equals(
                Json.object(Json.parse(response.body())).get("contentSafetyScanningEnabled")))
            throw new IllegalStateException("Restore scanning on the authenticated controller first");
    }

    static Path validate(Path root,Path input)throws Exception{
        Path realRoot=root.toRealPath();
        Path base=realRoot.resolve("output/remote_worker").toRealPath();
        if(!base.startsWith(realRoot))throw new IllegalArgumentException("Worker output directory escapes the installation");
        Path candidate=(input.isAbsolute()?input:realRoot.resolve(input)).normalize();
        if(Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate))
            throw new IllegalArgumentException("Draft must be a regular MP4 file");
        Path draft=candidate.toRealPath();
        if(!draft.startsWith(base))throw new IllegalArgumentException("Only worker-local drafts can be inspected here");
        Path relative=base.relativize(draft);
        String name=draft.getFileName().toString();
        if(relative.getNameCount()!=4||!relative.getName(0).toString().endsWith("_local_draft")
                ||!"slot_001".equals(relative.getName(1).toString())
                ||!"local_drafts".equals(relative.getName(2).toString())
                ||!name.startsWith("NOT_SCANNED_")||!name.endsWith(".mp4"))
            throw new IllegalArgumentException("Not an isolated local draft");
        Path sidecar=draft.resolveSibling(name+".json");
        if(Files.isSymbolicLink(sidecar)||!Files.isRegularFile(sidecar))
            throw new IllegalArgumentException("Local draft metadata is missing");
        Map<String,Object>meta=Json.object(Json.read(sidecar));
        if(!"NOT_SCANNED".equals(meta.get("safetyStatus"))||!Boolean.FALSE.equals(meta.get("publicationAllowed"))
                ||!Boolean.FALSE.equals(meta.get("contentSafetyScanningEnabled"))
                ||!ContentSafetyService.hash(draft).equals(meta.get("videoSha256")))
            throw new IllegalArgumentException("Local draft metadata or hash is invalid");
        return draft;
    }

    /** Synthetic metadata and file identity checks; does not invoke a detector. */
    public static void selfTest()throws Exception{
        Path root=Files.createTempDirectory("anr-draft-inspection-");
        Path folder=root.resolve("output/remote_worker/test_local_draft/slot_001/local_drafts");
        Files.createDirectories(folder);
        Path draft=folder.resolve("NOT_SCANNED_test.mp4");
        Files.write(draft,new byte[]{1,2,3});
        Json.write(draft.resolveSibling(draft.getFileName()+".json"),Map.of(
                "safetyStatus","NOT_SCANNED","publicationAllowed",false,
                "contentSafetyScanningEnabled",false,"videoSha256",ContentSafetyService.hash(draft)));
        if(!draft.toRealPath().equals(validate(root,draft)))throw new AssertionError("Valid draft identity rejected");
        Files.write(draft,new byte[]{3,2,1});
        try{validate(root,draft);throw new AssertionError("Modified draft accepted");}
        catch(IllegalArgumentException expected){}
        System.out.println("PASS local draft inspection rejects modified media");
    }
}
