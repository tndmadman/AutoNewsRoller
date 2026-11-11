package autonewsroller.orchestration;

import java.util.Locale;

/**
 * Shared, fail-closed retry policy. Workers report a stable code; the controller
 * independently derives the retry decision and never trusts an asserted boolean.
 */
public final class FailurePolicy {
    private FailurePolicy(){}

    public enum Code {
        POLICY_BLOCKED(false),
        SOURCE_INVALID(false),
        STALE_EVIDENCE(false),
        CONFIGURATION_INVALID(false),
        SERVICE_UNAVAILABLE(true),
        OLLAMA_FAILURE(true),
        SCRIPT_VALIDATION_FAILED(false),
        TTS_FAILURE(true),
        COMFY_EXECUTION_FAILED(true),
        COMFY_TIMEOUT(true),
        IMAGE_QUALITY_REJECTED(false),
        IMAGE_SAFETY_REJECTED(false),
        SAFETY_SCANNER_UNAVAILABLE(false),
        VISUAL_ASSETS_MISSING(false),
        RENDER_FAILURE(true),
        FINAL_SAFETY_BLOCKED(false),
        UPLOAD_FAILURE(true),
        WORKER_LEASE_EXPIRED(true),
        UNKNOWN_FAILURE(true);

        public final boolean automaticRetry;
        Code(boolean automaticRetry){this.automaticRetry=automaticRetry;}
    }

    public record Decision(Code code,boolean automaticRetry,String action){}

    public static Decision fromCode(String reported,String stage,String message){
        if(reported!=null&&!reported.isBlank()){
            try{return decision(Code.valueOf(reported.trim().toUpperCase(Locale.ROOT)));}
            catch(IllegalArgumentException ignored){}
        }
        return classify(stage,message);
    }

    public static Decision classify(String stage,String message){
        String m=message==null?"":message.toLowerCase(Locale.ROOT);
        String s=stage==null?"":stage.toUpperCase(Locale.ROOT);
        // The ordering matters: scanner infrastructure failure is not an unsafe
        // detector verdict, and must never turn into a successful safety result.
        if(m.contains("scanner_unavailable")||m.contains("safety scanner unavailable")
                ||m.contains("mandatory safety model unavailable")
                ||m.contains("safety detector unavailable"))
            return decision(Code.SAFETY_SCANNER_UNAVAILABLE);
        if(m.contains("stale_framing_evidence")||m.contains("stale_evidence"))
            return decision(Code.STALE_EVIDENCE);
        if(m.contains("meme_editorial_review_required")||m.contains("policy_blocked")
                ||m.contains("safety-sensitive")||m.contains("unsafe or documentary-impersonating")
                ||m.contains("content_safety")||m.contains("final_safety_blocked"))
            return decision(Code.POLICY_BLOCKED);
        if(m.contains("visual_image_required")||m.contains("visual assets missing")
                ||m.contains("visual_asset_gate")||m.contains("no replaceable story visuals"))
            return decision(Code.VISUAL_ASSETS_MISSING);
        if(m.contains("configured checkpoint is unavailable")||m.contains("no imagecheckpoint configured")
                ||m.contains("invalid configuration")||m.contains("comfyui image generation disabled")
                ||m.contains("worker does not support")||m.contains("pipeline disabled"))
            return decision(Code.CONFIGURATION_INVALID);
        if(m.contains("meme_evidence_missing")||m.contains("meme_evidence_failure")
                ||m.contains("insufficient_framing_evidence"))
            return decision(Code.SOURCE_INVALID);
        if(m.contains("meme_script_validation_failed")||m.contains("meme_hook_failure")
                ||m.contains("framing_script_validation_failed")||m.contains("framing_script_format_failed")
                ||m.contains("framing_ollama_output_limit")||m.contains("script validation failed")
                ||m.contains("narration audio still too short after repair")
                ||m.contains("expected exactly six analysis segments"))
            return decision(Code.SCRIPT_VALIDATION_FAILED);
        if(m.contains("image safety rejected")||m.contains("unsafe image"))
            return decision(Code.IMAGE_SAFETY_REJECTED);
        if(m.contains("image quality rejected"))
            return decision(Code.IMAGE_QUALITY_REJECTED);
        if(m.contains("comfyui timed out")||m.contains("comfy_timeout"))
            return decision(Code.COMFY_TIMEOUT);
        if(m.contains("comfyui execution failed")||m.contains("comfy_execution_failed")
                ||m.contains("cuda out of memory"))
            return decision(Code.COMFY_EXECUTION_FAILED);
        if(m.contains("ollama")||m.contains("output hit token limit"))
            return decision(Code.OLLAMA_FAILURE);
        if(m.contains("video upload")||m.contains("upload http")||m.contains("upload_failure"))
            return decision(Code.UPLOAD_FAILURE);
        if(m.contains("service unavailable")||m.contains("connection refused"))
            return decision(Code.SERVICE_UNAVAILABLE);
        return switch(s){
            case "SCRIPT"->decision(Code.OLLAMA_FAILURE);
            case "TTS"->decision(Code.TTS_FAILURE);
            case "VISUALS"->decision(Code.COMFY_EXECUTION_FAILED);
            case "RENDER"->decision(Code.RENDER_FAILURE);
            case "AUDIT"->decision(Code.FINAL_SAFETY_BLOCKED);
            case "UPLOAD","APPROVED"->decision(Code.UPLOAD_FAILURE);
            default->decision(Code.UNKNOWN_FAILURE);
        };
    }

    private static Decision decision(Code code){
        String action=switch(code){
            case POLICY_BLOCKED,IMAGE_SAFETY_REJECTED,FINAL_SAFETY_BLOCKED->
                "Review the story and safety evidence; do not bypass approval.";
            case SOURCE_INVALID,STALE_EVIDENCE->"Refresh and verify source evidence.";
            case CONFIGURATION_INVALID->"Correct the worker configuration or checkpoint.";
            case SAFETY_SCANNER_UNAVAILABLE->"Restore and validate both mandatory detectors before retry.";
            case SCRIPT_VALIDATION_FAILED,IMAGE_QUALITY_REJECTED,VISUAL_ASSETS_MISSING->
                "Inspect the failed stage and change its inputs before a manual retry.";
            default->"Check the worker logs and retry within the configured budget.";
        };
        return new Decision(code,code.automaticRetry,action);
    }
}
