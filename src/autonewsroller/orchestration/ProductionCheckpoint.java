package autonewsroller.orchestration;

import autonewsroller.safety.ContentSafetyService;
import autonewsroller.util.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Atomic, content-addressed, per-job artifact manifest. Entries never supply
 * paths to read: callers supply fixed workspace-local paths for every reuse.
 */
public final class ProductionCheckpoint {
    private static final int SCHEMA=1;
    private final Path workspace,artifactRoot,manifest;
    private final String contract;
    private final Map<String,Object> entries=new LinkedHashMap<>();

    public ProductionCheckpoint(Path workspace,String contract)throws Exception{
        this(workspace,workspace,contract);
    }

    /**
     * A job may stage its approved MP4 in a sibling final_videos directory.
     * All artifact paths must remain under this explicit trusted batch root.
     */
    public ProductionCheckpoint(Path workspace,Path artifactRoot,String contract)throws Exception{
        this.workspace=workspace.toAbsolutePath().normalize();
        this.artifactRoot=artifactRoot.toAbsolutePath().normalize();
        if(!this.workspace.startsWith(this.artifactRoot))
            throw new IllegalArgumentException("Checkpoint workspace outside authorized artifact root");
        this.manifest=this.workspace.resolve("production_checkpoint.json");
        this.contract=Objects.requireNonNull(contract);
        Files.createDirectories(this.workspace);
        if(Files.isRegularFile(manifest)){
            try{
                Map<String,Object>state=Json.object(Json.read(manifest));
                if(state.get("schema") instanceof Number n&&n.intValue()==SCHEMA
                        &&contract.equals(state.get("contract"))
                        &&ContentSafetyService.POLICY.equals(state.get("safetyPolicy"))
                        &&state.get("entries") instanceof Map<?,?> old){
                    for(var item:old.entrySet())if(item.getValue() instanceof Map<?,?>)
                        entries.put(String.valueOf(item.getKey()),item.getValue());
                }
            }catch(Exception malformed){
                // Corrupt manifests are never accepted. Generated files remain
                // in the workspace for operator inspection, not for blind reuse.
                entries.clear();
            }
        }
        persist();
    }

    public synchronized Map<String,Object> validated(String stage,String fingerprint,Map<String,Path> files){
        Object object=entries.get(stage);
        if(!(object instanceof Map<?,?>raw))return null;
        Map<String,Object>saved=Json.object(raw);
        if(!fingerprint.equals(saved.get("fingerprint")))return null;
        if(!ContentSafetyService.POLICY.equals(saved.get("safetyPolicy")))return null;
        if(!(saved.get("files") instanceof Map<?,?> hashes))return null;
        if(hashes.size()!=files.size())return null;
        try{
            for(var file:files.entrySet()){
                Path path=inside(file.getValue());
                if(!Files.isRegularFile(path)||Files.isSymbolicLink(path)
                        ||!Objects.equals(String.valueOf(hashes.get(file.getKey())),ContentSafetyService.hash(path)))
                    return null;
            }
            return saved.get("metadata") instanceof Map<?,?>meta
                    ?new LinkedHashMap<>(Json.object(meta)):new LinkedHashMap<>();
        }catch(Exception invalid){return null;}
    }

    public synchronized Map<String,Object> metadata(String stage,String fingerprint){
        Object value=entries.get(stage);
        if(!(value instanceof Map<?,?>raw))return null;
        Map<String,Object>saved=Json.object(raw);
        if(!fingerprint.equals(saved.get("fingerprint"))
                ||!ContentSafetyService.POLICY.equals(saved.get("safetyPolicy")))return null;
        return saved.get("metadata") instanceof Map<?,?>meta
                ?new LinkedHashMap<>(Json.object(meta)):null;
    }

    public synchronized void accept(String stage,String fingerprint,
            Map<String,Path> files,Map<String,Object> metadata)throws Exception{
        Map<String,Object>hashes=new LinkedHashMap<>();
        for(var file:files.entrySet()){
            Path path=inside(file.getValue());
            if(!Files.isRegularFile(path)||Files.isSymbolicLink(path))
                throw new IllegalArgumentException("Checkpoint artifact is not a regular workspace file: "+file.getKey());
            hashes.put(file.getKey(),ContentSafetyService.hash(path));
        }
        Map<String,Object>value=new LinkedHashMap<>();
        value.put("fingerprint",fingerprint);
        value.put("safetyPolicy",ContentSafetyService.POLICY);
        value.put("files",hashes);
        value.put("metadata",metadata==null?Map.of():new LinkedHashMap<>(metadata));
        value.put("acceptedAt",Instant.now().toString());
        entries.put(stage,value);
        persist();
    }

    public synchronized int completedStages(){return entries.size();}

    private Path inside(Path file){
        Path path=file.toAbsolutePath().normalize();
        if(!path.startsWith(artifactRoot))throw new IllegalArgumentException("Artifact outside authorized batch root");
        return path;
    }
    private void persist()throws Exception{
        Map<String,Object>state=new LinkedHashMap<>();
        state.put("schema",SCHEMA);state.put("contract",contract);
        state.put("safetyPolicy",ContentSafetyService.POLICY);
        state.put("entries",entries);
        Json.write(manifest,state);
    }
}
