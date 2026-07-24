package com.igmo.admin.web.dto;

import java.util.List;

public record AdminImageGenerationOptionsResponse(
        List<String> models,
        List<String> imageSizes
) {
}
