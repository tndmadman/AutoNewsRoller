package autonewsroller.visuals;

import autonewsroller.orchestration.ProductionMode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Fail-closed last gate before FFmpeg. A procedural PNG does not count as a
 * ComfyUI illustration. Every requested image scene must use an accepted,
 * decodable, provenance-linked Comfy image. Framing and safety-sensitive
 * stories deliberately use rendered, validated evidence graphics instead.
 */
public final class VisualAssetGate {
    private VisualAssetGate() {}

    // Safety-sensitive reporting uses factual graphics rather than invented
    // depictions of people, victims, disasters, elections, or crime scenes.
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\\\b(war|battle|armed conflict|terroris\\\\w*|bombing|hostage|shooting|murder|rape|sexual assault|sex crime|trafficking|child abuse|minor\\\\w*|underage|fatal|dead|death|corpse|injur\\\\w*|medical|disaster|earthquake|flood|wildfire|evacuat\\\\w*|emergency|election|candidate|politic\\\\w*|accus\\\\w*|alleg\\\\w*|arrest|police|fraud|endorse\\\\w*)\\\\b");

    public static boolean sensitiveTopic(String headline, String summary) {
        return SENSITIVE.matcher(String.valueOf(headline) + " " + String.valueOf(summary)).find();
    }

    public static void requireReady(ProductionMode mode, boolean sensitiveTopic,
            List<Integer> requiredGeneratedScenes, int acceptedGenerated,
            List<Map<String,Object>> sources, List<Path> composed,
            List<Path> backgrounds, List<Path> overlays, int width, int height) {
        if (mode == null || sources == null || composed == null
                || backgrounds == null || overlays == null)
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: absent scene evidence");
        int count = composed.size();
        if (count < 1 || sources.size() != count || backgrounds.size() != count
                || overlays.size() != count)
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: scene list lengths disagree");
        if (width < 1 || height < 1)
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: invalid output geometry");

        for (int i = 0; i < count; i++) {
            requireImage(composed.get(i), width, height, "scene " + i + " composed");
            requireImage(backgrounds.get(i), width, height, "scene " + i + " background");
            if (overlays.get(i) != null)
                requireImage(overlays.get(i), width, height, "scene " + i + " overlay");
        }

        boolean illustrationRequired = mode != ProductionMode.FRAMING_EXPOSURE && !sensitiveTopic;
        if (illustrationRequired) {
            if (requiredGeneratedScenes == null || requiredGeneratedScenes.isEmpty())
                throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: no ComfyUI illustration scenes were scheduled");
            if (new HashSet<>(requiredGeneratedScenes).size() != requiredGeneratedScenes.size())
                throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: duplicate ComfyUI scene indexes");
            if (acceptedGenerated != requiredGeneratedScenes.size())
                throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: accepted " + acceptedGenerated
                        + "/" + requiredGeneratedScenes.size() + " ComfyUI illustrations");
            for (int scene : requiredGeneratedScenes) {
                if (scene < 0 || scene >= count)
                    throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: nonexistent scene " + scene);
                Map<String,Object> src = sources.get(scene);
                if (!"comfyui-generated-ai-illustration".equals(src.get("type"))
                        || !Boolean.TRUE.equals(src.get("aiIllustrationDisclosure")))
                    throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: scene " + scene
                            + " was replaced by a procedural fallback");
                requireMatch(src.get("path"), composed.get(scene), scene, "composed");
                requireMatch(src.get("backgroundPath"), backgrounds.get(scene), scene, "background");
                requireMatch(src.get("overlayPath"), overlays.get(scene), scene, "overlay");
                Object original = src.get("generatedPath");
                if (!(original instanceof String name) || name.isBlank())
                    throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: missing ComfyUI source for scene " + scene);
                requireDecodable(Path.of(name), "scene " + scene + " ComfyUI source");
            }
        } else {
            // Deliberately non-fabricated visuals remain required: no empty,
            // missing, corrupt, or silently substituted evidence slides.
            if (requiredGeneratedScenes != null && !requiredGeneratedScenes.isEmpty())
                throw new IllegalStateException("VISUAL_ASSETS_MISSING: unexpected ComfyUI scenes on graphics-only story");
            if (acceptedGenerated != 0)
                throw new IllegalStateException("VISUAL_ASSETS_MISSING: inconsistent generated image count");
            for (int i = 0; i < count; i++) {
                Object type = sources.get(i).get("type");
                if (!"procedural-card".equals(type) && !"procedural-animated-graphic".equals(type))
                    throw new IllegalStateException("VISUAL_ASSETS_MISSING: invalid graphics-only source " + i);
                requireMatch(sources.get(i).get("path"), composed.get(i), i, "graphic");
            }
        }
    }

    private static void requireMatch(Object declared, Path actual, int scene, String kind) {
        if (actual == null || !(declared instanceof String value) || value.isBlank()
                || !Path.of(value).toAbsolutePath().normalize()
                    .equals(actual.toAbsolutePath().normalize()))
            throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: scene " + scene
                    + " " + kind + " does not match its source evidence");
    }

    private static void requireImage(Path path, int width, int height, String context) {
        BufferedImage image = requireDecodable(path, context);
        if (image.getWidth() != width || image.getHeight() != height)
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: " + context
                    + " dimensions " + image.getWidth() + "x" + image.getHeight()
                    + " expected " + width + "x" + height);
    }

    private static BufferedImage requireDecodable(Path path, String context) {
        try {
            if (path == null || !Files.isRegularFile(path) || Files.size(path) < 32)
                throw new IOException("missing, empty, or truncated image");
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0)
                throw new IOException("image could not be decoded");
            return image;
        } catch (Exception error) {
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: " + context
                    + " not usable: " + error.getMessage(), error);
        }
    }
}
