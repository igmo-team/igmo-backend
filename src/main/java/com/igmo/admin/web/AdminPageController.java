package com.igmo.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String adminHome() {
        return "forward:/admin/index.html";
    }

    @GetMapping({"/admin/image-generation", "/admin/image-generation/"})
    public String imageGenerationPage() {
        return "forward:/admin/image-generation/index.html";
    }
}
