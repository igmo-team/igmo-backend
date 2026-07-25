package com.igmo.admin.web;

import com.igmo.admin.application.AdminImageGenerationService;
import com.igmo.admin.web.dto.AdminImageGenerationOptionsResponse;
import com.igmo.admin.web.dto.AdminImageGenerationRequest;
import com.igmo.admin.web.dto.AdminImageGenerationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/image-generation")
@RequiredArgsConstructor
public class AdminImageGenerationController {

    private final AdminImageGenerationService adminImageGenerationService;

    @GetMapping("/options")
    public AdminImageGenerationOptionsResponse getOptions() {
        return adminImageGenerationService.getOptions();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public AdminImageGenerationResponse generate(@Valid @RequestBody AdminImageGenerationRequest request) {
        return adminImageGenerationService.generate(request);
    }
}
