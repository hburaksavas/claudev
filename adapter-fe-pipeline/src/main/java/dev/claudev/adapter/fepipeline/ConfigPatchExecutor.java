package dev.claudev.adapter.fepipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Key-&gt;value substitution against exactly one declared file — never a free-form find/replace
 * across the repo tree (docs/FE_PIPELINE_STEPS.md). Substitution syntax is {@code ${key}}, the
 * common convention for this kind of templating; {@code substitutions} not matching anything in
 * the file is not an error (a template evolving independently of any one patch's keys is normal).
 */
final class ConfigPatchExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path targetFile = Params.requirePath(params, "targetFile").toAbsolutePath().normalize();
        Map<String, String> substitutions = Params.optionalStringMap(params, "substitutions");

        if (!Files.isRegularFile(targetFile)) {
            throw new StepExecutionException("ConfigPatch targetFile does not exist: " + targetFile);
        }

        String content;
        try {
            content = Files.readString(targetFile);
        } catch (IOException e) {
            throw new StepExecutionException("Failed to read ConfigPatch targetFile: " + targetFile, e);
        }

        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            content = content.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        try {
            Files.writeString(targetFile, content);
        } catch (IOException e) {
            throw new StepExecutionException("Failed to write patched ConfigPatch targetFile: " + targetFile, e);
        }
    }
}
