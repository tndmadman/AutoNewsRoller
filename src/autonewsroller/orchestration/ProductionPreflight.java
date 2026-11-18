package autonewsroller.orchestration;

import autonewsroller.config.NewsConfig;
import autonewsroller.model.FactPackage;
import autonewsroller.safety.ContentSafetyPolicy;
import autonewsroller.script.MemeEditorialGuard;
import java.time.Instant;

/**
 * Pure, inexpensive checks performed before article enrichment, Ollama, or TTS.
 * A later check against refreshed evidence remains mandatory.
 */
public final class ProductionPreflight {
    private ProductionPreflight(){}

    public record Result(boolean eligible,String code,String reason,boolean recoverable,
                         String operatorAction,Instant checkedAt){
        public void requireEligible(){
            if(!eligible)throw new IllegalStateException(code+": "+reason);
        }
    }

    private static Result allow(){
        return new Result(true,"READY","Production preflight passed",false,"",Instant.now());
    }
    private static Result reject(String code,String reason,String action){
        return new Result(false,code,reason,false,action,Instant.now());
    }

    public static Result check(NewsPipeline.Candidate candidate,ProductionMode mode,
            boolean useComfy,boolean requireComfy,int requestedImages,boolean dryRun,NewsConfig cfg){
        if(candidate==null||candidate.cluster()==null||candidate.factPackage()==null)
            return reject("SOURCE_INVALID","Missing story or fact package","Refresh story evidence.");
        if(mode==null)
            return reject("CONFIGURATION_INVALID","Production mode is missing","Select a production mode.");
        if(candidate.cluster().articles==null||candidate.cluster().articles.isEmpty())
            return reject("SOURCE_INVALID","The story has no source articles","Refresh article evidence.");
        FactPackage fp=candidate.factPackage();
        if(fp.storyId().isBlank()||fp.headline().isBlank())
            return reject("SOURCE_INVALID","The story has no usable identity or headline","Refresh source evidence.");

        Result policy=checkPolicy(fp,mode);
        if(!policy.eligible())return policy;
        boolean evidenceGraphicsOnly=requiresEvidenceGraphics(fp,mode);
        if(mode==ProductionMode.MEME_RAGEBAIT){
            if(!cfg.getBool("memePipelineEnabled",true))
                return reject("CONFIGURATION_INVALID","Meme production is disabled","Enable this production mode.");
            String risk=MemeEditorialGuard.reason(candidate.cluster());
            if(!risk.isBlank())
                return reject("POLICY_BLOCKED","Meme editorial review required: "+risk,
                        "Review the story; do not generate a victim-centered meme.");
        }
        if(mode==ProductionMode.FRAMING_EXPOSURE){
            if(!cfg.getBool("politicalExposureEnabled",true))
                return reject("CONFIGURATION_INVALID","Framing exposure is disabled","Enable this production mode.");
            if(useComfy&&!dryRun)
                return reject("CONFIGURATION_INVALID","Framing exposure requires evidence graphics, not ComfyUI imagery",
                        "Disable ComfyUI for this mode.");
        }else if(!dryRun&&!useComfy&&!evidenceGraphicsOnly){
            return reject("CONFIGURATION_INVALID","ComfyUI illustrations are mandatory for non-sensitive stories in this production mode",
                    "Enable required ComfyUI illustrations.");
        }
        if(requireComfy&&!useComfy&&!dryRun&&!evidenceGraphicsOnly)
            return reject("CONFIGURATION_INVALID","ComfyUI is required but disabled","Fix the production settings.");
        if(!dryRun&&(requestedImages<1||requestedImages>40))
            return reject("CONFIGURATION_INVALID","Required image count is outside the supported range (1-40)",
                    "Correct the requested image count.");
        if(!dryRun&&useComfy&&!evidenceGraphicsOnly){
            int width=cfg.getInt("imageWidth",832),height=cfg.getInt("imageHeight",1472);
            int steps=cfg.getInt("imageSteps",32);
            if(width<256||height<256||width>4096||height>4096||width%8!=0||height%8!=0||steps<1||steps>150)
                return reject("CONFIGURATION_INVALID","Invalid ComfyUI dimensions or sampler steps",
                        "Select supported dimensions divisible by eight and valid step count.");
        }
        return allow();
    }

    /**
     * Safety-sensitive sources are not rejected merely because normal videos
     * request AI illustrations. They must instead use the already supported
     * source-grounded, non-generative evidence-card renderer and still undergo
     * mandatory full-video scans and independent controller approval.
     */
    public static boolean requiresEvidenceGraphics(FactPackage fp,ProductionMode mode){
        return fp!=null&&mode!=null&&mode!=ProductionMode.FRAMING_EXPOSURE
                &&ContentSafetyPolicy.sensitive(fp.headline()+" "+fp.summary());
    }

    public static Result checkPolicy(FactPackage fp,ProductionMode mode){
        if(fp==null)return reject("SOURCE_INVALID","Fact package is missing","Refresh source evidence.");
        if(mode==null)return reject("CONFIGURATION_INVALID","Production mode is missing","Choose a production mode.");
        if(requiresEvidenceGraphics(fp,mode))
            return new Result(true,"EVIDENCE_GRAPHICS_REQUIRED",
                    "Safety-sensitive reporting must use factual evidence graphics; no AI event depictions",
                    false,"Use validated source graphics and retain both mandatory scanners.",Instant.now());
        return allow();
    }
}
